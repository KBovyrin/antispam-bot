package ru.kbovyrin.antispam.telegram;

import java.util.List;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;

import ru.kbovyrin.antispam.captcha.CaptchaService;
import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.moderation.LinkModerationService;

/**
 * Маршрутизация входящих обновлений Telegram к сервисам.
 * Группы наблюдения фильтруются, сообщения ботов — тоже; админов фильтруют сами сервисы.
 * Личка обрабатывается только для владельца (ADMIN_CHAT_ID): команды /banlist, /unban.
 */
@Component
public class UpdateRouter {

    private final AntispamProperties properties;
    private final CaptchaService captchaService;
    private final LinkModerationService linkModerationService;
    private final OwnerCommandsService ownerCommands;
    private final TelegramApi telegramApi;

    public UpdateRouter(AntispamProperties properties, CaptchaService captchaService,
                        LinkModerationService linkModerationService, OwnerCommandsService ownerCommands,
                        TelegramApi telegramApi) {
        this.properties = properties;
        this.captchaService = captchaService;
        this.linkModerationService = linkModerationService;
        this.ownerCommands = ownerCommands;
        this.telegramApi = telegramApi;
    }

    public void route(Update update) {
        if (update.hasMessage()) {
            handleMessage(update.getMessage(), true);
        } else if (update.hasEditedMessage()) {
            handleMessage(update.getEditedMessage(), false);
        } else if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
        }
    }

    private void handleMessage(Message message, boolean issueCaptcha) {
        if (message == null) {
            return;
        }
        if (isServiceMessage(message)) {
            return;
        }
        long chatId = message.getChat().getId();
        User from = message.getFrom();
        if (from == null || from.getIsBot()) {
            return;
        }
        // Личка: только команды владельца (id личного чата совпадает с id пользователя).
        // Только новые сообщения — правка команды не реисполняет её (N7')
        if (issueCaptcha && chatId == properties.adminChatId()) {
            ownerCommands.handlePrivateMessage(chatId, from.getId(), message.getText());
            return;
        }
        if (!isWatched(chatId)) {
            return;
        }
        // Автопересылка постов канала в связанную группу (sender_chat = канал)
        // и сообщения от имени чата (анонимные админы) не модерация обычных юзеров
        if (message.getSenderChat() != null) {
            return;
        }
        Content content = extractContent(message);

        // Сначала анти-ссылки: спамер банится и капчу уже не получает (W1 —
        // иначе капча выдаётся забаненному и таймаут даёт второй бан).
        boolean banned = linkModerationService.handle(chatId, from.getId(), from.getUserName(),
                message.getMessageId(), content.text(), content.entities());
        if (banned) {
            // Погасить активную капчу, если она была (правки тоже могут банить)
            captchaService.cancelSession(chatId, from.getId());
            return;
        }
        if (issueCaptcha) {
            captchaService.handleFirstMessage(chatId, from.getId(), from.getUserName(), message.getMessageId());
        }
    }

    /**
     * Служебные сообщения Telegram (вход/выход участников, смена названия/фото,
     * закрепления, видеочат и т.п.) — не контент пользователя: их нельзя считать
     * «первым сообщением» для капчи и модерировать. Иначе вышедший из группы
     * участник получит капчу в пустоту и по таймауту окажется забанен навсегда.
     */
    private static boolean isServiceMessage(Message message) {
        // getNewChatMembers() в библиотеке возвращает пустой список, а не null
        return !message.getNewChatMembers().isEmpty()
                || message.getLeftChatMember() != null
                || message.getNewChatTitle() != null
                || message.getNewChatPhoto() != null
                || Boolean.TRUE.equals(message.getDeleteChatPhoto())
                || message.getPinnedMessage() != null
                || Boolean.TRUE.equals(message.getGroupchatCreated())
                || Boolean.TRUE.equals(message.getChannelChatCreated())
                || message.getMigrateToChatId() != null
                || message.getMigrateFromChatId() != null
                || message.getVideoChatScheduled() != null
                || message.getVideoChatStarted() != null
                || message.getVideoChatEnded() != null
                || message.getVideoChatParticipantsInvited() != null
                || message.getForumTopicCreated() != null
                || message.getForumTopicEdited() != null
                || message.getForumTopicClosed() != null
                || message.getForumTopicReopened() != null;
    }

    private void handleCallback(CallbackQuery callback) {
        if (callback == null) {
            return;
        }
        if (!(callback.getMessage() instanceof Message message)) {
            // N4': сообщение-носитель удалено/неизвестно — отвечаем, иначе у юзера висят «часики»
            answerSafely(callback.getId(), "Кнопка больше не актуальна");
            return;
        }
        long chatId = message.getChat().getId();
        User from = callback.getFrom();
        if (from == null || from.getIsBot()) {
            answerSafely(callback.getId(), "Эта кнопка не для вас");
            return;
        }
        // Кнопки в личке владельца (уведомления о банах)
        if (chatId == properties.adminChatId()) {
            ownerCommands.handleUnbanCallback(chatId, from.getId(), message.getMessageId(),
                    callback.getData(), callback.getId());
            return;
        }
        if (!isWatched(chatId)) {
            return;
        }
        captchaService.handleCallback(chatId, from.getId(), callback.getData(), callback.getId());
    }

    private void answerSafely(String callbackQueryId, String text) {
        try {
            telegramApi.answerCallbackQuery(callbackQueryId, text);
        } catch (RuntimeException e) {
            // Ответ не критичен: колбэк мог устареть и для Telegram
        }
    }

    private boolean isWatched(long chatId) {
        return properties.groupIds().contains(chatId);
    }

    private static Content extractContent(Message message) {
        if (message.hasText()) {
            return new Content(message.getText(), safe(message.getEntities()));
        }
        if (message.getCaption() != null) {
            return new Content(message.getCaption(), safe(message.getCaptionEntities()));
        }
        return new Content(null, List.of());
    }

    private static List<MessageEntity> safe(List<MessageEntity> entities) {
        return entities == null ? List.of() : entities;
    }

    private record Content(String text, List<MessageEntity> entities) {
    }
}
