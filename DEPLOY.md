# Деплой antispam-bot на VPS

Целевая схема: VPS с Ubuntu, Docker, бот работает через long polling
(домен, TLS и открытые порты для Telegram не нужны — соединения только исходящие).

## 1. Что понадобится

- Актуальный VPS: **2 vCPU, 4 ГБ RAM, 40 ГБ NVMe** — этого достаточно для сборки
  на месте: Maven-сборка в контейнере пикует ~1,5–2 ГБ RAM, бот в рантайме
  потребляет ~300–500 МБ. Вариант «образ собрать локально» (раздел 8) не нужен.
- Ubuntu 22.04/24.04, sudo-доступ.
- Токен бота и ID чатов — см. раздел 2.

## 2. Подготовка в Telegram

1. **Токен:** у [@BotFather](https://t.me/BotFather) → `/newbot` → сохранить
   `BOT_TOKEN` (вида `123456789:AA...`).
2. **Группа обсуждения:** канал → «Управление» → «Обсуждение группы» — должна быть
   привязана группа комментариев (если ещё нет — создать и привязать).
3. **Права бота:** добавить бота в эту группу и сделать администратором, выдав:
   - «Удаление сообщений»;
   - «Блокировка участников».
4. **ID группы:** напишите любое сообщение в группе обсуждения, затем откройте в
   браузере `https://api.telegram.org/bot<ТОКЕН>/getUpdates` и найдите `chat.id` —
   отрицательное число вида `-1001234567890`. Это `GROUP_IDS`.
   Ссылка раскрывает токен — не публикуйте её, при утечке отзовите токен в BotFather.
5. **Свой ID для уведомлений:** напишите [@userinfobot](https://t.me/userinfobot) —
   он покажет ваш `ADMIN_CHAT_ID`.

## 3. Установка Docker на VPS

```bash
ssh user@VPS_IP
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
exit
# перезайти по SSH, чтобы применилась группа docker
docker --version && docker compose version
```

Для 4 ГБ RAM swap не обязателен, но 1–2 ГБ подстрахуют сборку на пиках:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Firewall: боту нужны только исходящие соединения (long polling), наружу
достаточно открыть один SSH:

```bash
sudo ufw allow OpenSSH && sudo ufw --force enable
```

## 4. Клонирование проекта

Проект живёт на GitHub, на VPS клонируется в `/opt`:

```bash
ssh user@VPS_IP

# /opt принадлежит root — создаём папку и отдаём её своему пользователю
sudo mkdir -p /opt/antispam-bot
sudo chown $USER:$USER /opt/antispam-bot

git clone https://github.com/KBovyrin/antispam-bot.git /opt/antispam-bot
```

Если репозиторий приватный, git спросит логин/пароль — вместо пароля нужен
Personal Access Token из GitHub (Settings → Developer settings → Tokens).

`.env` создаётся на VPS отдельно (раздел 5) и в git не попадает.

## 5. Конфигурация (.env на VPS)

```bash
cd /opt/antispam-bot
cp .env.example .env
nano .env
chmod 600 .env      # токен не должен быть читаем другими пользователями VPS
```

Обязательные переменные:

| Переменная      | Пример значения        |
|-----------------|------------------------|
| `BOT_TOKEN`     | `123456789:AA...`      |
| `BOT_USERNAME`  | `my_channel_bot`       |
| `ADMIN_CHAT_ID` | `123456789`            |
| `GROUP_IDS`     | `-1001234567890`       |

Необязательные: `CAPTCHA_TIMEOUT` (по умолчанию `5m`), `ANTISPAM_ENABLED`
(`true`), `DB_URL` (в контейнере задаётся compose: `jdbc:sqlite:/data/antispam.db`).

⚠️ `.env` содержит токен: не коммитить, не выкладывать. Он и так исключён из
`.gitignore` и `.dockerignore`, а права 600 закрывают его от других пользователей машины.

⚠️ Проверьте, что файл `.mvn/jvm.config` (настройки таймаутов для скачивания
зависимостей) **не содержит строк с `#`** — комментарии в нём не поддерживаются,
Maven не запустится. Если при сборке видите `Could not find or load main class #`:

```bash
sed -i '/#/d' .mvn/jvm.config
```

## 6. Запуск

```bash
cd /opt/antispam-bot
docker compose up -d --build
```

Первая сборка скачивает базовые образы и зависимости Maven (несколько минут).
При сетевом сбое вида `Read timed out` просто повторите команду — Maven докачает
с кеша, теперь уже с увеличенными таймаутами (см. `.mvn/jvm.config`).

## 7. Проверка

```bash
docker compose ps                          # статус: Up
docker compose logs -f                     # ждём строку «Long polling запущен для бота @...»
```

Дальше — ручная приёмка в тестовой группе по чек-листу из `README.md`
(капча пройдена/провалена/таймаут, ссылка → бан, правка сообщения → бан,
админ не трогается).

Уведомления о банах приходят в личку `ADMIN_CHAT_ID`. Для их получения напишите
боту что-нибудь в личку один раз (Telegram не даёт ботам писать первыми).
Владельцу в личке также доступны команды `/banlist` и `/unban <user_id>`
(подробнее — в README.md), кнопка «Разбанить» — прямо в уведомлении о бане.

⚠️ Бот видит сообщения группы только потому, что он в админах группы. Если снять
с него админку (или убрать из группы) — модерация молча перестанет работать.

## 8. Обновление бота

```bash
cd /opt/antispam-bot
git pull
docker compose up -d --build      # пересоберёт и подменит контейнер
docker compose logs -f            # ждём «Long polling запущен для бота @...»
```

`.env` и база данных (named volume `antispam-data`) обновления не затрагивают.

Откат на прошлую версию, если что-то пошло не так:

```bash
git log --oneline -5              # найти предыдущий рабочий коммит
git checkout <hash>
docker compose up -d --build
# после фикса вернуться: git checkout main
```

Старые образы после нескольких обновлений копятся — периодически
`docker image prune -f`.

### Вариант для слабого VPS (1–2 ГБ): собрать образ локально

Актуальному VPS (4 ГБ) не нужен — сборка на месте проходит без проблем.
Оставлен на случай отката на дешёвую машину. Сборка Maven в контейнере
может не влезть в 1 ГБ RAM. Тогда:

```bash
# локально (Bazzite):
podman build -t antispam-bot:latest .
podman save --format docker-archive -o antispam-bot.tar.gz antispam-bot:latest
scp antispam-bot.tar.gz user@VPS_IP:~

# VPS:
docker load -i antispam-bot.tar.gz
```

и в `docker-compose.yml` заменить `build: .` на `image: antispam-bot:latest`.
Настройки (`.env`) продолжат применяться через `env_file`.

## 9. Бэкап и восстановление БД

Бэкап делается при остановленном боте — tar живого SQLite-файла может дать
несогласованную копию (restore и так корректно делает `stop`):

```bash
# бэкап (выполняется на VPS, файл появится в текущей директории)
cd /opt/antispam-bot
docker compose stop
docker run --rm -v antispam-data:/data -v "$PWD":/backup alpine \
  tar czf "/backup/antispam-db-$(date +%F).tar.gz" -C /data antispam.db
docker compose start

# восстановление
cd /opt/antispam-bot
docker compose stop
docker run --rm -v antispam-data:/data -v "$PWD":/backup alpine \
  sh -c 'rm -f /data/antispam.db && tar xzf /backup/antispam-db-2026-10-20.tar.gz -C /data'
docker compose start
```

## 10. Диагностика

| Симптом | Причина / действие |
|---|---|
| `docker compose logs`: `401 Unauthorized` от Telegram | Неверный `BOT_TOKEN` — проверить `.env`, перевыпустить токен у BotFather |
| В логах «GROUP_IDS пуст» | `.env` не подхватился: он должен лежать рядом с `docker-compose.yml` |
| Бот молчит в группе | Бот не админ группы; `GROUP_IDS` не совпадает с реальным `chat.id` |
| «Read timed out» при сборке | Повторить `docker compose up -d --build`; проверить `.mvn/jvm.config` (раздел 5) |
| Сборка падает по памяти (`Killed`) | Раздел 8 — собрать образ локально |
| Нужно сменить токен/группу | Поправить `.env` → `docker compose up -d` (пересборка не нужна) |
| Проверить состояние БД | `docker run --rm -v antispam-data:/data alpine ls -la /data` |
