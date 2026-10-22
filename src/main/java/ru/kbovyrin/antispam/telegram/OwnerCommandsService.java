package ru.kbovyrin.antispam.telegram;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.db.Participant;
import ru.kbovyrin.antispam.moderation.UnbanService;

/**
 * Команды владельца в личке бота (отправитель обязан совпадать с ADMIN_CHAT_ID):
 * /start и /help — справка, /banlist — забаненные наблюдаемых групп,
 * /unban &lt;user_id&gt; — разбан во всех наблюдаемых группах. Здесь же обрабатывается
 * кнопка «Разбанить» из уведомления о бане (callback_data = "unban:&lt;userId&gt;").
 */
@Component
public class OwnerCommandsService {

    public static final String UNBAN_CALLBACK_PREFIX = "unban:";
    private static final int BANLIST_LIMIT = 50;
    /** Лимит символов на одно сообщение /banlist (Telegram: 4096, берём с запасом). */
    private static final int BANLIST_CHAR_LIMIT = 3500;
    /** Дата бана в /banlist — читаемая человеком, UTC (N3). */
    private static final DateTimeFormatter BAN_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

    private static final Logger log = LoggerFactory.getLogger(OwnerCommandsService.class);

    private final AntispamProperties properties;
    private final UnbanService unbanService;
    private final TelegramApi telegramApi;

    public OwnerCommandsService(AntispamProperties properties, UnbanService unbanService, TelegramApi telegramApi) {
        this.properties = properties;
        this.unbanService = unbanService;
        this.telegramApi = telegramApi;
    }

    /** Клавиатура с кнопкой разбана для уведомления о бане. */
    public static InlineKeyboardMarkup unbanKeyboard(long userId) {
        InlineKeyboardButton button = new InlineKeyboardButton("Разбанить");
        button.setCallbackData(UNBAN_CALLBACK_PREFIX + userId);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(button)));
        return markup;
    }

    /** Приватное сообщение владельца. Не-команды и чужие отправители игнорируются. */
    public void handlePrivateMessage(long chatId, long fromId, String text) {
        if (!isOwner(chatId, fromId) || text == null || !text.startsWith("/")) {
            return;
        }
        String[] parts = text.trim().split("\\s+");
        String command = parts[0].toLowerCase();
        int suffix = command.indexOf('@');
        if (suffix > 0) {
            command = command.substring(0, suffix);
        }
        switch (command) {
            case "/start", "/help" -> sendHelp(chatId);
            case "/banlist" -> sendBanlist(chatId);
            case "/unban" -> unbanByCommand(chatId, parts);
            default -> telegramApi.sendMessage(chatId,
                    "Неизвестная команда. Доступны: /banlist, /unban <user_id>");
        }
    }

    /** Нажатие кнопки в личке владельца (уведомление о бане и пр.). */
    public void handleUnbanCallback(long chatId, long fromId, long messageId, String data, String callbackQueryId) {
        if (!isOwner(chatId, fromId)) {
            telegramApi.answerCallbackQuery(callbackQueryId, "Эта кнопка не для вас");
            return;
        }
        if (data == null || !data.startsWith(UNBAN_CALLBACK_PREFIX)) {
            telegramApi.answerCallbackQuery(callbackQueryId, "Неизвестная команда");
            return;
        }
        Long userId = parseUserId(data.substring(UNBAN_CALLBACK_PREFIX.length()));
        if (userId == null) {
            editOrSend(chatId, messageId, "Не удалось разобрать id пользователя");
            telegramApi.answerCallbackQuery(callbackQueryId, "Ошибка");
            return;
        }
        int groups = unbanService.unbanEverywhere(userId);
        String text = groups == 0
                ? "Пользователь id=" + userId + " не найден в бан-листе"
                : "✅ Разбанен в " + groups + " " + pluralGroups(groups);
        editOrSend(chatId, messageId, text);
        telegramApi.answerCallbackQuery(callbackQueryId, groups == 0 ? "Не найден" : "Разбанен");
    }

    /**
     * Правка текста сообщения-носителя кнопки. Уведомление старше 48 часов
     * Telegram редактировать не даёт (W4): тогда результат уходит новым
     * сообщением, а колбэк всё равно получает ответ — без «часиков» у владельца.
     */
    private void editOrSend(long chatId, long messageId, String text) {
        try {
            telegramApi.editMessageText(chatId, messageId, text);
        } catch (RuntimeException e) {
            log.warn("EDIT_FAILED chat={} message={}: {}", chatId, messageId, e.getMessage());
            telegramApi.sendMessage(chatId, text);
        }
    }

    private boolean isOwner(long chatId, long fromId) {
        return chatId == properties.adminChatId() && fromId == properties.adminChatId();
    }

    private void sendHelp(long chatId) {
        telegramApi.sendMessage(chatId, """
                Команды владельца:
                /banlist — забаненные в наблюдаемых группах
                /unban <user_id> — разбанить во всех группах (id есть в /banlist и в уведомлениях о банах)
                Кнопка «Разбанить» работает прямо в уведомлении о бане.""");
    }

    private void sendBanlist(long chatId) {
        List<Participant> banned = unbanService.bannedParticipants();
        if (banned.isEmpty()) {
            telegramApi.sendMessage(chatId, "Бан-лист пуст ✅");
            return;
        }
        List<Participant> shown = banned.stream().limit(BANLIST_LIMIT).toList();
        List<String> lines = new ArrayList<>();
        for (Participant p : shown) {
            String name = p.getUsername() == null || p.getUsername().isBlank()
                    ? "id=" + p.getUserId()
                    : "@" + p.getUsername() + " (id=" + p.getUserId() + ")";
            lines.add("• %s — чат %d — с %s UTC".formatted(name, p.getChatId(), BAN_DATE.format(p.getUpdatedAt())));
        }
        int rest = banned.size() - shown.size();
        if (rest > 0) {
            lines.add("…и ещё %d".formatted(rest));
        }
        // W1': Telegram не принимает сообщения длиннее 4096 символов — режем
        // на чанки с запасом, иначе админ не получил бы список вовсе
        String header = "🚫 Бан-лист (%d):%n".formatted(banned.size());
        StringBuilder chunk = new StringBuilder(header);
        for (String line : lines) {
            if (chunk.length() + line.length() + 1 > BANLIST_CHAR_LIMIT && chunk.length() > header.length()) {
                telegramApi.sendMessage(chatId, chunk.toString());
                chunk.setLength(0);
            }
            if (chunk.length() > 0) {
                chunk.append('\n');
            }
            chunk.append(line);
        }
        telegramApi.sendMessage(chatId, chunk.toString());
    }

    private void unbanByCommand(long chatId, String[] parts) {
        Long userId = parts.length > 1 ? parseUserId(parts[1]) : null;
        if (userId == null) {
            telegramApi.sendMessage(chatId, "Использование: /unban <user_id>");
            return;
        }
        int groups = unbanService.unbanEverywhere(userId);
        String text = groups == 0
                ? "Пользователь id=" + userId + " не найден в бан-листе"
                : "✅ Разбанен в " + groups + " " + pluralGroups(groups);
        telegramApi.sendMessage(chatId, text);
    }

    private static Long parseUserId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String pluralGroups(int n) {
        return n % 10 == 1 && n % 100 != 11 ? "группе" : "группах";
    }
}
