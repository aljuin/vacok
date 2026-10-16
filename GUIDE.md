# Мессенджер: от прототипа до рабочего приложения

## Что в наборе

```
MessengerKit/
├── web/index.html        веб-интерфейс (один файл)
├── server/               сервер (Node.js, без зависимостей)
│   ├── server.js
│   ├── package.json
│   └── public/index.html копия интерфейса: сервер отдаёт его сам
└── android/              проект для Android Studio (оболочка с WebView)
    └── app/src/main/assets/index.html   тот же интерфейс внутри приложения
```

Как это работает: интерфейс (HTML) один и тот же в браузере и в приложении. Если адрес сервера не задан, он работает в демо-режиме: всё хранится на устройстве. Если адрес задан, регистрация, сообщения, файлы и уведомления идут через ваш сервер.

**Что проверено:** интерфейс и сервер проверены автоматически в Chromium (два пользователя: регистрация, поиск по @имени, сообщения, «прочитано», «печатает», фото, голосовые, отправка после потери связи, перезагрузка страницы).
**Что НЕ проверено:** сборка проекта Android (в моей среде нет Android SDK), работа на реальном телефоне (микрофон, push), работа за вашим доменом. Поэтому шаги 2 и 3 ниже — это ваша первая проверка; если Android Studio покажет ошибку, пришлите её текст.

---

## Шаг 1. Запустить сервер на своём компьютере (10 минут)

1. Установите Node.js **22.13 или новее** (nodejs.org, версия LTS/Current). Проверка: `node -v`.
2. Откройте терминал в папке `MessengerKit/server` и выполните: `node server.js`
3. Откройте в браузере `http://localhost:3000`. Это интерфейс, уже подключённый к серверу.
4. Зарегистрируйте двух пользователей (во втором окне браузера или в режиме инкогнито), например `@anna` и `@bob`.
5. От `@anna` нажмите «+», введите `@bob`, отправьте сообщение. У `@bob` придёт уведомление.

Данные сервера лежат в папке `server/data` (база `messenger.db` и папка `media`). Чтобы начать с чистого листа, остановите сервер и удалите эту папку.

Настройки (переменные окружения): `PORT` (по умолчанию 3000), `DATA_DIR` (где хранить данные), `ALLOW_ORIGIN` (какие сайты могут обращаться к API, через запятую; `*` = любые), `MAX_UPLOAD_MB` (лимит файла, по умолчанию 80).

## Шаг 2. Собрать приложение в Android Studio

1. Установите Android Studio (developer.android.com/studio). При первом запуске дождитесь загрузки Android SDK.
2. **File → Open** и выберите папку `MessengerKit/android`. Дождитесь, пока Gradle закончит синхронизацию (внизу справа; в первый раз это несколько минут и нужен интернет).
3. Запустите: выберите эмулятор или подключённый телефон (на телефоне включите «Отладку по USB») и нажмите зелёную кнопку **Run**. Без адреса сервера приложение откроется в демо-режиме.
4. **Подключение к серверу с вашего компьютера.** Откройте `android/gradle.properties` и впишите строку:
   - эмулятор: `serverUrl=http://10.0.2.2:3000` (так эмулятор видит ваш компьютер);
   - настоящий телефон в той же Wi-Fi сети: `serverUrl=http://192.168.1.50:3000` (замените на IP компьютера; на компьютере разрешите входящие подключения к порту 3000 в брандмауэре).
   Нажмите **Sync Now** и запустите ещё раз. Обычный `http` работает только в отладочной сборке; в релизной нужен `https` (шаг 3).
5. **Своё имя пакета.** Замените `com.example.messenger` на свой (например `com.mycompany.chat`) в трёх местах: `app/build.gradle.kts` (`namespace` и `applicationId`) и папки `app/src/main/java/com/example/messenger` (в Android Studio: правый клик по пакету → **Refactor → Rename**, а в первой строке `MainActivity.kt` поправьте `package`). Имя пакета нельзя менять после публикации в Google Play.
6. **Название и иконка.** Название: `app/src/main/res/values/strings.xml`. Иконка: правый клик на `res` → **New → Image Asset**.

