package ru.kbovyrin.antispam.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import ru.kbovyrin.antispam.config.RateLimitProperties;

/**
 * P1 плана 2026-11: декоратор над TelegramApi выравнивает исходящие вызовы
 * минимальными интервалами и делает один ретрай с ожиданием retry_after на 429.
 */
class RateLimitedTelegramApiTest {

    static final long CHAT_A = -100L;
    static final long CHAT_B = -200L;

    TelegramApi delegate;
    List<Duration> sleeps;
    MutableClock clock;
    RateLimitedTelegramApi api;

    @BeforeEach
    void setUp() {
        delegate = mock(TelegramApi.class);
        sleeps = new ArrayList<>();
        clock = new MutableClock(Instant.parse("2026-11-06T12:00:00Z"));
        RateLimitProperties config = new RateLimitProperties(true,
                ofMillis(50), ofMillis(1100), ofSeconds(30));
        api = new RateLimitedTelegramApi(delegate, config, clock, sleeps::add);
    }

    private static TelegramBotAdapter.TelegramRequestException tooManyRequests(String message) {
        return new TelegramBotAdapter.TelegramRequestException("тест",
                new org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException(message));
    }

    // --- прозрачная делегация ---

    @Test
    void callsPassThroughToDelegate() {
        when(delegate.sendMessage(CHAT_A, "текст")).thenReturn(7L);

        assertThat(api.sendMessage(CHAT_A, "текст")).isEqualTo(7L);

        api.deleteMessage(CHAT_A, 5L);
        api.banChatMember(CHAT_A, 42L);
        api.answerCallbackQuery("cb", "ok");

        verify(delegate).sendMessage(CHAT_A, "текст");
        verify(delegate).deleteMessage(CHAT_A, 5L);
        verify(delegate).banChatMember(CHAT_A, 42L);
        verify(delegate).answerCallbackQuery("cb", "ok");
    }

    // --- глобальный минимальный интервал ---

    @Test
    void globalIntervalIsEnforcedBetweenCalls() {
        api.deleteMessage(CHAT_A, 1L);
        api.deleteMessage(CHAT_A, 2L);  // часы не двигались → пауза 50 мс
        api.deleteMessage(CHAT_A, 3L);  // очередь сдвинулась → пауза 100 мс

        assertThat(sleeps).containsExactly(ofMillis(50), ofMillis(100));
    }

    @Test
    void noSleepWhenClockAdvancedEnough() {
        api.deleteMessage(CHAT_A, 1L);
        clock.advance(ofMillis(60));
        api.deleteMessage(CHAT_A, 2L);

        assertThat(sleeps).isEmpty();
    }

    // --- интервал на чат только для sendMessage ---

    @Test
    void perChatIntervalAppliesOnlyToSendMessage() {
        api.sendMessage(CHAT_A, "первый");
        api.sendMessage(CHAT_B, "второй в другой чат — только глобальная пауза");

        assertThat(sleeps).containsExactly(ofMillis(50));

        api.sendMessage(CHAT_A, "тот же чат — пауза на чат больше глобальной");

        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(1)).isEqualTo(ofMillis(1100));
    }

    // --- 429: один ретрай после retry_after ---

    @Test
    void tooManyRequestsIsRetriedOnceAfterRetryAfter() {
        Mockito.doThrow(tooManyRequests("Too Many Requests: retry after 3"))
                .doReturn(9L)
                .when(delegate).sendMessage(CHAT_A, "текст");

        assertThat(api.sendMessage(CHAT_A, "текст")).isEqualTo(9L);

        verify(delegate, times(2)).sendMessage(CHAT_A, "текст");
        assertThat(sleeps).contains(ofSeconds(3));
    }

    @Test
    void retryAfterIsCappedByMaxRetryWait() {
        Mockito.doThrow(tooManyRequests("Too Many Requests: retry after 120"))
                .doNothing()
                .when(delegate).deleteMessage(CHAT_A, 1L);

        api.deleteMessage(CHAT_A, 1L);

        verify(delegate, times(2)).deleteMessage(CHAT_A, 1L);
        assertThat(sleeps).contains(ofSeconds(30));
    }

    @Test
    void repeatedTooManyRequestsFailsAfterSingleRetry() {
        Mockito.doThrow(tooManyRequests("Too Many Requests: retry after 1"))
                .doThrow(tooManyRequests("Too Many Requests: retry after 1"))
                .when(delegate).sendMessage(CHAT_A, "текст");

        assertThatThrownBy(() -> api.sendMessage(CHAT_A, "текст"))
                .isInstanceOf(RuntimeException.class);

        verify(delegate, times(2)).sendMessage(CHAT_A, "текст");
    }

    @Test
    void missingRetryAfterUsesDefaultWait() {
        Mockito.doThrow(tooManyRequests("Too Many Requests"))
                .doNothing()
                .when(delegate).deleteMessage(CHAT_A, 1L);

        api.deleteMessage(CHAT_A, 1L);

        verify(delegate, times(2)).deleteMessage(CHAT_A, 1L);
        assertThat(sleeps).contains(RateLimitedTelegramApi.DEFAULT_RETRY_WAIT);
    }

    // --- чужие ошибки идут мимо ретрая ---

    @Test
    void otherApiErrorsAreNotRetried() {
        Mockito.doThrow(new TelegramBotAdapter.TelegramRequestException("тест",
                        new org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException("Bad Request: chat not found")))
                .when(delegate).sendMessage(CHAT_A, "текст");

        assertThatThrownBy(() -> api.sendMessage(CHAT_A, "текст"))
                .isInstanceOf(RuntimeException.class);

        verify(delegate, times(1)).sendMessage(CHAT_A, "текст");
        // пауз только троттлинг (≤ глобального интервала), без ожидания retry_after
        assertThat(sleeps).allMatch(d -> d.compareTo(ofMillis(50)) <= 0);
    }

    @Test
    void retryAfterFromResponseParametersHasPriorityOverMessage() throws Exception {
        // warn ревью P1: приоритетный источник retry_after — ResponseParameters
        // (именно так библиотека наполняет ответ в production). Ложное «retry after 999»
        // в тексте не должно затмить параметр 7
        String json = "{\"ok\":false,\"error_code\":429,"
                + "\"description\":\"Too Many Requests: retry after 999\","
                + "\"parameters\":{\"retry_after\":7}}";
        var response = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<
                        org.telegram.telegrambots.meta.api.objects.ApiResponse<?>>() {
                });
        var cause = new org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException(
                "Too Many Requests", response);
        Mockito.doThrow(new TelegramBotAdapter.TelegramRequestException("тест", cause))
                .doNothing()
                .when(delegate).deleteMessage(CHAT_A, 1L);

        api.deleteMessage(CHAT_A, 1L);

        verify(delegate, times(2)).deleteMessage(CHAT_A, 1L);
        assertThat(sleeps).contains(ofSeconds(7));
        // путь парсинга сообщения дал бы min(999, cap) = 30с — их быть не должно
        assertThat(sleeps).doesNotContain(ofSeconds(30));
    }

    /** Подвижные часы: тест двигает время сам, паузы записывает sleeper. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
