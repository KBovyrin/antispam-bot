package ru.kbovyrin.antispam.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import ru.kbovyrin.antispam.telegram.RateLimitedTelegramApi;
import ru.kbovyrin.antispam.telegram.TelegramApi;
import ru.kbovyrin.antispam.telegram.TelegramBotAdapter;

@EnableScheduling
@Configuration
public class AppConfig {

    /** Управляемые часы: в тестах подменяются фиксированными/подвижными. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Единица API регистрации ботов: сам по себе polling не стартует —
     * сессии создаются в {@code PollingBootstrap.run()} через registerBot().
     */
    @Bean
    public TelegramBotsApi telegramBotsApi() throws TelegramApiException {
        return new TelegramBotsApi(DefaultBotSession.class);
    }

    /** Сырой адаптер над библиотекой (без троттлинга). */
    @Bean
    public TelegramBotAdapter telegramBotAdapter(AntispamProperties properties) {
        return new TelegramBotAdapter(properties);
    }

    /**
     * TelegramApi для сервисов — с троттлингом и одним ретраем на 429 (P1 2026-11).
     * @Primary снимает неоднозначность: кандидат — ещё и сам адаптер.
     */
    @Bean
    @Primary
    public TelegramApi telegramApi(TelegramBotAdapter adapter, RateLimitProperties rateLimit, Clock clock) {
        if (!rateLimit.enabled()) {
            return adapter;
        }
        return new RateLimitedTelegramApi(adapter, rateLimit, clock, RateLimitedTelegramApi.PROD_SLEEPER);
    }
}
