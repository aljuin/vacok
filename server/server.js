'use strict';
/*
 * Сервер мессенджера. Без внешних зависимостей: нужен только Node.js 22.13 или новее.
 * Запуск:  node server.js        (порт по умолчанию 3000)
 * Настройки через переменные окружения: PORT, DATA_DIR, ALLOW_ORIGIN, MAX_UPLOAD_MB.
 * Протокол: HTTP + JSON, доставка в реальном времени через SSE (GET /api/stream).
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

const PORT = +process.env.PORT || 3000;
const DATA = process.env.DATA_DIR || path.join(__dirname, 'data');
const ORIGINS = (process.env.ALLOW_ORIGIN || '*').split(',').map(s => s.trim()).filter(Boolean);
const MAX_UPLOAD = (+process.env.MAX_UPLOAD_MB || 80) * 1024 * 1024;
const PUBLIC = path.join(__dirname, 'public');
fs.mkdirSync(path.join(DATA, 'media'), { recursive: true });

const db = new DatabaseSync(path.join(DATA, 'messenger.db'));
db.exec(`
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY AUTOINCREMENT, login TEXT UNIQUE COLLATE NOCASE, handle TEXT UNIQUE COLLATE NOCASE, name TEXT, salt TEXT, hash TEXT, created INTEGER);
CREATE TABLE IF NOT EXISTS sessions(th TEXT PRIMARY KEY, user_id INTEGER, created INTEGER);
CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT, from_id INTEGER, to_id INTEGER, cid TEXT, kind TEXT, text TEXT, media TEXT, media_id TEXT, ts INTEGER, read_at INTEGER);
CREATE INDEX IF NOT EXISTS m_to ON messages(to_id, id);
CREATE INDEX IF NOT EXISTS m_from ON messages(from_id, id);
CREATE TABLE IF NOT EXISTS media(id TEXT PRIMARY KEY, owner INTEGER, mime TEXT, size INTEGER, ts INTEGER);
CREATE TABLE IF NOT EXISTS push(token TEXT PRIMARY KEY, user_id INTEGER, platform TEXT, ts INTEGER);
`);
// Подготовленные запросы кешируются: раньше SQL компилировался заново при каждом запросе
const stmtCache = new Map();
const Q = sql => { let st = stmtCache.get(sql); if (!st) { st = db.prepare(sql); stmtCache.set(sql, st); } return st; };
db.exec('PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=3000; PRAGMA temp_store=MEMORY;');
// Миграция: ответы на сообщения (цитата). Для уже существующей базы колонка добавляется один раз.
try { db.exec('ALTER TABLE messages ADD COLUMN reply TEXT'); } catch (_) { /* колонка уже есть */ }

const LOGIN_RE = /^[\w.\-а-яё]{3,20}$/i, HANDLE_RE = /^[a-z][a-z0-9_]{2,19}$/;
const ipOf = req => {
  const ra = req.socket.remoteAddress || '', xf = req.headers['x-forwarded-for'];
  return (ra === '127.0.0.1' || ra === '::1' || ra === '::ffff:127.0.0.1') && xf ? String(xf).split(',')[0].trim() : ra;
};
const pub = u => ({ id: u.id, login: u.login, handle: u.handle, name: u.name || u.login });
const sha = s => crypto.createHash('sha256').update(s).digest('hex');
const hashPw = (pw, salt) => crypto.scryptSync(pw, salt, 32).toString('hex');
const safeEq = (a, b) => { const x = Buffer.from(a), y = Buffer.from(b); return x.length === y.length && crypto.timingSafeEqual(x, y); };

/* ---------- простая защита от перебора ---------- */
const hits = new Map();
function limited(key, max, windowMs) {
  const now = Date.now(), arr = (hits.get(key) || []).filter(t => now - t < windowMs);
  arr.push(now); hits.set(key, arr); return arr.length > max;
}
setInterval(() => { const now = Date.now(); for (const [k, v] of hits) if (!v.some(t => now - t < 600000)) hits.delete(k); }, 600000).unref();

/* ---------- SSE-подключения ---------- */
const streams = new Map(); // userId -> Set<res>
const isOnline = id => (streams.get(id) || new Set()).size > 0;
function emit(uid, event, data) {
  const set = streams.get(uid); if (!set) return;
  const chunk = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of set) res.write(chunk);
}
const peersOf = id => Q('SELECT DISTINCT CASE WHEN from_id=? THEN to_id ELSE from_id END p FROM messages WHERE from_id=? OR to_id=?').all(id, id, id).map(r => r.p);
const broadcastPresence = id => { const on = isOnline(id); for (const p of peersOf(id)) emit(p, 'presence', { id, online: on }); };

