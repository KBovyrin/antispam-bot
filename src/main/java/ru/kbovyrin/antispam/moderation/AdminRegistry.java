package ru.kbovyrin.antispam.moderation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.kbovyrin.antispam.telegram.TelegramApi;

/**
 * Кеш списков администраторов чатов: без обращения к API на каждое сообщение.
 * <p>
 * Отказоустойчивость (W2): при сбое API без данных включается fail-open —
 * пользователь считается админом и не наказывается. Цена перманентного бана
 * админа (механизма разбана может не быть под рукой) несопоставима с пропуском
 * спама на время сбоя. Результат «неизвестно» кешируется на короткий TTL,
 * чтобы не порождать запрос к API на каждое сообщение.
 */
@Component
public class AdminRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdminRegistry.class);

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Duration UNKNOWN_TTL = Duration.ofSeconds(15);

    private final Clock clock;
    private final TelegramApi telegramApi;

    private final Map<Long, CachedAdmins> cache = new ConcurrentHashMap<>();

    /** ids == null означает «неизвестно» (сбой API) — все считаются админами. */
    private record CachedAdmins(List<Long> ids, Instant fetchedAt) {
    }

    public AdminRegistry(Clock clock, TelegramApi telegramApi) {
        this.clock = clock;
        this.telegramApi = telegramApi;
    }

    public boolean isAdmin(long chatId, long userId) {
        Instant now = clock.instant();
        CachedAdmins cached = cache.get(chatId);
        if (needsRefresh(cached, now)) {
            try {
                cache.put(chatId, new CachedAdmins(telegramApi.getChatAdminIds(chatId), now));
            } catch (RuntimeException e) {
                if (cached == null) {
                    // Нет даже протухшего кеша — фиксируем «неизвестно» (fail-open)
                    cache.put(chatId, new CachedAdmins(null, now));
                } else {
                    // W2': данные остались старыми, но следующая попытка — не раньше
                    // чем через TTL, иначе каждое сообщение группы дёргает лежащий API
                    // и съедает общий rate-limit бота
                    cache.put(chatId, new CachedAdmins(cached.ids(), now));
                }
                // Бэккофф ограничивает сбои одним warn-ом на TTL
                log.warn("ADMIN_REFRESH_FAILED chat={}: {}", chatId, e.getMessage());
            }
        }
        List<Long> ids = cache.get(chatId).ids();
        return ids == null || ids.contains(userId);
    }

    private static boolean needsRefresh(CachedAdmins cached, Instant now) {
        if (cached == null) {
            return true;
        }
        Duration ttl = cached.ids() == null ? UNKNOWN_TTL : TTL;
        return cached.fetchedAt().plus(ttl).isBefore(now);
    }
}
