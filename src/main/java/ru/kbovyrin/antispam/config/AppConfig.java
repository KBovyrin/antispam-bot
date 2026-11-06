package ru.kbovyrin.antispam.config;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import okhttp3.OkHttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

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
     * Демонический планировщик для сессий long polling (блокер ревью/пентеста P4):
     * дефолтный supplier библиотеки создаёт НЕ-демон потоки, которые никто не
     * глушит — JVM висела после закрытия контекста. Демоны умирают сами.
     */
    public static ScheduledExecutorService pollingScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bots-longpolling");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Реестр long-polling сессий (telegrambots 10.x, P4): сессии создаются в
     * {@code PollingBootstrap.run()} через registerBot(). AutoCloseable —
     * Spring закрывает реестр при остановке контекста.
     */
    @Bean(destroyMethod = "close")
    public TelegramBotsLongPollingApplication botsApplication() {
        return new TelegramBotsLongPollingApplication(
                com.fasterxml.jackson.databind.ObjectMapper::new,
                OkHttpClient::new,
                AppConfig::pollingScheduler);
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