/* ---------- утилиты HTTP ---------- */
function cors(req, res) {
  const o = req.headers.origin;
  if (o && (ORIGINS.includes('*') || ORIGINS.includes(o))) {
    res.setHeader('Access-Control-Allow-Origin', ORIGINS.includes('*') ? '*' : o);
    res.setHeader('Vary', 'Origin');
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  }
}
const send = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'X-Content-Type-Options': 'nosniff' }); res.end(JSON.stringify(obj)); };
const fail = (res, code, error) => send(res, code, { error });
function readJson(req, limit = 200 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', c => { size += c.length; if (size > limit) { reject({ code: 413, msg: 'Слишком большой запрос' }); req.destroy(); } else chunks.push(c); });
    req.on('end', () => { try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}); } catch { reject({ code: 400, msg: 'Неверный JSON' }); } });
    req.on('error', () => reject({ code: 400, msg: 'Ошибка запроса' }));
  });
}
function authUser(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || ''); if (!m) return null;
  const s = Q('SELECT user_id, created FROM sessions WHERE th=?').get(sha(m[1]));
  if (!s) return null;
  if (Date.now() - s.created > 90 * 864e5) { Q('DELETE FROM sessions WHERE th=?').run(sha(m[1])); return null; }
  return Q('SELECT * FROM users WHERE id=?').get(s.user_id) || null;
}
function newSession(uid) {
  const token = crypto.randomBytes(32).toString('base64url');
  Q('INSERT INTO sessions(th,user_id,created) VALUES(?,?,?)').run(sha(token), uid, Date.now());
  return token;
}
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };
function serveStatic(req, res, pathname) {
  let f = path.normalize(path.join(PUBLIC, pathname === '/' ? 'landing.html' : pathname));
  if (!f.startsWith(PUBLIC)) return fail(res, 403, 'Запрещено');
  fs.stat(f, (e, st) => {
    if (e || !st.isFile()) return fail(res, 404, 'Не найдено');
    const type = MIME[path.extname(f)] || 'application/octet-stream';
    if (path.extname(f) === '.html') {   // веб-клиент, лежащий рядом с сервером, сам узнаёт адрес API
      return fs.readFile(f, 'utf8', (er, html) => {
        if (er) return fail(res, 500, 'Ошибка чтения файла');
        res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-cache' });
        res.end(html.replace('<head>', '<head><script>window.MESSENGER_SERVER=location.origin</script>'));
      });
    }
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-cache' });
    fs.createReadStream(f).pipe(res);
  });
}

/* ---------- версия приложения для проверки обновлений ---------- */
const VERSION_FILE = path.join(PUBLIC, 'downloads', 'version.json');
function serveVersion(req, res) {
  cors(req, res);
  fs.readFile(VERSION_FILE, 'utf8', (e, txt) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-cache');
    if (e) return res.end(JSON.stringify({ versionCode: 0 }));
    try { res.end(JSON.stringify(JSON.parse(txt))); } catch (_) { res.end(JSON.stringify({ versionCode: 0 })); }
  });
}

/* ---------- скачивание приложения ---------- */
const APK = path.join(PUBLIC, 'downloads', 'messenger.apk');
function serveApk(req, res) {
  fs.stat(APK, (e, st) => {
    if (e || !st.isFile()) return fail(res, 404, 'Файл приложения ещё не загружен на сервер');
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': st.size,
      'Content-Disposition': 'attachment; filename="messenger.apk"',
      'Cache-Control': 'no-cache',
    });
    fs.createReadStream(APK).pipe(res);
  });
}

