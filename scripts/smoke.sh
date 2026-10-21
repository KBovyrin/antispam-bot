#!/usr/bin/env bash
# Локальная smoke-проверка образа: сборка + запуск с выключенным ботом
# (ANTISPAM_ENABLED=false) + ожидание строки «Бот отключён» в логе.
# Проверяет: собираемость Dockerfile, старт Spring, инициализацию файловой
# SQLite в volume (schema.sql) и права пользователя bot.
# Запуск: scripts/smoke.sh  (использует podman, при его отсутствии docker)
set -euo pipefail
cd "$(dirname "$0")/.."

RUNTIME=podman
command -v "$RUNTIME" >/dev/null 2>&1 || RUNTIME=docker

IMAGE=antispam-bot:smoke
NAME=antispam-smoke
VOLUME=antispam-smoke-data

echo "==> Сборка образа ($RUNTIME)"
"$RUNTIME" build -t "$IMAGE" .

echo "==> Запуск с ANTISPAM_ENABLED=false"
"$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true
"$RUNTIME" volume rm "$VOLUME" >/dev/null 2>&1 || true
cleanup() {
  "$RUNTIME" rm -f "$NAME" >/dev/null 2>&1 || true
  "$RUNTIME" volume rm "$VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$RUNTIME" run -d --name "$NAME" \
  -e ANTISPAM_ENABLED=false \
  -e DB_URL=jdbc:sqlite:/data/antispam.db \
  -v "$VOLUME":/data:Z \
  "$IMAGE"

echo "==> Ожидание старта Spring (до 90 с)"
ok=""
for _ in $(seq 1 45); do
  if "$RUNTIME" logs "$NAME" 2>&1 | grep -q "Бот отключён"; then
    ok=1
    break
  fi
  # Контейнер умер — дальше ждать бессмысленно
  if [ "$("$RUNTIME" inspect -f '{{.State.Running}}' "$NAME")" != "true" ]; then
    break
  fi
  sleep 2
done

"$RUNTIME" logs "$NAME" 2>&1 | tail -5
if [ -z "$ok" ]; then
  echo "SMOKE FAILED: строка «Бот отключён» не появилась в логе"
  exit 1
fi

echo "==> Проверка схемы БД в volume (schema.sql применился)"
TABLES=$("$RUNTIME" run --rm -v "$VOLUME":/data:Z docker.io/library/alpine \
  sh -c 'apk add --no-cache sqlite >/dev/null 2>&1 && sqlite3 /data/antispam.db .tables')
echo "$TABLES"
echo "$TABLES" | grep -q participants \
  || { echo "SMOKE FAILED: таблица participants не создана"; exit 1; }

echo "SMOKE OK"
