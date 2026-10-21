package ru.kbovyrin.antispam.telegram;

import java.util.List;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/**
 * Тонкая обёртка над Telegram Bot API для тестируемости сервисов.
 * Реализация (long polling) добавляется на шаге маршрутизации.
 */
public interface TelegramApi {

    /** Отправить сообщение с inline-клавиатурой. @return ID созданного сообщения. */
    long sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard);

    /** Отправить простое текстовое сообщение. @return ID созданного сообщения. */
    long sendMessage(long chatId, String text);

    /** Отредактировать текст сообщения. */
    void editMessageText(long chatId, long messageId, String text);

    /** Удалить сообщение. */
    void deleteMessage(long chatId, long messageId);

    /** Забанить пользователя в чате навсегда. */
    void banChatMember(long chatId, long userId);

    /**
     * Снять бан (убрать из чёрного списка чата). Вызывается с only_if_banned:
     * на состоящих в чате участников не влияет.
     */
    void unbanChatMember(long chatId, long userId);

    /**
     * Статус участника в чате (creator/administrator/member/restricted/left/kicked).
     * Нужен, чтобы не банить по таймауту капчи уже вышедших из группы.
     */
    String getChatMemberStatus(long chatId, long userId);

    /** Список ID администраторов чата. */
    List<Long> getChatAdminIds(long chatId);

    /** Ответить на callback query, чтобы убрать «часики» в клиенте. */
    void answerCallbackQuery(String callbackQueryId, String text);
}