Что умеет оболочка: микрофон (запрос разрешения при первой записи), выбор фото/видео/аудио, уведомления, кнопка «Назад» (закрывает экраны внутри приложения), ссылки открываются в браузере телефона, клавиатура не перекрывает поле ввода, цвет системных панелей меняется вместе с темой.

## Шаг 3. Настоящий сервер в интернете

Нужно: VPS (любой хостинг, от 1 ядра и 1 ГБ памяти, Ubuntu 24.04), домен и 30–60 минут.

1. **Домен.** В настройках DNS создайте запись `A`: `chat.example.com` → IP вашего VPS.
2. **Подключитесь по SSH** и подготовьте систему:
   ```
   sudo apt update && sudo apt install -y nginx certbot python3-certbot-nginx ufw
   curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
   sudo apt install -y nodejs
   sudo ufw allow OpenSSH && sudo ufw allow 'Nginx Full' && sudo ufw enable
   ```
3. **Загрузите сервер** (папку `server` целиком) в `/opt/messenger/server`, например так с вашего компьютера: `scp -r server user@IP:/tmp/` и затем `sudo mkdir -p /opt/messenger && sudo mv /tmp/server /opt/messenger/`.
4. **Пользователь и папка данных:**
   ```
   sudo useradd -r -s /usr/sbin/nologin messenger
   sudo mkdir -p /var/lib/messenger
   sudo chown -R messenger:messenger /var/lib/messenger /opt/messenger
   ```
5. **Служба** (автозапуск и перезапуск при сбоях). Создайте `/etc/systemd/system/messenger.service`:
   ```
   [Unit]
   Description=Messenger server
   After=network.target

   [Service]
   User=messenger
   WorkingDirectory=/opt/messenger/server
   Environment=PORT=3000
   Environment=DATA_DIR=/var/lib/messenger
   Environment=ALLOW_ORIGIN=https://chat.example.com,https://appassets.androidplatform.net
   ExecStart=/usr/bin/node server.js
   Restart=always
   RestartSec=3
   NoNewPrivileges=true

   [Install]
   WantedBy=multi-user.target
   ```
   Затем: `sudo systemctl daemon-reload && sudo systemctl enable --now messenger` и проверка `systemctl status messenger`. Адрес `https://appassets.androidplatform.net` нужен, чтобы разрешить запросы из приложения.
6. **Nginx** (принимает https и передаёт запросы серверу). Создайте `/etc/nginx/sites-available/messenger`:
   ```
   server {
       server_name chat.example.com;
       client_max_body_size 90m;
       location / {
           proxy_pass http://127.0.0.1:3000;
           proxy_http_version 1.1;
           proxy_set_header Host $host;
           proxy_set_header X-Forwarded-For $remote_addr;
           proxy_set_header X-Forwarded-Proto $scheme;
           proxy_set_header Connection '';
           proxy_buffering off;
           proxy_cache off;
           proxy_read_timeout 3600s;
       }
   }
   ```
   Включите: `sudo ln -s /etc/nginx/sites-available/messenger /etc/nginx/sites-enabled/ && sudo nginx -t && sudo systemctl reload nginx`.
   Важно: строки `proxy_buffering off` и `proxy_read_timeout` обязательны, иначе уведомления в реальном времени будут приходить с задержкой или рваться.
7. **Сертификат https:** `sudo certbot --nginx -d chat.example.com` (продлевается автоматически).
8. Откройте `https://chat.example.com`. Должна загрузиться страница входа.
9. **Приложение на этот сервер:** в `android/gradle.properties` поставьте `serverUrl=https://chat.example.com`, Sync, Run.
10. **Резервные копии.** Копируйте папку `/var/lib/messenger` (база и файлы) раз в сутки в другое место, например `rsync` на другой сервер или в облачное хранилище. Без этого поломка диска = потеря всех переписок.
11. **Обновление интерфейса или сервера:** заменить файлы в `/opt/messenger/server` (и `public/index.html`), затем `sudo systemctl restart messenger`. Данные сохраняются.

