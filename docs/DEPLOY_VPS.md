# Развёртывание сервера на VPS (по шагам)

Приложению **обязательно нужен запущенный сервер** в интернете — без него телефоны не свяжутся, и регистрация падает с ошибкой «failed to connect». Адрес `10.0.2.2` работает только в эмуляторе Android, на реальном телефоне его указывать нельзя.

Ниже — как поднять сервер на обычном Linux-VPS. Подойдёт любой недорогой VPS с Ubuntu 22.04/24.04 (например, российские Timeweb, Reg.ru, Beget, Selectel — оплата российской картой). Минимальной конфигурации (1 ядро, 1 ГБ RAM) достаточно. Платные услуги оплачиваете вы сами; я ничего не подключаю.

---

## Часть 1. Быстрый старт — HTTP (работает сразу)

Для прототипа приложение разрешает обычный `http://`, поэтому можно начать без домена и сертификата.

### 1. Создайте VPS
Закажите VPS с **Ubuntu 24.04**. Запишите его **публичный IP** (например, `203.0.113.10`) и пароль root (или ssh-ключ).

### 2. Подключитесь по SSH
С компьютера:
```bash
ssh root@203.0.113.10
```

### 3. Установите Node.js и инструменты
```bash
apt update && apt install -y curl git
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt install -y nodejs
node -v   # должно показать v20.x
```

### 4. Скопируйте сервер на VPS
Вариант А — склонировать репозиторий:
```bash
git clone https://github.com/ponartemon034-lab/shlagbaum.git /opt/shlagbaum
cd /opt/shlagbaum/server
```
Вариант Б — если репозиторий приватный, скопируйте папку `server/` через `scp` с компьютера:
```bash
scp -r server root@203.0.113.10:/opt/shlagbaum/server
```

### 5. Установите зависимости
```bash
cd /opt/shlagbaum/server
npm install --omit=optional
node src/selftest.js   # необязательно: должно быть «20 пройдено, 0 провалено»
```

### 6. Запустите как службу (systemd) — чтобы работал постоянно
Создайте файл службы:
```bash
cat > /etc/systemd/system/shlagbaum.service <<'EOF'
[Unit]
Description=Shlagbaum relay server
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/shlagbaum/server
Environment=PORT=8080
Environment=DB_PATH=/opt/shlagbaum/server/data.sqlite
ExecStart=/usr/bin/node src/index.js
Restart=always
RestartSec=3
User=root

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now shlagbaum
systemctl status shlagbaum --no-pager   # должно быть active (running)
```

### 7. Откройте порт 8080 в брандмауэре
```bash
# если включён ufw:
ufw allow 8080/tcp
```
Также откройте порт 8080 в панели управления VPS (Security Group / Firewall), если провайдер фильтрует входящие.

### 8. Проверьте
С компьютера или телефона откройте в браузере:
```
http://203.0.113.10:8080/v1/health
```
Должно вернуться что-то вроде `{"ok":true,"fcm":"pull-only",...}`.

### 9. Укажите адрес в приложении
На обоих телефонах в поле «Адрес вашего сервера» впишите:
```
http://203.0.113.10:8080
```
(ваш реальный IP). Теперь регистрация и всё остальное заработают.

---

## Часть 2. Рекомендуется: HTTPS через домен и Caddy

HTTP достаточно для теста, но для постоянной работы лучше HTTPS. Нужен **домен**, направленный на IP вашего VPS (A-запись). Caddy сам получит бесплатный сертификат Let's Encrypt.

### 1. Направьте домен на VPS
В настройках домена создайте A-запись: `shlagbaum.ваш-домен.ru → 203.0.113.10`.

### 2. Установите Caddy
```bash
apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
apt update && apt install -y caddy
```

### 3. Настройте обратный прокси на сервер
```bash
cat > /etc/caddy/Caddyfile <<'EOF'
shlagbaum.ваш-домен.ru {
    reverse_proxy 127.0.0.1:8080
}
EOF

systemctl restart caddy
```
Caddy автоматически получит сертификат. Через минуту проверьте:
```
https://shlagbaum.ваш-домен.ru/v1/health
```

### 4. Закройте прямой доступ к 8080 (необязательно)
Теперь можно оставить 8080 только для localhost:
```bash
ufw delete allow 8080/tcp
```
(сервер слушает 8080, но наружу идёт только через Caddy на 443).

### 5. В приложении укажите
```
https://shlagbaum.ваш-домен.ru
```

---

## Часть 3. Push-уведомления (FCM) — для надёжного звонка при заблокированном экране

Опционально. Нужно, чтобы телефон B будился мгновенно даже из глубокого сна. Требует проекта Firebase и пересборки приложения. Шаги — в [INSTALL.md](INSTALL.md), раздел «Включение FCM». Серверный ключ Firebase задаётся переменной окружения `GOOGLE_APPLICATION_CREDENTIALS` в службе systemd (в APK он не кладётся).

---

## Полезные команды

```bash
systemctl status shlagbaum      # статус сервера
journalctl -u shlagbaum -f      # логи сервера в реальном времени
systemctl restart shlagbaum     # перезапуск после обновления кода
```

## Резервное поведение при сбое
- Если сервер перезагрузится — systemd поднимет его автоматически (`Restart=always`), очередь команд сохраняется в `data.sqlite`.
- Если телефон B был офлайн — команда дождётся его в очереди (в пределах срока действия) и будет забрана при возврате сети.
