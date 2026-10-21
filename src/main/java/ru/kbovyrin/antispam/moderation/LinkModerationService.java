package ru.kbovyrin.antispam.moderation;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import ru.kbovyrin.antispam.spam.LinkDetector;

/**
 * Правило «без ссылок и @упоминаний» для обычных пользователей.
 * Администраторы освобождены (список кешируется в AdminRegistry).
 * Роутер вызывает один и тот же метод и для сообщений, и для правок (edited_message).
 */
@Service
public class LinkModerationService {

    private static final Logger log = LoggerFactory.getLogger(LinkModerationService.class);

    private final AdminRegistry adminRegistry;
    private final LinkDetector detector;
    private final EnforcementService enforcement;

    public LinkModerationService(AdminRegistry adminRegistry, LinkDetector detector,
                                 EnforcementService enforcement) {
        this.adminRegistry = adminRegistry;
        this.detector = detector;
        this.enforcement = enforcement;
    }

    /**
     * @param content   текст сообщения или подпись к медиа (может быть null)
     * @param entities  сущности Telegram для этого контента
     * @param messageId ID сообщения, которое нужно удалить при нарушении
     * @return true, если пользователь забанен за это сообщение (капча ему больше не нужна)
     */
    public boolean handle(long chatId, long userId, String username, long messageId,
                          String content, List<MessageEntity> entities) {
        if (adminRegistry.isAdmin(chatId, userId)) {
            return false;
        }
        if (!detector.isViolation(content, entities)) {
            return false;
        }
        log.info("LINK_VIOLATION chat={} user={} username={} message={}", chatId, userId, username, messageId);
        // Возвращаем фактический результат: при сбое бана юзер НЕ забанен —
        // роутер обязан выдать ему капчу, а не гасить её
        return enforcement.ban(chatId, userId, username, "ссылка или @упоминание в сообщении",
                List.of(new EnforcementService.MessageRef(chatId, messageId)));
    }
}
