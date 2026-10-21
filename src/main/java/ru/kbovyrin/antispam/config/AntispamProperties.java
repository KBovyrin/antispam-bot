package ru.kbovyrin.antispam.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки бота. Значения по умолчанию задаются в application.yml через переменные окружения.
 */
@ConfigurationProperties(prefix = "antispam")
public record AntispamProperties(
        boolean enabled,
        String botToken,
        String botUsername,
        long adminChatId,
        List<Long> groupIds,
        Captcha captcha) {

    public record Captcha(Duration timeout, String prompt) {
    }
}
