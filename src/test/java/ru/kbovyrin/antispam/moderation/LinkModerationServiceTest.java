package ru.kbovyrin.antispam.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.kbovyrin.antispam.db.ParticipantRepository;
import ru.kbovyrin.antispam.notify.AdminNotifier;
import ru.kbovyrin.antispam.spam.LinkDetector;
import ru.kbovyrin.antispam.telegram.TelegramApi;

@ExtendWith(MockitoExtension.class)
class LinkModerationServiceTest {

    static final long CHAT = -100L;
    static final long USER = 42L;
    static final long MSG = 321L;
    static final String USERNAME = "vasya";

    final Instant start = Instant.parse("2026-10-20T12:00:00Z");
    final MutableClock clock = new MutableClock(start);

    @Mock
    ParticipantRepository repository;
    @Mock
    TelegramApi telegramApi;
    @Mock
    AdminNotifier adminNotifier;

    LinkModerationService service;

    @BeforeEach
    void setUp() {
        when(telegramApi.getChatAdminIds(CHAT)).thenReturn(List.of(777L));
        service = new LinkModerationService(new AdminRegistry(clock, telegramApi), new LinkDetector(),
                new EnforcementService(repository, telegramApi, adminNotifier, clock));
    }

    @Test
    void messageWithLinkFromRegularUserIsDeletedAndUserBanned() {
        boolean banned = service.handle(CHAT, USER, USERNAME, MSG, "смотри http://spam.example", List.of());

        assertThat(banned).isTrue();
        verify(telegramApi).deleteMessage(CHAT, MSG);
        verify(telegramApi).banChatMember(CHAT, USER);
        verify(adminNotifier).violation(eq(CHAT), eq(USER), eq(USERNAME), contains("ссылк"));
    }

    @Test
    void messageWithMentionOnlyIsViolation() {
        service.handle(CHAT, USER, USERNAME, MSG, "реклама у @spamchannel", List.of());

        verify(telegramApi).banChatMember(CHAT, USER);
    }

    @Test
    void captionWithLinkIsViolation() {
        service.handle(CHAT, USER, USERNAME, MSG, "https://spam.example дешево", List.of());

        verify(telegramApi).banChatMember(CHAT, USER);
    }

    @Test
    void messageWithoutLinkIsIgnored() {
        boolean banned = service.handle(CHAT, USER, USERNAME, MSG, "просто текст без ссылок", List.of());

        assertThat(banned).isFalse();
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(telegramApi, never()).deleteMessage(anyLong(), anyLong());
        verify(adminNotifier, never()).violation(anyLong(), anyLong(), any(), any());
    }

    @Test
    void handleReturnsFalseWhenBanFails() {
        // Сбой API: юзер НЕ забанен — роутер не должен гасить капчу (semantics W1)
        doThrow(new RuntimeException("api down")).when(telegramApi).banChatMember(CHAT, USER);

        boolean banned = service.handle(CHAT, USER, USERNAME, MSG, "ссылка http://spam.example", List.of());

        assertThat(banned).isFalse();
        verify(adminNotifier).banFailed(anyLong(), anyLong(), any(), any());
    }

    @Test
    void nullContentIsIgnored() {
        service.handle(CHAT, USER, USERNAME, MSG, null, List.of());

        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
    }

    @Test
    void adminIsExemptEvenWithLink() {
        service.handle(CHAT, 777L, "boss", MSG, "https://example.com разрешено", List.of());

        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(telegramApi, never()).deleteMessage(anyLong(), anyLong());
    }

    @Test
    void adminListIsCachedWithinTtl() {
        service.handle(CHAT, USER, USERNAME, MSG, "первое сообщение", List.of());
        service.handle(CHAT, USER, USERNAME, MSG + 1, "второе сообщение", List.of());
        verify(telegramApi, times(1)).getChatAdminIds(CHAT);

        clock.advance(Duration.ofSeconds(61));
        service.handle(CHAT, USER, USERNAME, MSG + 2, "третье сообщение", List.of());
        verify(telegramApi, times(2)).getChatAdminIds(CHAT);
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
