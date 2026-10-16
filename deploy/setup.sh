#!/usr/bin/env bash
# Одноразовая настройка сервера Ubuntu 24.04.
# Использование:  sudo bash setup.sh messenger.example.com you@mail.ru
# Перед запуском в папке /opt/messenger должен лежать проект (папка server/ с server.js).
set -euo pipefail
DOMAIN="${1:?укажите домен}"
EMAIL="${2:?укажите email для сертификата}"

# 1. Node.js 22 (нужен 22.13+; в стандартном Ubuntu 24.04 версия старее)
if ! node -v 2>/dev/null | grep -q '^v22'; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi

# 2. nginx и certbot
apt-get update
apt-get install -y nginx certbot python3-certbot-nginx

# 3. Пользователь и папки
id messenger >/dev/null 2>&1 || useradd --system --home /opt/messenger --shell /usr/sbin/nologin messenger
mkdir -p /opt/messenger/data
chown -R messenger:messenger /opt/messenger

# 4. Служба мессенджера
sed "s/messenger.example.com/${DOMAIN}/g" /opt/messenger/deploy/messenger.service > /etc/systemd/system/messenger.service
systemctl daemon-reload
systemctl enable --now messenger

# 5. nginx: сначала простой http-конфиг, чтобы certbot смог проверить домен и выпустить сертификат
rm -f /etc/nginx/sites-enabled/default
cat > /etc/nginx/sites-available/messenger <<NGINX
server {
    listen 80;
    server_name ${DOMAIN};
    location / { proxy_pass http://127.0.0.1:3000; proxy_set_header Host \$host; }
}
NGINX
ln -sf /etc/nginx/sites-available/messenger /etc/nginx/sites-enabled/messenger
nginx -t && systemctl reload nginx

# 6. https-сертификат (Let's Encrypt), затем полный конфиг с оптимизациями
certbot certonly --nginx -d "$DOMAIN" -m "$EMAIL" --agree-tos -n
sed "s/messenger.example.com/${DOMAIN}/g" /opt/messenger/deploy/nginx.conf > /etc/nginx/sites-available/messenger
nginx -t && systemctl reload nginx

# 7. Фаервол
ufw allow OpenSSH || true
ufw allow 'Nginx Full' || true
echo "Готово: https://${DOMAIN}"
