package ru.kbovyrin.antispam.telegram;

import java.util.List;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

import ru.kbovyrin.antispam.config.AntispamProperties;

/**
 * Реализация TelegramApi поверх telegrambots-client 10.x (P4 миграция 2026-11):
 * исходящие вызовы идут через {@link OkHttpTelegramClient}, долг бота библиотека
 * больше не требует. В polling-цикле не участвует (регистрируется только
 * BotUpdateReceiver).
 * <p>
 * Бин объявлен ТОЛЬКО в AppConfig (шов-конструктор для тестов), @Service
 * не ставится — двойная регистрация (warn ревью P4).
 */
public class TelegramBotAdapter implements TelegramApi {

    private final AntispamProperties properties;
    private final TelegramClient client;

    public TelegramBotAdapter(AntispamProperties properties) {
        this(properties, new OkHttpTelegramClient(properties.botToken()));
    }

    /** Шов конструктора: тесты подставляют мок TelegramClient вместо сети. */
    TelegramBotAdapter(AntispamProperties properties, TelegramClient client) {
        this.properties = properties;
        this.client = client;
    }

    /** Имя бота для диагностики. */
    public String getBotUsername() {
        return properties.botUsername();
    }

    @Override
    public long sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            Message sent = client.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .build());
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            throw new TelegramRequestException("sendMessage chat=" + chatId, e);
        }
    }

    @Override
    public long sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    @Override
    public void editMessageText(long chatId, long messageId, String text) {
        try {
            client.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId((int) messageId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            throw new TelegramRequestException("editMessageText chat=" + chatId + " message=" + messageId, e);
        }
    }

    @Override
    public void deleteMessage(long chatId, long messageId) {
        try {
            client.execute(DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId((int) messageId)
                    .build());
        } catch (TelegramApiException e) {
            throw new TelegramRequestException("deleteMessage chat=" + chatId + " message=" + messageId, e);
        }
    }

    @Override
    public void banChatMember(long chatId, long userId) {
        try {
            client.execute(BanChatMember.builder()
                    .chatId(chatId)
                    .userId(userId)
                    .build());
        } catch (TelegramApiException e) {
            throw new TelegramRequestException("banChatMember chat=" + chatId + " user=" + userId, e);
        }
    }

    @Override
    public void unbanChatMember(long chatId, long userId) {
        try {
            client.execute(UnbanChatMember.builder()
                    .chatId(chatId)
                    .userId(userId)
                    .onlyIfBanned(true)
                    .build());
        } catch (TelegramApiException e) {
            throw new TelegramRequestException("unbanChatMember chat=" + chatId + " user=" + userId, e);
        }
    }

    @Override
    public String getChatMemberStatus(long chatId, long userId) {
        try {
            ChatMember member = client.execute(GetChatMember.builder()
                    .chatId(chatId)
                    .userId(userId)
                    .build());
            return member.getStatus();
        } catch (TelegramApiException e) {
            throw new TelegramRequestException("getChatMember chat=" + chatId + " user=" + userId, e);
        }
    }

    @Override
    public List<Long> getChatAdminIds(long chatId) {
        try {
            List<ChatMember> administrators = client.execute(GetChatAdministrators.builder()
                    .chatId(chatId)
                    .build());
            return administrators.stream()
                    .map(member -> member.getUser().getId())
                    .toList();
        } catch (TelegramApiException e) {
            throw new TelegramRequestException("getChatAdministrators chat=" + chatId, e);
        }
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            throw new TelegramRequestException("answerCallbackQuery id=" + callbackQueryId, e);
        }
    }

    /** Ошибка вызова Telegram API внутри нашей обёртки. */
    public static class TelegramRequestException extends RuntimeException {
        public TelegramRequestException(String action, Throwable cause) {
            super("Не удалось выполнить " + action + ": " + cause.getMessage(), cause);
        }
    }
}
