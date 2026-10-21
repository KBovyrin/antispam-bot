package ru.kbovyrin.antispam.notify;

/**
 * Уведомления администратору о действиях бота.
 */
public interface AdminNotifier {

    /** Сообщить админу о нарушителе (бан выполнен). */
    void violation(long chatId, long userId, String username, String reason);

    /** Сообщить админу, что забанить не удалось (нужна ручная проверка). */
    void banFailed(long chatId, long userId, String username, String reason);
}
