package ru.kbovyrin.antispam.db;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    Optional<Participant> findByChatIdAndUserId(long chatId, long userId);

    /** Забаненные в указанных чатах — для бан-листа владельца. */
    List<Participant> findByChatIdInAndStatus(List<Long> chatIds, Participant.Status status);

    /** Все забаненные записи пользователя в указанных чатах — для разбана «везде». */
    List<Participant> findByUserIdAndChatIdInAndStatus(long userId, List<Long> chatIds, Participant.Status status);
}
