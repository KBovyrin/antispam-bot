package ru.kbovyrin.antispam.moderation;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.kbovyrin.antispam.db.Participant;
import ru.kbovyrin.antispam.db.ParticipantRepository;
import ru.kbovyrin.antispam.notify.AdminNotifier;
import ru.kbovyrin.antispam.telegram.TelegramApi;

/**
 * Общее «правоохранительное» действие: удалить сообщения нарушителя, забанить,
 * обновить статус в БД и уведомить админа. Используется и капчей, и анти-ссылками.
 */
@Service
public class EnforcementService {

    private static final Logger log = LoggerFactory.getLogger(EnforcementService.class);

    private final ParticipantRepository repository;
    private final TelegramApi telegramApi;
    private final AdminNotifier adminNotifier;
    private final Clock clock;

    public EnforcementService(ParticipantRepository repository, TelegramApi telegramApi,
                              AdminNotifier adminNotifier, Clock clock) {
        this.repository = repository;
        this.telegramApi = telegramApi;
        this.adminNotifier = adminNotifier;
        this.clock = clock;
    }

    /**
     * Ссылка на сообщение, подлежащее удалению.
     */
    public record MessageRef(long chatId, long messageId) {
    }

    /**
     * Полный цикл наказания нарушителя.
     * <p>
     * Порядок операций (W9): сначала бан, затем удаление сообщений. При сбое бана
     * контент не удаляется — админ видит, за что наказывали, и может добить вручную.
     *
     * @param reason   человекочитаемая причина для уведомления админа и лога
     * @param messages сообщения, которые нужно удалить после успешного бана
     * @return true, если бан применён в Telegram; false — сбой API, наказание можно повторить
     */
    public boolean ban(long chatId, long userId, String username, String reason, List<MessageRef> messages) {
        boolean banned;
        try {
            telegramApi.banChatMember(chatId, userId);
            banned = true;
        } catch (RuntimeException e) {
            banned = false;
            // BAN_API_ERROR — причина сбоя вызова (диагностика); событие-аудит
            // BAN_FAILED пишет AdminNotifierImpl, не дублируем
            log.error("BAN_API_ERROR chat={} user={} username={}: {}", chatId, userId, username, e.getMessage());
        }
        if (banned) {
            for (MessageRef message : messages) {
                try {
                    telegramApi.deleteMessage(message.chatId(), message.messageId());
                } catch (RuntimeException e) {
                    // Сообщение могло быть уже удалено (или прав нет) — бан уже применён
                    log.error("DELETE_FAILED chat={} message={}: {}", message.chatId(), message.messageId(), e.getMessage());
                }
            }
            try {
                // Запись создаётся и при бане «с холодного старта» (ссылка в первом
                // сообщении): иначе /banlist и /unban не увидят забаненного
                repository.findByChatIdAndUserId(chatId, userId).ifPresentOrElse(
                        participant -> {
                            participant.ban(clock.instant());
                            repository.save(participant);
                        },
                        () -> repository.save(Participant.newBanned(chatId, userId, username, clock.instant())));
            } catch (RuntimeException e) {
                // Бан в Telegram применён — сбой БД не должен ронять наказание;
                // рассинхрон виден в логах и лечится следующим нарушением/разбаном
                log.error("BAN_DB_UPDATE_FAILED chat={} user={} username={}: {}",
                        chatId, userId, username, e.getMessage());
            }
            // N5: аудит пишет AdminNotifier (VIOLATION/BAN_FAILED — всегда, независимо
            // от настроек уведомлений); summary-строка здесь дублировала бы его
            adminNotifier.violation(chatId, userId, username, reason);
        } else {
            // Статус в БД не меняем: пользователь всё ещё может писать
            adminNotifier.banFailed(chatId, userId, username, reason);
        }
        return banned;
    }
}
