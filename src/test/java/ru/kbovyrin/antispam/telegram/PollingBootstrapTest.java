package ru.kbovyrin.antispam.telegram;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.meta.generics.LongPollingBot;

import ru.kbovyrin.antispam.config.AntispamProperties;

@ExtendWith(MockitoExtension.class)
class PollingBootstrapTest {

    @Mock
    TelegramBotsApi botsApi;
    @Mock
    BotUpdateReceiver receiver;
    @Mock
    BotSession session;

    static AntispamProperties props(boolean enabled, String token, List<Long> groupIds) {
        return new AntispamProperties(enabled, token, "bot", 1L, groupIds,
                new AntispamProperties.Captcha(Duration.ofMinutes(5), "q"));
    }

    PollingBootstrap bootstrap(AntispamProperties properties) {
        return new PollingBootstrap(properties, receiver, botsApi);
    }

    @Test
    void disabledBotDoesNotRegisterPolling() throws Exception {
        bootstrap(props(false, "tok", List.of(-100L))).run(null);

        verify(botsApi, never()).registerBot(any(LongPollingBot.class));
    }

    @Test
    void missingTokenFailsFast() throws Exception {
        assertThatThrownBy(() -> bootstrap(props(true, "  ", List.of(-100L))).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOT_TOKEN");
        verify(botsApi, never()).registerBot(any(LongPollingBot.class));
    }

    @Test
    void emptyGroupListStillRegistersPolling() throws Exception {
        when(botsApi.registerBot(receiver)).thenReturn(session);

        bootstrap(props(true, "tok", List.of())).run(null);

        verify(botsApi).registerBot(receiver);
    }

    @Test
    void shutdownStopsRegisteredSession() throws Exception {
        when(botsApi.registerBot(receiver)).thenReturn(session);
        PollingBootstrap running = bootstrap(props(true, "tok", List.of(-100L)));
        running.run(null);

        running.shutdown();

        verify(session).stop();
    }

    @Test
    void shutdownWithoutRegistrationIsSafe() {
        bootstrap(props(true, "tok", List.of(-100L))).shutdown();

        verifyNoInteractions(session);
    }
}