## Без компьютера (только телефон и браузер)

Android Studio не нужна: APK собирает GitHub Actions, а сервер настраивается по SSH с телефона (например, приложение Termius).

1. Зарегистрируйтесь на github.com и создайте пустой репозиторий. Загрузите в него всю папку MessengerKit (кнопка «Upload files» в браузере работает, но для архива лучше распаковать на компьютере; с телефона — через приложение GitHub).
2. Вкладка **Actions** → **Make signing key (one time)** → **Run workflow**. Перед этим в **Settings → Secrets and variables → Actions** добавьте секрет `KEYSTORE_PASSWORD` (придумайте пароль и запишите).
3. Скачайте артефакт `key-b64`, скопируйте содержимое в секрет `KEYSTORE_B64`. Добавьте секреты `KEY_ALIAS` (значение `messenger`) и `KEY_PASSWORD` (тот же пароль). Затем удалите workflow `make-keystore.yml` и артефакт: ключ больше не нужен в репозитории.
4. **Actions → Build APK → Run workflow**, в поле адреса вставьте `https://ваш-домен` (без / в конце). Готовый APK появится в артефакте `messenger-apk`. Номер запуска станет версией приложения.

## Обновление приложения прямо в приложении

Как это работает: открытое приложение раз в несколько секунд после запуска проверяет `/api/app-version`. Если на сервере версия выше установленной, сверху появляется полоска «Доступна версия…» с кнопкой «Обновить». Нажатие скачивает APK с вашего сервера и открывает системный установщик. Работает только в установленном APK, не в браузере.

Чтобы выпустить обновление:

1. Соберите новый APK в GitHub Actions (шаг 4 выше). Запомните номер запуска (например, 12) и скачайте файл.
2. Загрузите APK на сервер, например с телефона через SFTP-приложение в папку `/tmp`, либо через `scp` с компьютера.
3. На сервере выполните: `sudo bash /opt/messenger/deploy/publish-update.sh /tmp/app-release.apk 12 "1.12" "Кружки и ответы свайпом"`.

Скрипт проверяет, что код версии больше предыдущего, и публикует обновление без перезапуска сервера.

Важно: обновление ставится поверх старой версии только если APK подписан тем же ключом. Поэтому ключ из шага 3 нужно хранить в надёжном месте. Если потерять ключ, пользователям придётся удалить приложение и установить заново (переписка на сервере сохранится, но локальные данные в демо-режиме будут потеряны).

## Шаг 4. Push-уведомления (когда приложение закрыто)

Без этого шага сообщения приходят мгновенно, пока приложение открыто или недавно было открыто; когда система «усыпила» приложение, они придут только при следующем открытии. Это ограничение Android, решается через Firebase Cloud Messaging (FCM, бесплатно). Это единственный крупный кусок, которого в наборе нет; ниже описание, **код в этом шаге я не проверял**.

1. console.firebase.google.com → создайте проект → добавьте Android-приложение с вашим `applicationId` → скачайте `google-services.json` и положите в `android/app/`.
2. В `android/build.gradle.kts` добавьте в `plugins`: `id("com.google.gms.google-services") version "4.4.2" apply false`.
   В `android/app/build.gradle.kts` добавьте в `plugins`: `id("com.google.gms.google-services")`, а в `dependencies`:
   `implementation(platform("com.google.firebase:firebase-bom:33.7.0"))` и `implementation("com.google.firebase:firebase-messaging")`.
3. Передайте токен устройства странице. В `MainActivity.onPageFinished` добавьте:
   ```kotlin
   com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
       web.evaluateJavascript("window.__setPushToken && window.__setPushToken('$t')", null)
   }
   ```
   Страница сама отправит токен на сервер (`POST /api/push-token`, уже реализовано).
