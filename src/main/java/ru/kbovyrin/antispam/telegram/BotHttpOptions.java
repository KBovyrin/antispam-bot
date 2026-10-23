package ru.kbovyrin.antispam.telegram;

import org.apache.http.client.config.RequestConfig;
import org.telegram.telegrambots.bots.DefaultBotOptions;

/**
 * Явные сетевые таймауты вместо молчаливых 75 секунд библиотеки (W3):
 * обновления обрабатываются одним потоком, и зависший вызов API блокирует
 * всю модерацию. Connect-фаза (типичное место зависания) — 5 с, ожидание
 * соединения из пула — 10 с.
 * <p>
 * Socket timeout обязан оставаться больше 50-секундного long poll getUpdates
 * (ApiConstants.GETUPDATES_TIMEOUT), иначе цикл polling начнёт рваться по
 * read timeout — поэтому для него выбрано 65 с, а не «чем меньше, тем лучше».
 * Это осознанное ограничение библиотеки: она использует один RequestConfig
 * и для polling, и для обычных вызовов.
 */
final class BotHttpOptions {

    private BotHttpOptions() {
    }

    static DefaultBotOptions create() {
        DefaultBotOptions options = new DefaultBotOptions();
        options.setRequestConfig(RequestConfig.custom()
                .setConnectTimeout(5_000)
                .setConnectionRequestTimeout(10_000)
                .setSocketTimeout(65_000)
                .build());
        return options;
    }
}
