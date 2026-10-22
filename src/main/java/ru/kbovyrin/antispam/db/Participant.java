package ru.kbovyrin.antispam.db;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Участник группы обсуждения и статус его проверки.
 * <p>
 * Схема таблицы описана в resources/schema.sql (уникальность пары chat_id+user_id —
 * уникальным индексом, т.к. Hibernate-диалект SQLite не создаёт констрейнты из @Table).
 * <p>
 * Осознанное решение — без @Version (N7): после атомарного {@code remove(key, session)}
 * в CaptchaService параллельные писатели бана (таймаут ↔ callback ↔ ссылка) схлопнулись
 * в один, а оба оставшихся пути пишут одно и то же значение BANNED — гонка теряет
 * практический смысл. Добавление @Version потребовало бы миграцию живой SQLite-базы
 * (schema.sql идемпотентен только через CREATE IF NOT EXISTS) — цена выше выгоды.
 */
@Entity
@Table(name = "participants")
public class Participant {

    public enum Status {
        /** Капча выдана, ждём ответа. */
        PENDING,
        /** Капча пройдена, больше не проверяем. */
        PASSED,
        /** Забанен навсегда. */
        BANNED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(length = 64)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Participant() {
    }

    private Participant(long chatId, long userId, String username, Status status, Instant now) {
        this.chatId = chatId;
        this.userId = userId;
        this.username = username;
        this.status = status;
        this.createdAt = now.truncatedTo(ChronoUnit.MILLIS);
        this.updatedAt = this.createdAt;
    }

    /** Капча выдана новичку (метка времени — от управляемых часов сервиса). */
    public static Participant newPending(long chatId, long userId, String username, Instant now) {
        return new Participant(chatId, userId, username, Status.PENDING, now);
    }

    /** Бан без капчи: например, ссылка в первом же сообщении (записи ещё не было). */
    public static Participant newBanned(long chatId, long userId, String username, Instant now) {
        return new Participant(chatId, userId, username, Status.BANNED, now);
    }

    /** Капча пройдена. */
    public void pass(Instant now) {
        transitionTo(Status.PASSED, now);
    }

    /** Username изменился с прошлой капчи — обновить для актуального /banlist. */
    public void updateUsername(String username, Instant now) {
        this.username = username;
        this.updatedAt = now.truncatedTo(ChronoUnit.MILLIS);
    }

    /** Нарушение: ссылка/упоминание или провал капчи. */
    public void ban(Instant now) {
        transitionTo(Status.BANNED, now);
    }

    private void transitionTo(Status newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = now.truncatedTo(ChronoUnit.MILLIS);
    }

    public Long getId() {
        return id;
    }

    public long getChatId() {
        return chatId;
    }

    public long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
