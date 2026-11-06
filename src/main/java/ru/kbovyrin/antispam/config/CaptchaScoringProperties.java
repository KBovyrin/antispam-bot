package ru.kbovyrin.antispam.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Пороги скоринга капчи (P3 плана 2026-11): детерминированные анти-юзербот
 * сигналы поверх эмодзи-загадки.
 * <p>
 * minAnswerTime — верный ответ раньше этого срока считается машинным подбором;
 * floodBanThreshold — столько сообщений до ответа на капчу расценивается как шторм.
 * Значения утверждались владельцем (1 с / 10 сообщений), подстройка — по логам банов.
 */
@ConfigurationProperties(prefix = "antispam.captcha-scoring")
public record CaptchaScoringProperties(
        Duration minAnswerTime,
        int floodBanThreshold) {
}
