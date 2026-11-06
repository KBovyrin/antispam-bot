package ru.kbovyrin.antispam.telegram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import jakarta.annotation.PreDestroy;
import ru.kbovyrin.antispam.config.AntispamProperties;

/**
 * Регистрирует long polling при старте приложения (telegrambots 10.x, P4), если
 * бот включён. При остановке контекста глушит сессию полностью (W4): в 10.3.0
 * stop() лишь отменяет цикл — executor сессии держит JVM, поэтому гасим и его
 * (плюс демонические потоки реестра, см. AppConfig.pollingScheduler).
 * <p>
 * Таймауты/бэкофф сети — на стороне библиотеки (OkHttp: исходящие 10с,
 * long poll 75/100/70с + ExponentialBackOff), собственный BotHttpOptions
 * ушёл вместе с Apache HttpClient из 6.x.
 */
@Component
public class PollingBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PollingBootstrap.class);

    private final AntispamProperties properties;
    private final BotUpdateReceiver receiver;
    private final TelegramBotsLongPollingApplication botsApplication;

    private volatile BotSession session;

    public PollingBootstrap(AntispamProperties properties, BotUpdateReceiver receiver,
                            TelegramBotsLongPollingApplication botsApplication) {
        this.properties = properties;
        this.receiver = receiver;
        this.botsApplication = botsApplication;
    }

    @Override
    public void run(ApplicationArguments args) throws TelegramApiException {
        warnIfEnvFileTooOpen();
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
        session = botsApplication.registerBot(properties.botToken(), receiver);
        // registerBot в 10.x сам стартует сессию (nit ревью P4)
        log.info("Long polling запущен для бота @{}", properties.botUsername());
    }

    /**
     * Гигиена секрета (аудит 2026-11): .env, читаемый группой/остальными — путь
     * утечки токена. Проверка здесь, а не в DotenvEnvironmentPostProcessor: на фазе
     * EnvironmentPostProcessor логгинг ещё не инициализирован — WARN терялся.
     */
    private static void warnIfEnvFileTooOpen() {
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) {
            return;
        }
        try {
            var perms = Files.getPosixFilePermissions(envFile);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                log.warn("Файл .env читается группой или остальными пользователями — выполните chmod 600");
            }
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            // не-POSIX ФС или нет прав на stat — не блокируем старт
        }
    }

    @PreDestroy
    public void shutdown() {
        BotSession current = session;
        if (current == null) {
            return;
        }
        try {
            current.stop();
            // Блокер ревью/пентеста P4: в 10.3.0 stop() лишь отменяет polling,
            // executor сессии никто не глушит (даже close()) — глушим сами,
            // иначе JVM виснет после закрытия контекста до SIGKILL
            current.getExecutor().shutdownNow();
            log.info("Long polling остановлен");
        } catch (RuntimeException e) {
            log.error("Не удалось остановить long polling: {}", e.getMessage());
        }
    }
}
