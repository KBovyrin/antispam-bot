package ru.kbovyrin.antispam.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@EnableScheduling
@Configuration
public class AppConfig {

    /** Управляемые часы: в тестах подменяются фиксированными/подвижными. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Единый экземпляр API регистрации ботов: сам по себе polling не стартует —
     * сессии создаются в {@code PollingBootstrap.run()} через registerBot().
     */
    @Bean
    public TelegramBotsApi telegramBotsApi() throws TelegramApiException {
        return new TelegramBotsApi(DefaultBotSession.class);
    }
}
