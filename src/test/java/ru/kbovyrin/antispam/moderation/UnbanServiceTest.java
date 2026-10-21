package ru.kbovyrin.antispam.moderation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.db.Participant;
import ru.kbovyrin.antispam.db.ParticipantRepository;
import ru.kbovyrin.antispam.telegram.TelegramApi;
import ru.kbovyrin.antispam.telegram.TelegramBotAdapter.TelegramRequestException;

@ExtendWith(MockitoExtension.class)
class UnbanServiceTest {

    static final long GROUP_A = -100L;
    static final long GROUP_B = -200L;
    static final long ADMIN = 777L;

    @Mock
    ParticipantRepository repository;
    @Mock
    TelegramApi telegramApi;

    UnbanService service;

    @BeforeEach
    void setUp() {
        var props = new AntispamProperties(true, "tok", "bot", ADMIN, List.of(GROUP_A, GROUP_B),
                new AntispamProperties.Captcha(java.time.Duration.ofMinutes(5), "q"));
        service = new UnbanService(props, repository, telegramApi);
    }

    private static Participant banned(long chatId, long userId, String username) {
        Participant p = Participant.newPending(chatId, userId, username, java.time.Instant.now());
        p.ban(java.time.Instant.now());
        return p;
    }

    @Test
    void unbanEverywhereRemovesBlacklistAndRecordInAllWatchedGroups() {
        Participant inA = banned(GROUP_A, 42L, "vasya");
        Participant inB = banned(GROUP_B, 42L, "vasya");
        when(repository.findByUserIdAndChatIdInAndStatus(42L, List.of(GROUP_A, GROUP_B),
                Participant.Status.BANNED)).thenReturn(List.of(inA, inB));

        int count = service.unbanEverywhere(42L);

        assertThat(count).isEqualTo(2);
        verify(telegramApi).unbanChatMember(GROUP_A, 42L);
        verify(telegramApi).unbanChatMember(GROUP_B, 42L);
        verify(repository).delete(inA);
        verify(repository).delete(inB);
    }

    @Test
    void unbanEverywhereWithoutBannedRecordsIsZero() {
        when(repository.findByUserIdAndChatIdInAndStatus(42L, List.of(GROUP_A, GROUP_B),
                Participant.Status.BANNED)).thenReturn(List.of());

        assertThat(service.unbanEverywhere(42L)).isZero();

        verify(telegramApi, never()).unbanChatMember(anyLong(), anyLong());
        verify(repository, never()).delete(any());
    }

    @Test
    void apiFailureInOneGroupKeepsRecordAndContinuesWithOthers() {
        Participant inA = banned(GROUP_A, 42L, "vasya");
        Participant inB = banned(GROUP_B, 42L, "vasya");
        when(repository.findByUserIdAndChatIdInAndStatus(42L, List.of(GROUP_A, GROUP_B),
                Participant.Status.BANNED)).thenReturn(List.of(inA, inB));
        doThrow(new TelegramRequestException("test", new RuntimeException("api down")))
                .when(telegramApi).unbanChatMember(GROUP_A, 42L);

        int count = service.unbanEverywhere(42L);

        assertThat(count).isEqualTo(1);
        verify(repository, never()).delete(inA);
        verify(repository).delete(inB);
    }

    @Test
    void deleteFailureIsContainedAndContinuesWithOthers() {
        Participant inA = banned(GROUP_A, 42L, "vasya");
        Participant inB = banned(GROUP_B, 42L, "vasya");
        when(repository.findByUserIdAndChatIdInAndStatus(42L, List.of(GROUP_A, GROUP_B),
                Participant.Status.BANNED)).thenReturn(List.of(inA, inB));
        doThrow(new RuntimeException("db busy")).when(repository).delete(inA);

        int count = service.unbanEverywhere(42L);

        // Сбой удаления записи в одной группе не роняет разбан в остальных
        assertThat(count).isEqualTo(1);
        verify(repository).delete(inB);
    }

    @Test
    void bannedParticipantsComeFromRepository() {
        when(repository.findByChatIdInAndStatus(List.of(GROUP_A, GROUP_B), Participant.Status.BANNED))
                .thenReturn(List.of(banned(GROUP_A, 42L, "vasya")));

        List<Participant> result = service.bannedParticipants();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo(42L);
    }
}
