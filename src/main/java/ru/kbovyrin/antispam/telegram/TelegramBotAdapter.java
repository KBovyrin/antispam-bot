package ru.kbovyrin.antispam.telegram;

import java.util.List;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import ru.kbovyrin.antispam.config.AntispamProperties;

/**
 * Реализация TelegramApi поверх библиотеки telegrambots.
 * В polling-цикле не участвует (регистрируется только BotUpdateReceiver):
 * класс-носитель execute() из TelegramLongPollingBot, но только исходящие вызовы.
 */
@Component
public class TelegramBotAdapter extends org.telegram.telegrambots.bots.TelegramLongPollingBot implements TelegramApi {

    private final AntispamProperties properties;

    public TelegramBotAdapter(AntispamProperties properties) {
        super();
        this.properties = properties;
    }

    @Override
    public String getBotUsername() {
        return properties.botUsername();
    }

    @Override
    public String getBotToken() {
        return properties.botToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        // Адаптер не зарегистрирован в polling — обновления сюда не приходят.
    }

    @Override
    public long sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            Message sent = execute(SendMessage.builder()
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
            execute(EditMessageText.builder()
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
            execute(DeleteMessage.builder()
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
            execute(BanChatMember.builder()
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
            execute(org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember.builder()
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
            ChatMember member = execute(org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember.builder()
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
            List<ChatMember> administrators = execute(GetChatAdministrators.builder()
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
            execute(AnswerCallbackQuery.builder()
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
