package ru.kbovyrin.antispam.moderation;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.db.Participant;
import ru.kbovyrin.antispam.db.ParticipantRepository;
import ru.kbovyrin.antispam.telegram.TelegramApi;

/**
 * Разбан по команде владельца: убирает пользователя из чёрных списков всех
 * наблюдаемых групп и удаляет его записи из БД — следующее сообщение снова
 * получит капчу (история банов не хранится, миграция схемы не нужна).
 */
@Service
public class UnbanService {

    private static final Logger log = LoggerFactory.getLogger(UnbanService.class);

    private final AntispamProperties properties;
    private final ParticipantRepository repository;
    private final TelegramApi telegramApi;

    public UnbanService(AntispamProperties properties, ParticipantRepository repository, TelegramApi telegramApi) {
        this.properties = properties;
        this.repository = repository;
        this.telegramApi = telegramApi;
    }

    /**
     * @return количество групп, где бан успешно снят
     */
    public int unbanEverywhere(long userId) {
        List<Participant> banned = repository.findByUserIdAndChatIdInAndStatus(
                userId, properties.groupIds(), Participant.Status.BANNED);
        int unbanned = 0;
        for (Participant participant : banned) {
            try {
                telegramApi.unbanChatMember(participant.getChatId(), userId);
            } catch (RuntimeException e) {
                // Запись оставляем: владелец увидит в бан-листе и сможет повторить
                log.error("UNBAN_FAILED chat={} user={}: {}", participant.getChatId(), userId, e.getMessage());
                continue;
            }
            try {
                repository.delete(participant);
                unbanned++;
            } catch (RuntimeException e) {
                // Unban в Telegram применён, запись осталась: повторный /unban идемпотентен
                log.error("UNBAN_DB_DELETE_FAILED chat={} user={}: {}", participant.getChatId(), userId, e.getMessage());
            }
        }
        log.info("UNBAN user={} groups={} of {}", userId, unbanned, banned.size());
        return unbanned;
    }

    /** Забаненные участники наблюдаемых групп — для команды /banlist. */
    public List<Participant> bannedParticipants() {
        return repository.findByChatIdInAndStatus(properties.groupIds(), Participant.Status.BANNED);
    }
}
