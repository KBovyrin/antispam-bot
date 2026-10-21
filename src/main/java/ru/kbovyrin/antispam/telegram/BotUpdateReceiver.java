package ru.kbovyrin.antispam.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import ru.kbovyrin.antispam.config.AntispamProperties;

/**
 * Приёмник обновлений long polling. Регистрируется в TelegramBotsApi при старте
 * (PollingBootstrap) и передаёт все обновления в UpdateRouter.
 */
@Component
public class BotUpdateReceiver extends org.telegram.telegrambots.bots.TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateReceiver.class);

    private final AntispamProperties properties;
    private final UpdateRouter router;

    public BotUpdateReceiver(AntispamProperties properties, UpdateRouter router) {
        super();
        this.properties = properties;
        this.router = router;
    }

    @Override
    public String getBotUsername() {
        return properties.botUsername();
    }

    @Override
    public String getBotToken() {
        return properties.botToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            router.route(update);
        } catch (RuntimeException e) {
            // Не даём одной ошибке остановить цикл polling.
            // В лог не пишем тело обновления — это тексты сообщений людей (N5)
            log.error("Ошибка обработки обновления {}: {}", update.getUpdateId(), e.toString(), e);
        }
    }
}