4. Создайте класс, который показывает уведомление по push, и зарегистрируйте его в манифесте:
   ```kotlin
   class MessagingService : com.google.firebase.messaging.FirebaseMessagingService() {
       override fun onMessageReceived(m: com.google.firebase.messaging.RemoteMessage) {
           val d = m.data   // title, body, chatId
           // постройте уведомление так же, как showNotification в MainActivity
       }
   }
   ```
   ```xml
   <service android:name=".MessagingService" android:exported="false">
       <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
   </service>
   ```
5. Сервер: в `server.js` в обработчике `POST /api/messages` есть комментарий «Место для push-уведомлений». Туда добавьте отправку через FCM HTTP v1: получите ключ сервисного аккаунта (Firebase → Настройки проекта → Сервисные аккаунты → «Создать закрытый ключ»), по нему получите токен доступа (OAuth2, JWT с подписью RS256, `https://oauth2.googleapis.com/token`) и отправьте
   `POST https://fcm.googleapis.com/v1/projects/ВАШ_PROJECT_ID/messages:send` с телом
   `{"message":{"token":"<токен из таблицы push>","data":{"title":"…","body":"…","chatId":"…"},"android":{"priority":"HIGH"}}}`.
   Токены устройств хранятся в таблице `push`. Проще всего подключить официальный пакет `firebase-admin` (`npm i firebase-admin`), он делает всё это за вас.
   Для приватности можно слать в push только «Новое сообщение», без текста.

## Шаг 5. Публикация

- **Раздать файл без магазина:** `Build → Build Bundle(s)/APK(s) → Build APK(s)`; файл `app-debug.apk` можно отправить напрямую (на телефоне разрешить установку из неизвестных источников). Для публичной раздачи используйте подписанную релизную сборку.
- **Google Play:** разовая плата разработчика (на момент написания 25 $), `Build → Generate Signed Bundle/APK → Android App Bundle`, создайте и **сохраните в безопасном месте файл ключа и пароли** (потеряете — не сможете обновлять приложение). Нужны политика конфиденциальности (ссылка на страницу), заполненная форма «Безопасность данных» и скриншоты. У новых личных аккаунтов есть требование закрытого тестирования перед публикацией; актуальные условия смотрите в Play Console.
- Увеличивайте `versionCode` в `app/build.gradle.kts` при каждом обновлении.

## Что сейчас не сделано (и стоит запланировать)

- **Восстановление пароля.** Вход только по логину и паролю: забытый пароль восстановить нельзя. Добавьте почту или телефон.
- **Шифрование.** Соединение защищено https, но сервер видит сообщения (сквозного шифрования нет).
- **Группы и каналы** работают только как демо-чаты на устройстве, в серверном режиме их нет.
- Редактирование и удаление сообщений, блокировка пользователей, список устройств, возможность выйти на всех устройствах.
- Если пользователи из России: персональные данные граждан РФ по закону хранятся на серверах в России (152-ФЗ), выбирайте хостинг с этим учётом. Для остальных стран требования свои.
- Масштаб: один сервер Node + SQLite спокойно держит тысячи пользователей. Дальше: PostgreSQL, Redis для рассылки между несколькими серверами, WebSocket вместо SSE при необходимости.

## Если что-то не работает

| Симптом | Что проверить |
|---|---|
| В приложении «Нет связи с сервером» | Верный ли `serverUrl` (без `/` на конце), открывается ли адрес в браузере телефона, для `http` — только отладочная сборка |
| В релизной сборке не соединяется по `http` | Это защита Android: нужен `https` (шаг 3) |
| Страница входа в приложении пустая | Убедитесь, что `app/src/main/assets/index.html` на месте; в Chrome откройте `chrome://inspect` и посмотрите консоль WebView (работает в отладочной сборке) |
| Уведомления приходят с задержкой | В Nginx нет `proxy_buffering off` |
| Ошибка про SQLite при запуске сервера | Версия Node старше 22.13: обновите |
| Микрофон не работает в приложении | Выдайте разрешение в Настройки → Приложения → Мессенджер → Разрешения |
| Сообщение с красным «!» | Сервер отклонил его (текст ошибки показывается внизу); нажмите на сообщение, чтобы отправить заново |
