package ru.kbovyrin.antispam.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.BotSession;

import jakarta.annotation.PreDestroy;
import ru.kbovyrin.antispam.config.AntispamProperties;

/**
 * Регистрирует long polling при старте приложения, если бот включён.
 * При остановке контекста останавливает сессию (W4): иначе потоки
 * DefaultBotSession держат JVM до SIGKILL и теряется плавность обновлений.
 */
@Component
public class PollingBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PollingBootstrap.class);

    private final AntispamProperties properties;
    private final BotUpdateReceiver receiver;
    private final TelegramBotsApi botsApi;

    private volatile BotSession session;

    public PollingBootstrap(AntispamProperties properties, BotUpdateReceiver receiver, TelegramBotsApi botsApi) {
        this.properties = properties;
        this.receiver = receiver;
        this.botsApi = botsApi;
    }

    @Override
    public void run(ApplicationArguments args) throws TelegramApiException {
        if (!properties.enabled()) {
            log.info("Бот отключён (ANTISPAM_ENABLED=false), long polling не запущен");
            return;
        }
        if (properties.botToken() == null || properties.botToken().isBlank()) {
            throw new IllegalStateException("BOT_TOKEN не задан — заполните .env (см. .env.example)");
        }
        if (properties.groupIds().isEmpty()) {
            log.warn("GROUP_IDS пуст — бот запущен, но не отслеживает ни одну группу");
        }
        session = botsApi.registerBot(receiver);
        log.info("Long polling запущен для бота @{}", properties.botUsername());
    }

    @PreDestroy
    public void shutdown() {
        BotSession current = session;
        if (current == null) {
            return;
        }
        try {
            current.stop();
            log.info("Long polling остановлен");
        } catch (RuntimeException e) {
            log.error("Не удалось остановить long polling: {}", e.getMessage());
        }
    }
}
