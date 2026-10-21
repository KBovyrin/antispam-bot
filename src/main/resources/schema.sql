-- Схема БД. SQLite поддерживает IF NOT EXISTS — скрипт идемпотентен.
-- Hibernate-диалект SQLite не создаёт unique-констрейнты из @Table,
-- поэтому схема описана явно здесь (ddl-auto=none).
CREATE TABLE IF NOT EXISTS participants (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    username   VARCHAR(64),
    status     VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'PASSED', 'BANNED')),
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_participants_chat_user ON participants (chat_id, user_id);