/* ---------- маршруты API ---------- */
const routes = {
  async 'POST /api/register'(req, res) {
    if (limited('reg:' + ipOf(req), 10, 3600000)) return fail(res, 429, 'Слишком много попыток, попробуйте позже');
    const b = await readJson(req);
    const login = String(b.login || '').trim(), handle = String(b.handle || '').trim().replace(/^@/, '').toLowerCase(), password = String(b.password || '');
    if (!LOGIN_RE.test(login)) return fail(res, 400, 'Логин: 3–20 символов, буквы, цифры, _ . -');
    if (!HANDLE_RE.test(handle)) return fail(res, 400, 'Короткое имя: 3–20 символов, латиница, цифры и _, первая — буква');
    if (password.length < 6 || password.length > 64) return fail(res, 400, 'Пароль: от 6 до 64 символов');
    if (Q('SELECT 1 FROM users WHERE login=?').get(login)) return fail(res, 409, 'Такой логин уже занят');
    if (Q('SELECT 1 FROM users WHERE handle=?').get(handle)) return fail(res, 409, 'Имя @' + handle + ' уже занято');
    const salt = crypto.randomBytes(16).toString('hex');
    const r = Q('INSERT INTO users(login,handle,name,salt,hash,created) VALUES(?,?,?,?,?,?)').run(login, handle, login, salt, hashPw(password, salt), Date.now());
    const u = Q('SELECT * FROM users WHERE id=?').get(r.lastInsertRowid);
    send(res, 201, { token: newSession(u.id), user: pub(u) });
  },
  async 'POST /api/login'(req, res) {
    const b = await readJson(req), login = String(b.login || '').trim(), password = String(b.password || '');
    if (limited('login:' + ipOf(req) + ':' + login.toLowerCase(), 8, 300000)) return fail(res, 429, 'Слишком много попыток входа, подождите 5 минут');
    const u = Q('SELECT * FROM users WHERE login=?').get(login);
    if (!u) return fail(res, 404, 'Аккаунт не найден. Сначала зарегистрируйтесь');
    if (!safeEq(hashPw(password, u.salt), u.hash)) return fail(res, 401, 'Неверный пароль');
    send(res, 200, { token: newSession(u.id), user: pub(u) });
  },
  async 'POST /api/logout'(req, res, me) {
    const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
    if (m) Q('DELETE FROM sessions WHERE th=?').run(sha(m[1]));
    send(res, 200, { ok: true });
  },
  async 'GET /api/me'(req, res, me) { send(res, 200, { user: pub(me) }); },
  async 'POST /api/handle'(req, res, me) {
    const handle = String((await readJson(req)).handle || '').trim().replace(/^@/, '').toLowerCase();
    if (!HANDLE_RE.test(handle)) return fail(res, 400, 'Короткое имя: 3–20 символов, латиница, цифры и _, первая — буква');
    if (Q('SELECT 1 FROM users WHERE handle=? AND id<>?').get(handle, me.id)) return fail(res, 409, 'Имя @' + handle + ' уже занято');
    Q('UPDATE users SET handle=? WHERE id=?').run(handle, me.id);
    send(res, 200, { user: pub(Q('SELECT * FROM users WHERE id=?').get(me.id)) });
  },
  async 'POST /api/profile'(req, res, me) {
    const name = String((await readJson(req)).name || '').trim().slice(0, 24) || me.login;
    Q('UPDATE users SET name=? WHERE id=?').run(name, me.id);
    send(res, 200, { user: pub(Q('SELECT * FROM users WHERE id=?').get(me.id)) });
  },
  async 'GET /api/users'(req, res, me, url) {
    const q = (url.searchParams.get('q') || '').trim().replace(/^@/, '').toLowerCase().replace(/[%_]/g, '');
    if (q.length < 2) return send(res, 200, []);
    const rows = Q(`SELECT * FROM users WHERE id<>? AND (handle LIKE ? OR login LIKE ?) ORDER BY (handle=?) DESC, handle LIMIT 20`).all(me.id, q + '%', q + '%', q);
    send(res, 200, rows.map(u => ({ ...pub(u), online: isOnline(u.id) })));
  },
  async 'GET /api/users/by-handle'(req, res, me, url) {
    const h = (url.searchParams.get('h') || '').trim().replace(/^@/, '').toLowerCase();
    const u = Q('SELECT * FROM users WHERE handle=?').get(h);
    if (!u) return fail(res, 404, 'Пользователь @' + h + ' не найден');
    send(res, 200, { ...pub(u), online: isOnline(u.id) });
  },
  async 'POST /api/messages'(req, res, me) {
    const b = await readJson(req), to = +b.to, kind = b.kind || 'text', cid = String(b.cid || '').slice(0, 64);
    if (!['text', 'img', 'vid', 'voice'].includes(kind)) return fail(res, 400, 'Неизвестный тип сообщения');
    const peer = Q('SELECT * FROM users WHERE id=?').get(to);
    if (!peer || peer.id === me.id) return fail(res, 404, 'Получатель не найден');
    if (cid) { const dup = Q('SELECT id,ts FROM messages WHERE from_id=? AND cid=?').get(me.id, cid); if (dup) return send(res, 200, { id: dup.id, ts: dup.ts }); }
    let text = '', media = null, mediaId = null;
    if (kind === 'text') {
      text = String(b.text || '').slice(0, 4000);
      if (!text.trim()) return fail(res, 400, 'Пустое сообщение');
    } else {
      const m = b.media || {}; mediaId = String(m.rid || '');
      const f = Q('SELECT * FROM media WHERE id=? AND owner=?').get(mediaId, me.id);
      if (!f) return fail(res, 400, 'Файл не найден: сначала загрузите его');
      media = { rid: mediaId, w: +m.w || 0, h: +m.h || 0, dur: +m.dur || 0, wave: Array.isArray(m.wave) ? m.wave.slice(0, 64).map(Number) : undefined, circ: m.circ ? 1 : 0 };
    }
    const rp = b.reply && typeof b.reply === 'object' ? { n: String(b.reply.n || '').slice(0, 64), t: String(b.reply.t || '').slice(0, 200) } : null;
    const ts = Date.now();
    const r = Q('INSERT INTO messages(from_id,to_id,cid,kind,text,media,media_id,ts,reply) VALUES(?,?,?,?,?,?,?,?,?)').run(me.id, peer.id, cid, kind, text, media ? JSON.stringify(media) : null, mediaId, ts, rp ? JSON.stringify(rp) : null);
    const msg = { id: +r.lastInsertRowid, from: me.id, to: peer.id, cid, kind, text, media, ts, reply: rp || undefined };
    emit(peer.id, 'message', { ...msg, peer: pub(me) });
    emit(me.id, 'message', { ...msg, peer: pub(peer) });
    // Место для push-уведомлений (FCM), если получатель не в сети — см. GUIDE.md, шаг «Push»
    send(res, 200, { id: msg.id, ts });
  },
  async 'GET /api/sync'(req, res, me, url) {
    const after = +url.searchParams.get('after') || 0, LIMIT = 500;
    const rows = Q('SELECT * FROM messages WHERE id>? AND (from_id=? OR to_id=?) ORDER BY id LIMIT ?').all(after, me.id, me.id, LIMIT + 1);
    const more = rows.length > LIMIT; if (more) rows.pop();
    const peers = {};
    const messages = rows.map(r => {
      const pid = r.from_id === me.id ? r.to_id : r.from_id;
      if (!peers[pid]) { const u = Q('SELECT * FROM users WHERE id=?').get(pid); if (u) peers[pid] = { ...pub(u), online: isOnline(pid) }; }
      return { id: r.id, from: r.from_id, to: r.to_id, cid: r.cid, kind: r.kind, text: r.text, media: r.media ? JSON.parse(r.media) : null, ts: r.ts, reply: r.reply ? JSON.parse(r.reply) : undefined };
    });
    const reads = Q('SELECT to_id by, MAX(id) upTo FROM messages WHERE from_id=? AND read_at IS NOT NULL GROUP BY to_id').all(me.id);
    send(res, 200, { messages, peers, reads: reads.map(r => ({ by: r.by, upTo: r.upTo })), more });
  },
  async 'POST /api/read'(req, res, me) {
    const b = await readJson(req), peer = +b.peer, upTo = +b.upTo;
    const r = Q('UPDATE messages SET read_at=? WHERE to_id=? AND from_id=? AND id<=? AND read_at IS NULL').run(Date.now(), me.id, peer, upTo);
    if (r.changes > 0) emit(peer, 'read', { by: me.id, upTo });
    send(res, 200, { ok: true });
  },
  async 'POST /api/typing'(req, res, me) {
    const to = +(await readJson(req)).to;
    if (limited('typing:' + me.id + ':' + to, 6, 5000)) return send(res, 200, { ok: true });
    emit(to, 'typing', { from: me.id }); send(res, 200, { ok: true });
  },
  async 'POST /api/push-token'(req, res, me) {
    const b = await readJson(req), token = String(b.token || '').slice(0, 512);
    if (token) Q('INSERT INTO push(token,user_id,platform,ts) VALUES(?,?,?,?) ON CONFLICT(token) DO UPDATE SET user_id=excluded.user_id, ts=excluded.ts').run(token, me.id, String(b.platform || '').slice(0, 20), Date.now());
    send(res, 200, { ok: true });
  },
  async 'POST /api/upload'(req, res, me) {
    const len = +req.headers['content-length'] || 0;
    if (len > MAX_UPLOAD) { req.resume(); return fail(res, 413, 'Файл слишком большой'); }
    const mime = String(req.headers['content-type'] || 'application/octet-stream').split(';')[0].slice(0, 100);
    if (!/^(image|video|audio)\//.test(mime)) { req.resume(); return fail(res, 415, 'Разрешены только изображения, видео и аудио'); }
    const id = crypto.randomBytes(12).toString('hex'), file = path.join(DATA, 'media', id);
    let size = 0, aborted = false; const out = fs.createWriteStream(file);
    req.on('data', c => { size += c.length; if (size > MAX_UPLOAD && !aborted) { aborted = true; out.destroy(); fs.unlink(file, () => {}); fail(res, 413, 'Файл слишком большой'); req.destroy(); } });
    req.pipe(out);
    out.on('finish', () => {
      if (aborted) return;
      if (size === 0) { fs.unlink(file, () => {}); return fail(res, 400, 'Пустой файл'); }
      Q('INSERT INTO media(id,owner,mime,size,ts) VALUES(?,?,?,?,?)').run(id, me.id, mime, size, Date.now());
      send(res, 200, { id });
    });
    out.on('error', () => { if (!aborted) fail(res, 500, 'Не удалось сохранить файл'); });
  },
  async 'GET /api/stream'(req, res, me) {
    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache, no-transform', 'Connection': 'keep-alive', 'X-Accel-Buffering': 'no' });
    req.socket.setTimeout(0); req.socket.setNoDelay(true);
    const wasOnline = isOnline(me.id);
    if (!streams.has(me.id)) streams.set(me.id, new Set());
    streams.get(me.id).add(res);
    res.write(`event: hello\ndata: ${JSON.stringify({ me: pub(me), online: peersOf(me.id).filter(isOnline) })}\n\n`);
    if (!wasOnline) broadcastPresence(me.id);
    const ping = setInterval(() => res.write(': ping\n\n'), 20000);
    req.on('close', () => {
      clearInterval(ping);
      const set = streams.get(me.id); if (set) { set.delete(res); if (!set.size) { streams.delete(me.id); broadcastPresence(me.id); } }
    });
  },
};
const OPEN = new Set(['POST /api/register', 'POST /api/login']);

const server = http.createServer(async (req, res) => {
  cors(req, res);
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  const url = new URL(req.url, 'http://x');
  try {
    if (url.pathname.startsWith('/api/media/') && req.method === 'GET') {
      const me = authUser(req); if (!me) return fail(res, 401, 'Нужен вход');
      const id = url.pathname.slice(11).replace(/[^a-f0-9]/g, '');
      const f = Q('SELECT * FROM media WHERE id=?').get(id);
      const ok = f && (f.owner === me.id || Q('SELECT 1 FROM messages WHERE media_id=? AND (from_id=? OR to_id=?)').get(id, me.id, me.id));
      if (!ok) return fail(res, 404, 'Файл не найден');
      res.writeHead(200, { 'Content-Type': f.mime, 'Content-Length': f.size, 'Cache-Control': 'private, max-age=31536000, immutable', 'X-Content-Type-Options': 'nosniff' });
      return fs.createReadStream(path.join(DATA, 'media', id)).pipe(res);
    }
    if (req.method === 'GET' && url.pathname === '/api/app-version') return serveVersion(req, res);
    if (url.pathname.startsWith('/api/')) {
      const key = req.method + ' ' + url.pathname, h = routes[key];
      if (!h) return fail(res, 404, 'Нет такого метода');
      let me = null;
      if (!OPEN.has(key)) { me = authUser(req); if (!me) return fail(res, 401, 'Нужен вход'); }
      return await h(req, res, me, url);
    }
    if (req.method === 'GET' && (url.pathname === '/app' || url.pathname === '/app/')) return serveStatic(req, res, '/index.html');
    if (req.method === 'GET' && url.pathname === '/download/messenger.apk') return serveApk(req, res);
    if (req.method === 'GET') return serveStatic(req, res, decodeURIComponent(url.pathname));
    fail(res, 405, 'Метод не поддерживается');
  } catch (e) {
    if (e && e.code && e.msg) return fail(res, e.code, e.msg);
    console.error(e); if (!res.headersSent) fail(res, 500, 'Ошибка сервера'); else res.end();
  }
});
server.requestTimeout = 0; server.headersTimeout = 30000;
// Долгоживущие соединения с nginx: не закрываем их раньше времени, экономим рукопожатия
server.keepAliveTimeout = 65000;
server.headersTimeout = 66000;
server.listen(PORT, () => console.log(`Мессенджер: сервер запущен на порту ${PORT}, данные: ${DATA}`));
process.on('SIGTERM', () => { server.close(); db.close(); process.exit(0); });
