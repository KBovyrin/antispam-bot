package ru.kbovyrin.antispam.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.apache.http.client.config.RequestConfig;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.bots.DefaultBotOptions;

import ru.kbovyrin.antispam.config.AntispamProperties;

/**
 * W3: сетевые таймауты должны быть заданы явно. Молчаливый дефолт библиотеки —
 * 75 секунд на каждый вызов, а апдейты обрабатываются одним потоком: зависший
 * запрос блокировал бы всю модерацию на минуту с четвертью.
 */
class BotHttpOptionsTest {

    private static AntispamProperties properties() {
        return new AntispamProperties(true, "tok", "bot", 1L, List.of(-100L),
                new AntispamProperties.Captcha(Duration.ofMinutes(5), "q"));
    }

    private static void assertExplicitTimeouts(RequestConfig config) {
        assertThat(config).isNotNull();
        assertThat(config.getConnectTimeout()).isEqualTo(5_000);
        assertThat(config.getConnectionRequestTimeout()).isEqualTo(10_000);
        // Больше 50-секундного long poll getUpdates — иначе polling рвётся
        assertThat(config.getSocketTimeout()).isEqualTo(65_000);
    }

    @Test
    void pollingReceiverHasExplicitNetworkTimeouts() {
        BotUpdateReceiver receiver = new BotUpdateReceiver(properties(), mock(UpdateRouter.class));

        DefaultBotOptions options = receiver.getOptions();
        assertExplicitTimeouts(options.getRequestConfig());
    }

    @Test
    void apiAdapterHasExplicitNetworkTimeouts() {
        TelegramBotAdapter adapter = new TelegramBotAdapter(properties());

        DefaultBotOptions options = adapter.getOptions();
        assertExplicitTimeouts(options.getRequestConfig());
    }
}
