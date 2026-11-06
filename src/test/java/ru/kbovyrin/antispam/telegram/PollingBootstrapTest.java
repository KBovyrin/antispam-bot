package ru.kbovyrin.antispam.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import ru.kbovyrin.antispam.config.AntispamProperties;

@ExtendWith(MockitoExtension.class)
class PollingBootstrapTest {

    @Mock
    TelegramBotsLongPollingApplication botsApplication;
    @Mock
    BotUpdateReceiver receiver;
    @Mock
    BotSession session;

    static AntispamProperties props(boolean enabled, String token, List<Long> groupIds) {
        return new AntispamProperties(enabled, token, "bot", 1L, groupIds,
                new AntispamProperties.Captcha(Duration.ofMinutes(5), "q"));
    }

    PollingBootstrap bootstrap(AntispamProperties properties) {
        return new PollingBootstrap(properties, receiver, botsApplication);
    }

    @Test
    void disabledBotDoesNotRegisterPolling() throws Exception {
        bootstrap(props(false, "tok", List.of(-100L))).run(null);

        verify(botsApplication, never()).registerBot(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingTokenFailsFast() throws Exception {
        assertThatThrownBy(() -> bootstrap(props(true, "  ", List.of(-100L))).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOT_TOKEN");
        verify(botsApplication, never()).registerBot(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emptyGroupListStillRegistersPolling() throws Exception {
        when(botsApplication.registerBot("tok", receiver)).thenReturn(session);

        bootstrap(props(true, "tok", List.of())).run(null);

        verify(botsApplication).registerBot("tok", receiver);
    }

    @Test
    void shutdownStopsSessionAndKillsExecutor() throws Exception {
        // Блокер ревью/пентеста P4: в 10.3.0 stop() лишь отменяет polling,
        // а не-демон executor сессии держит JVM навсегда — глушим принудительно
        java.util.concurrent.ScheduledExecutorService executor =
                mock(java.util.concurrent.ScheduledExecutorService.class);
        when(botsApplication.registerBot("tok", receiver)).thenReturn(session);
        when(session.getExecutor()).thenReturn(executor);
        PollingBootstrap running = bootstrap(props(true, "tok", List.of(-100L)));
        running.run(null);

        running.shutdown();

        verify(session).stop();
        verify(executor).shutdownNow();
    }

    @Test
    void shutdownWithoutRegistrationIsSafe() {
        bootstrap(props(true, "tok", List.of(-100L))).shutdown();

        verifyNoInteractions(session);
    }

    @Test
    void pollingSchedulerRunsDaemonThreads() throws Exception {
        // Блокер P4 (вторая половина): executor реестра обязан быть демоническим —
        // даже забытый shutdownNow не должен держать JVM при выходе
        var daemon = new java.util.concurrent.atomic.AtomicBoolean(false);
        var latch = new java.util.concurrent.CountDownLatch(1);
        try (var executor = ru.kbovyrin.antispam.config.AppConfig.pollingScheduler()) {
            executor.execute(() -> {
                daemon.set(Thread.currentThread().isDaemon());
                latch.countDown();
            });
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(daemon).isTrue();
    }
}
