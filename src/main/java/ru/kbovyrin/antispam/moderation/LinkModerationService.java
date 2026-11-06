package ru.kbovyrin.antispam.moderation;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import ru.kbovyrin.antispam.spam.LinkDetector;
import ru.kbovyrin.antispam.telegram.TelegramApi;

/**
 * Правило «без ссылок и @упоминаний» для обычных пользователей.
 * Администраторы освобождены (список кешируется в AdminRegistry).
 * Роутер вызывает один и тот же метод и для сообщений, и для правок (edited_message).
 * <p>
 * P2 (2026-11): при недоступном списке админов (UNKNOWN) нарушение удаляется,
 * но автор не банится — сбой API больше не выключает модерацию целиком.
 */
@Service
public class LinkModerationService {

    private static final Logger log = LoggerFactory.getLogger(LinkModerationService.class);

    private final AdminRegistry adminRegistry;
    private final LinkDetector detector;
    private final EnforcementService enforcement;
    private final TelegramApi telegramApi;

    public LinkModerationService(AdminRegistry adminRegistry, LinkDetector detector,
                                  EnforcementService enforcement, TelegramApi telegramApi) {
        this.adminRegistry = adminRegistry;
        this.detector = detector;
        this.enforcement = enforcement;
        this.telegramApi = telegramApi;
    }

    /**
     * @param content   текст сообщения или подпись к медиа (может быть null)
     * @param entities  сущности Telegram для этого контента
     * @param messageId ID сообщения, которое нужно удалить при нарушении
     * @return true, если пользователь забанен за это сообщение (капча ему больше не нужна)
     */
    public boolean handle(long chatId, long userId, String username, long messageId,
                          String content, List<MessageEntity> entities) {
        AdminRegistry.AdminState state = adminRegistry.adminState(chatId, userId);
        if (state == AdminRegistry.AdminState.ADMIN) {
            return false;
        }
        if (!detector.isViolation(content, entities)) {
            return false;
        }
        if (state == AdminRegistry.AdminState.UNKNOWN) {
            // P2: наказывать нельзя (виновен может быть админ с недоступным списком),
            // но и пропускать нарушение не обязаны — удаляем без шума. return false:
            // капча живёт по общим правилам (в UNKNOWN-окне этой группы сама капча
            // не выдаётся — fail-open там же, см. CaptchaService.isAdmin)
            log.warn("LINK_DELETED_ADMINS_UNKNOWN chat={} user={} username={} message={}",
                    chatId, userId, username, messageId);
            try {
                telegramApi.deleteMessage(chatId, messageId);
            } catch (RuntimeException e) {
                log.error("DELETE_FAILED chat={} message={}: {}", chatId, messageId, e.getMessage());
            }
            return false;
        }
        log.info("LINK_VIOLATION chat={} user={} username={} message={}", chatId, userId, username, messageId);
        // Возвращаем фактический результат: при сбое бана юзер НЕ забанен —
        // роутер обязан выдать ему капчу, а не гасить её
        return enforcement.ban(chatId, userId, username, "ссылка или @упоминание в сообщении",
                List.of(new EnforcementService.MessageRef(chatId, messageId)));
    }
}
