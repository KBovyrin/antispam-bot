package ru.kbovyrin.antispam.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.DefaultLongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

import ru.kbovyrin.antispam.config.AntispamProperties;

/**
 * Приёмник обновлений long polling (telegrambots 10.x, P4 миграция 2026-11).
 * Наследует {@link DefaultLongPollingUpdateConsumer}: библиотека обрабатывает
 * пачку апдейтов по одному в ЕДИНОМ потоке — однопоточная модель проекта
 * (гонки W2/W3 аудита) сохранена прежней. Пер-инстансный executor библиотеки —
 * демонический, глушится close() сессии (deprecatedRoute-интерфейс не используем).
 */
@Component
public class BotUpdateReceiver extends DefaultLongPollingUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateReceiver.class);

    private final AntispamProperties properties;
    private final UpdateRouter router;

    public BotUpdateReceiver(AntispamProperties properties, UpdateRouter router) {
        this.properties = properties;
        this.router = router;
    }

    /** Имя бота для диагностики (в 10.x библиотека больше не спрашивает). */
    public String getBotUsername() {
        return properties.botUsername();
    }

    @Override
    public void consume(Update update) {
        try {
            router.route(update);
        } catch (RuntimeException e) {
            // Не даём одной ошибке остановить цикл polling.
            // В лог не пишем тело обновления — это тексты сообщений людей (N5)
            log.error("Ошибка обработки обновления {}", update.getUpdateId(), e);
        }
    }
}
