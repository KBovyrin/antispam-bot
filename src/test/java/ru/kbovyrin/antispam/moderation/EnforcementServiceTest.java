package ru.kbovyrin.antispam.moderation;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.kbovyrin.antispam.db.Participant;
import ru.kbovyrin.antispam.db.ParticipantRepository;
import ru.kbovyrin.antispam.notify.AdminNotifier;
import ru.kbovyrin.antispam.telegram.TelegramApi;
import ru.kbovyrin.antispam.telegram.TelegramBotAdapter.TelegramRequestException;

@ExtendWith(MockitoExtension.class)
class EnforcementServiceTest {

    static final long CHAT = -100L;
    static final long USER = 42L;

    @Mock
    ParticipantRepository repository;
    @Mock
    TelegramApi telegramApi;
    @Mock
    AdminNotifier adminNotifier;

    EnforcementService service;

    @BeforeEach
    void setUp() {
        service = new EnforcementService(repository, telegramApi, adminNotifier, Clock.systemUTC());
    }

    private static TelegramRequestException apiError() {
        return new TelegramRequestException("test", new RuntimeException("api down"));
    }

    @Test
    void banDeletesMessagesBansAndNotifies() {
        service.ban(CHAT, USER, "vasya", "причина", List.of(new EnforcementService.MessageRef(CHAT, 1L)));

        verify(telegramApi).deleteMessage(CHAT, 1L);
        verify(telegramApi).banChatMember(CHAT, USER);
        verify(adminNotifier).violation(eq(CHAT), eq(USER), eq("vasya"), eq("причина"));
    }

    @Test
    void failedMessageDeletionDoesNotPreventBan() {
        doThrow(apiError()).when(telegramApi).deleteMessage(CHAT, 1L);

        service.ban(CHAT, USER, "vasya", "причина", List.of(new EnforcementService.MessageRef(CHAT, 1L)));

        verify(telegramApi).banChatMember(CHAT, USER);
        verify(adminNotifier).violation(eq(CHAT), eq(USER), eq("vasya"), eq("причина"));
    }

    @Test
    void failedBanSkipsStatusChangeAndReportsToAdmin() {
        doThrow(apiError()).when(telegramApi).banChatMember(CHAT, USER);

        service.ban(CHAT, USER, "vasya", "причина", List.of());

        // Статус в БД не меняется: пользователь ещё не забанен в Telegram
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        // Админ получает отдельное уведомление о сбое
        verify(adminNotifier).banFailed(eq(CHAT), eq(USER), eq("vasya"), contains("причина"));
        verify(adminNotifier, never()).violation(eq(CHAT), eq(USER), eq("vasya"), eq("причина"));
    }

    @Test
    void banReturnsTrueOnSuccessAndFalseOnApiFailure() {
        org.assertj.core.api.Assertions.assertThat(
                service.ban(CHAT, USER, "vasya", "причина", List.of())).isTrue();

        doThrow(apiError()).when(telegramApi).banChatMember(CHAT, USER);

        org.assertj.core.api.Assertions.assertThat(
                service.ban(CHAT, USER, "vasya", "причина", List.of())).isFalse();
    }

    @Test
    void banHappensBeforeMessageDeletion() {
        service.ban(CHAT, USER, "vasya", "причина", List.of(new EnforcementService.MessageRef(CHAT, 1L)));

        // Сначала бан, затем зачистка (W9): при сбое бана контент виден админу
        var inOrder = inOrder(telegramApi);
        inOrder.verify(telegramApi).banChatMember(CHAT, USER);
        inOrder.verify(telegramApi).deleteMessage(CHAT, 1L);
    }

    @Test
    void failedBanKeepsMessagesForManualReview() {
        doThrow(apiError()).when(telegramApi).banChatMember(CHAT, USER);

        service.ban(CHAT, USER, "vasya", "причина", List.of(new EnforcementService.MessageRef(CHAT, 1L)));

        // Бан не прошёл — сообщения не трогаем: админ увидит, что именно постили
        verify(telegramApi, never()).deleteMessage(anyLong(), anyLong());
    }

    @Test
    void banCreatesBannedRecordWhenAbsent() {
        // Бан по ссылке на первом сообщении: записи ещё нет — она должна создаться,
        // иначе /banlist и /unban не увидят забаненного (блокер ревью)
        org.mockito.Mockito.when(repository.findByChatIdAndUserId(CHAT, USER))
                .thenReturn(java.util.Optional.empty());

        service.ban(CHAT, USER, "vasya", "причина", List.of());

        var captor = org.mockito.ArgumentCaptor.forClass(Participant.class);
        verify(repository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getStatus())
                .isEqualTo(Participant.Status.BANNED);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getUserId()).isEqualTo(USER);
    }

    @Test
    void banDbFailureAfterSuccessfulBanIsContained() {
        // Бан применён в Telegram — сбой записи не должен ронять обработку
        org.mockito.Mockito.when(repository.findByChatIdAndUserId(CHAT, USER))
                .thenThrow(new RuntimeException("db busy"));

        boolean banned = service.ban(CHAT, USER, "vasya", "причина", List.of());

        org.assertj.core.api.Assertions.assertThat(banned).isTrue();
        verify(adminNotifier).violation(eq(CHAT), eq(USER), eq("vasya"), eq("причина"));
    }
}
