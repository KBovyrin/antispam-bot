package ru.kbovyrin.antispam.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.telegram.OwnerCommandsService;
import ru.kbovyrin.antispam.telegram.TelegramApi;

/**
 * Уведомления администратору в личку. Отправка best-effort:
 * сбой уведомления не должен ломать применённое наказание.
 */
@Component
public class AdminNotifierImpl implements AdminNotifier {

    private static final Logger log = LoggerFactory.getLogger(AdminNotifierImpl.class);

    private final AntispamProperties properties;
    private final TelegramApi telegramApi;

    public AdminNotifierImpl(AntispamProperties properties, TelegramApi telegramApi) {
        this.properties = properties;
        this.telegramApi = telegramApi;
    }

    @jakarta.annotation.PostConstruct
    void warnIfAdminUnset() {
        if (properties.adminChatId() <= 0) {
            log.warn("ADMIN_CHAT_ID не задан: уведомления о банах и команды /banlist, /unban работать не будут");
        }
    }

    @Override
    public void banFailed(long chatId, long userId, String username, String reason) {
        // N9': аудит-трейл пишем всегда — до проверки настроек уведомлений
        log.error("BAN_FAILED chat={} user={} username={} reason={}", chatId, userId, username, reason);
        if (properties.adminChatId() <= 0) {
            return;
        }
        String name = username == null || username.isBlank() ? "без username" : "@" + username;
        String text = "⚠️ НЕ удалось забанить: %s (id=%d) в чате %d.\nПричина нарушения: %s.\nПроверьте вручную!"
                .formatted(name, userId, chatId, reason);
        try {
            telegramApi.sendMessage(properties.adminChatId(), text);
        } catch (RuntimeException e) {
            log.error("Не удалось отправить уведомление о сбое бана админу {}: {}",
                    properties.adminChatId(), e.getMessage());
        }
    }

    @Override
    public void violation(long chatId, long userId, String username, String reason) {
        // N9': аудит-трейл пишем всегда — до проверки настроек уведомлений
        log.warn("VIOLATION chat={} user={} username={} reason={}", chatId, userId, username, reason);
        if (properties.adminChatId() <= 0) {
            return;
        }
        String name = username == null || username.isBlank() ? "без username" : "@" + username;
        String text = "🚫 Забанен навсегда: %s (id=%d) в чате %d.\nПричина: %s".formatted(name, userId, chatId, reason);
        try {
            telegramApi.sendMessage(properties.adminChatId(), text, unbanKeyboard(userId));
        } catch (RuntimeException e) {
            log.error("Не удалось отправить уведомление админу {}: {}", properties.adminChatId(), e.getMessage());
        }
    }

    /** Клавиатура с кнопкой разбана для уведомления. */
    private static InlineKeyboardMarkup unbanKeyboard(long userId) {
        return OwnerCommandsService.unbanKeyboard(userId);
    }
}
