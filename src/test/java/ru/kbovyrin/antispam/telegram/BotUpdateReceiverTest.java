package ru.kbovyrin.antispam.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

import ru.kbovyrin.antispam.config.AntispamProperties;

class BotUpdateReceiverTest {

    @Test
    void routerExceptionDoesNotKillPollingLoop() {
        var props = new AntispamProperties(true, "tok", "bot", 1L, List.of(-100L),
                new AntispamProperties.Captcha(Duration.ofMinutes(5), "q"));
        UpdateRouter router = mock(UpdateRouter.class);
        doThrow(new RuntimeException("boom")).when(router).route(any(Update.class));
        BotUpdateReceiver receiver = new BotUpdateReceiver(props, router);

        // Исключение из роутера проглатывается — polling продолжает работать
        receiver.onUpdateReceived(new Update());

        verify(router).route(any(Update.class));
    }
}
