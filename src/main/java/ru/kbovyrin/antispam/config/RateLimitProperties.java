package ru.kbovyrin.antispam.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки троттлинга исходящих вызовов Telegram (P1 плана 2026-11).
 * <p>
 * Глобальный интервал — общий поток бота; интервал на чат — лимит Telegram
 * «~1 сообщение в секунду в группу» с запасом; cap ожидания retry_after —
 * чтобы один 429 не заморозил поток обработки на минуты.
 */
@ConfigurationProperties(prefix = "antispam.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        Duration globalInterval,
        Duration perChatSendInterval,
        Duration maxRetryWait) {
}
