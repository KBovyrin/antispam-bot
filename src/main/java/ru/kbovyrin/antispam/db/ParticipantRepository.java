package ru.kbovyrin.antispam.db;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    Optional<Participant> findByChatIdAndUserId(long chatId, long userId);

    /** Забаненные в указанных чатах — для бан-листа владельца. */
    List<Participant> findByChatIdInAndStatus(List<Long> chatIds, Participant.Status status);

    /** Все забаненные записи пользователя в указанных чатах — для разбана «везде». */
    List<Participant> findByUserIdAndChatIdInAndStatus(long userId, List<Long> chatIds, Participant.Status status);

    /**
     * Точечное обновление username существующей записи (W2 аудита 2026-11):
     * меняет только username/updatedAt и физически не может затереть статус,
     * записанный параллельным писателем (таймаут-бан ↔ смена username).
     *
     * @return 1, если запись найдена и обновлена; 0 — записи нет
     */
    @Modifying
    @Query("update Participant p set p.username = :username, p.updatedAt = :updatedAt"
            + " where p.chatId = :chatId and p.userId = :userId")
    @Transactional
    int updateUsername(long chatId, long userId, String username, Instant updatedAt);

    /**
     * Точечное обновление статуса существующей записи ( EnforcementService):
     * меняет только status/updatedAt, username параллельного писателя не трогает.
     *
     * @return 1, если запись найдена и обновлена; 0 — записи нет
     */
    @Modifying
    @Query("update Participant p set p.status = :status, p.updatedAt = :updatedAt"
            + " where p.chatId = :chatId and p.userId = :userId")
    @Transactional
    int updateStatus(long chatId, long userId, Participant.Status status, Instant updatedAt);

    /** Синоним updateStatus(BANNED) для читаемости в EnforcementService. */
    default int markBanned(long chatId, long userId, Instant updatedAt) {
        return updateStatus(chatId, userId, Participant.Status.BANNED, updatedAt);
    }
}
