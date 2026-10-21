package ru.kbovyrin.antispam.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

import ru.kbovyrin.antispam.telegram.TelegramApi;

class AdminRegistryTest {

    static final long CHAT = -100L;

    final Instant start = Instant.parse("2026-10-20T12:00:00Z");
    final MutableClock clock = new MutableClock(start);
    final TelegramApi telegramApi = mock(TelegramApi.class);

    AdminRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AdminRegistry(clock, telegramApi);
    }

    @Test
    void adminIsRecognizedViaApi() {
        when(telegramApi.getChatAdminIds(CHAT)).thenReturn(List.of(777L));

        assertThat(registry.isAdmin(CHAT, 777L)).isTrue();
        assertThat(registry.isAdmin(CHAT, 42L)).isFalse();
    }

    @Test
    void apiFailureWithoutCacheFailsOpen() {
        doThrow(new RuntimeException("api down")).when(telegramApi).getChatAdminIds(CHAT);

        // Fail-open (W2): цена перманентного бана админа несопоставима с пропуском
        // спама на время сбоя — при сбое без данных никого не наказываем
        assertThat(registry.isAdmin(CHAT, 42L)).isTrue();
    }

    @Test
    void unknownResultIsNegativeCached() {
        doThrow(new RuntimeException("api down")).when(telegramApi).getChatAdminIds(CHAT);

        registry.isAdmin(CHAT, 42L);
        registry.isAdmin(CHAT, 42L);

        // «Неизвестно» кешируется на короткий TTL — не по запросу API на каждое сообщение
        verify(telegramApi, times(1)).getChatAdminIds(CHAT);
    }

    @Test
    void registryRecoversAfterUnknownPeriod() {
        doThrow(new RuntimeException("api down")).doReturn(List.of(777L))
                .when(telegramApi).getChatAdminIds(CHAT);

        assertThat(registry.isAdmin(CHAT, 42L)).isTrue();
        clock.advance(Duration.ofSeconds(16));

        // Кеш «неизвестно» истёк — получили реальный список и работаем по нему
        assertThat(registry.isAdmin(CHAT, 777L)).isTrue();
        assertThat(registry.isAdmin(CHAT, 42L)).isFalse();
    }

    @Test
    void staleCacheIsUsedWhenApiFails() {
        when(telegramApi.getChatAdminIds(CHAT)).thenReturn(List.of(777L));
        assertThat(registry.isAdmin(CHAT, 777L)).isTrue();

        doThrow(new RuntimeException("api down")).when(telegramApi).getChatAdminIds(CHAT);
        clock.advance(Duration.ofSeconds(61));

        // Протухший кеш лучше пропуска: ложный negative отправляет админа под капчу
        assertThat(registry.isAdmin(CHAT, 777L)).isTrue();
        assertThat(registry.isAdmin(CHAT, 42L)).isFalse();
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

    @Test
    void staleCacheFailureBacksOffAndKeepsServingOldData() {
        when(telegramApi.getChatAdminIds(CHAT)).thenReturn(List.of(777L));
        registry.isAdmin(CHAT, 777L);

        clock.advance(Duration.ofSeconds(61));
        doThrow(new RuntimeException("api down")).when(telegramApi).getChatAdminIds(CHAT);

        // W2': сбой при протухшем кеше — работаем по старым данным...
        assertThat(registry.isAdmin(CHAT, 777L)).isTrue();
        // ...и не дёргаем лежащий API на каждом сообщении: бэккофф до следующего TTL
        assertThat(registry.isAdmin(CHAT, 42L)).isFalse();
        assertThat(registry.isAdmin(CHAT, 42L)).isFalse();

        verify(telegramApi, times(2)).getChatAdminIds(CHAT);
    }
}
