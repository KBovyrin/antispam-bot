package ru.kbovyrin.antispam.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import ru.kbovyrin.antispam.config.RateLimitProperties;

/**
 * Декоратор над {@link TelegramApi}: выравнивает исходящие вызовы минимальными
 * интервалами (глобальным и на чат для sendMessage) и делает РОВНО один ретрай
 * с ожиданием retry_after при 429 Too Many Requests (P1 плана 2026-11).
 * <p>
 * Блокирующая модель осознанна: обновления буферизуются offset'ом long polling,
 * а выравнивание в потоке гарантирует, что всплеск новичков или шторм мусора
 * не выжжет общий лимит бота (демон №3 аудита: 429 → fail-open модерации).
 */
public class RateLimitedTelegramApi implements TelegramApi {

    /** Ожидание при 429 без указанного retry_after. */
    static final Duration DEFAULT_RETRY_WAIT = Duration.ofSeconds(1);
    private static final Pattern RETRY_AFTER_IN_MESSAGE = Pattern.compile("retry after (\\d+)", Pattern.CASE_INSENSITIVE);

    /** Пауза — шов внедрения (тесты подменяют), это часть дизайна, не инструментация. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);
    }

    /** Продакшн-пауза: прерывание восстанавливает флаг и не блокирует поток навечно. */
    public static final Sleeper PROD_SLEEPER = duration -> {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    private final TelegramApi delegate;
    private final RateLimitProperties config;
    private final Clock clock;
    private final Sleeper sleeper;

    private Instant nextGlobalAllowed = Instant.EPOCH;
    private final Map<Long, Instant> nextPerChatSend = new ConcurrentHashMap<>();

    public RateLimitedTelegramApi(TelegramApi delegate, RateLimitProperties config,
                                  Clock clock, Sleeper sleeper) {
        this.delegate = delegate;
        this.config = config;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public long sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
        return call(chatId, true, () -> delegate.sendMessage(chatId, text, keyboard));
    }

    @Override
    public long sendMessage(long chatId, String text) {
        return call(chatId, true, () -> delegate.sendMessage(chatId, text));
    }

    @Override
    public void editMessageText(long chatId, long messageId, String text) {
        call(chatId, false, () -> {
            delegate.editMessageText(chatId, messageId, text);
            return null;
        });
    }

    @Override
    public void deleteMessage(long chatId, long messageId) {
        call(chatId, false, () -> {
            delegate.deleteMessage(chatId, messageId);
            return null;
        });
    }

    @Override
    public void banChatMember(long chatId, long userId) {
        call(chatId, false, () -> {
            delegate.banChatMember(chatId, userId);
            return null;
        });
    }

    @Override
    public void unbanChatMember(long chatId, long userId) {
        call(chatId, false, () -> {
            delegate.unbanChatMember(chatId, userId);
            return null;
        });
    }

    @Override
    public String getChatMemberStatus(long chatId, long userId) {
        return call(chatId, false, () -> delegate.getChatMemberStatus(chatId, userId));
    }

    @Override
    public List<Long> getChatAdminIds(long chatId) {
        return call(chatId, false, () -> delegate.getChatAdminIds(chatId));
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId, String text) {
        call(null, false, () -> {
            delegate.answerCallbackQuery(callbackQueryId, text);
            return null;
        });
    }

    /**
     * Пейсинг + один ретрай на 429. Повторный 429 и любые чужие ошибки идут
     * наружу — их встречает штатная обработка сбоев сервисов.
     */
    private <T> T call(Long chatId, boolean perChat, Supplier<T> action) {
        pace(chatId, perChat);
        try {
            return action.get();
        } catch (RuntimeException e) {
            long retryAfter = retryAfterSeconds(e);
            if (retryAfter < 0) {
                throw e;
            }
            sleeper.sleep(Duration.ofSeconds(Math.min(retryAfter, config.maxRetryWait().toSeconds())));
            pace(chatId, perChat);
            return action.get();
        }
    }

    /** Выравнивание: очередь вызовов двигается на интервалы даже при стоящих часах.
     * synchronized вместе с паузой — сериализует конкурирующие потоки (polling +
     * планировщик) в общую очередь, иначе глобальный интервал схлопывается (warn ревью P1). */
    private synchronized void pace(Long chatId, boolean perChat) {
        Instant now = clock.instant();
        Instant readyAt = nextGlobalAllowed;
        if (chatId != null && perChat) {
            Instant chatReady = nextPerChatSend.getOrDefault(chatId, Instant.EPOCH);
            if (chatReady.isAfter(readyAt)) {
                readyAt = chatReady;
            }
        }
        Duration wait = Duration.between(now, readyAt);
        if (wait.isNegative()) {
            wait = Duration.ZERO;
        }
        if (!wait.isZero()) {
            sleeper.sleep(wait);
        }
        Instant base = wait.isZero() ? now : readyAt;
        nextGlobalAllowed = base.plus(config.globalInterval());
        if (chatId != null && perChat) {
            nextPerChatSend.put(chatId, base.plus(config.perChatSendInterval()));
        }
    }

    /**
     * @return секунды ожидания из 429-ответа, или -1, если ошибка не про лимиты.
     * Приоритет: ResponseParameters.retry_after → парсинг сообщения → дефолт.
     */
    static long retryAfterSeconds(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e == e.getCause()) {
                break;
            }
            if (e instanceof TelegramApiRequestException api) {
                if (!isTooManyRequests(api)) {
                    return -1;
                }
                return extractRetryAfter(api);
            }
        }
        return -1;
    }

    private static boolean isTooManyRequests(TelegramApiRequestException api) {
        if (Integer.valueOf(429).equals(api.getErrorCode())) {
            return true;
        }
        String message = api.getMessage();
        return message != null && message.contains("Too Many Requests");
    }

    private static long extractRetryAfter(TelegramApiRequestException api) {
        try {
            if (api.getParameters() != null && api.getParameters().getRetryAfter() != null) {
                return api.getParameters().getRetryAfter();
            }
        } catch (RuntimeException ignored) {
            // нет доступа к параметрам — парсим сообщение
        }
        String message = api.getMessage();
        if (message != null) {
            Matcher matcher = RETRY_AFTER_IN_MESSAGE.matcher(message);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
        }
        return DEFAULT_RETRY_WAIT.toSeconds();
    }
}
