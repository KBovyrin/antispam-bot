package ru.kbovyrin.antispam.captcha;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.db.Participant;
import ru.kbovyrin.antispam.db.ParticipantRepository;
import ru.kbovyrin.antispam.moderation.AdminRegistry;
import ru.kbovyrin.antispam.moderation.EnforcementService;
import ru.kbovyrin.antispam.telegram.TelegramApi;

/**
 * Проверка новичков капчей: на первое сообщение выдаёт вопрос с кнопками.
 * Верный ответ — статус PASSED; неверный или таймаут — бан.
 */
@Service
public class CaptchaService {

    public static final String CALLBACK_PREFIX = "captcha:";
    /** Лимит попыток бана одной сессии капчи (защита от шторма уведомлений). */
    static final int MAX_BAN_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    private final AntispamProperties properties;
    private final Clock clock;
    private final ParticipantRepository repository;
    private final TelegramApi telegramApi;
    private final EnforcementService enforcement;
    private final CaptchaQuestions questions;
    private final AdminRegistry adminRegistry;

    /** Активные сессии капчи. Переживать рестарт не обязана: новичку просто зададут вопрос заново. */
    private final Map<SessionKey, CaptchaSession> sessions = new ConcurrentHashMap<>();

    public CaptchaService(AntispamProperties properties, Clock clock, ParticipantRepository repository,
                          TelegramApi telegramApi, EnforcementService enforcement, CaptchaQuestions questions,
                          AdminRegistry adminRegistry) {
        this.properties = properties;
        this.clock = clock;
        this.repository = repository;
        this.telegramApi = telegramApi;
        this.enforcement = enforcement;
        this.questions = questions;
        this.adminRegistry = adminRegistry;
    }

    private record SessionKey(long chatId, long userId) {
    }

    private static final class CaptchaSession {
        private final String username;
        private final Instant expiresAt;
        private final int correctIndex;
        /** Все сообщения пользователя за время капчи: удаляются при бане. */
        private final List<Long> messageIds = new CopyOnWriteArrayList<>();
        /** ID отправленного сообщения капчи; известен только после sendMessage. */
        private long captchaMessageId;
        /** Число неудачных попыток бана (ретраи таймаута). */
        private int banAttempts;

        private CaptchaSession(String username, Instant expiresAt, int correctIndex) {
            this.username = username;
            this.expiresAt = expiresAt;
            this.correctIndex = correctIndex;
        }

        private void trackMessage(long messageId) {
            messageIds.add(messageId);
        }

        private void markIssued(long messageId) {
            this.captchaMessageId = messageId;
        }
    }

    private record ParsedCallback(long userId, int optionIndex) {
    }

    /** @return callback_data для кнопки ответа. */
    public static String callbackData(long userId, int optionIndex) {
        return CALLBACK_PREFIX + userId + ":" + optionIndex;
    }

    /**
     * Первое сообщение пользователя в группе: создаём участника и выдаём капчу.
     * Уже пройденным/забаненным и тем, у кого активная сессия, вопросов не задаём.
     */
    public void handleFirstMessage(long chatId, long userId, String username, long triggerMessageId) {
        if (adminRegistry.isAdmin(chatId, userId)) {
            return;
        }
        SessionKey key = new SessionKey(chatId, userId);
        CaptchaSession active = sessions.get(key);
        if (active != null) {
            // Капча уже выдана — просто запоминаем сообщение, чтобы удалить при бане
            active.trackMessage(triggerMessageId);
            return;
        }
        Optional<Participant> existing = repository.findByChatIdAndUserId(chatId, userId);
        if (existing.isPresent() && existing.get().getStatus() != Participant.Status.PENDING) {
            return;
        }
        repository.save(existing.orElseGet(() -> Participant.newPending(chatId, userId, username, clock.instant())));

        CaptchaQuestion question = questions.next();
        CaptchaSession session = new CaptchaSession(username,
                clock.instant().plus(properties.captcha().timeout()), question.correctOptionIndex());
        session.trackMessage(triggerMessageId);
        // N3: сессия регистрируется ДО отправки — ответ в микросекундном окне между
        // sendMessage и put раньше получал «Время капчи истекло», а потом зомби-сессия банила
        sessions.put(key, session);
        long captchaMessageId;
        try {
            captchaMessageId = telegramApi.sendMessage(chatId, prompt(username, question), keyboard(userId, question));
        } catch (RuntimeException e) {
            sessions.remove(key);
            throw e;
        }
        session.markIssued(captchaMessageId);
        log.info("CAPTCHA_ISSUED chat={} user={} username={} message={}", chatId, userId, username, captchaMessageId);
    }

    /**
     * Ответ на капчу. Чужие кнопки, неактуальные и некапчевые callback-и игнорируются.
     * <p>
     * Осознанный grace (N8'): опоздавший верный ответ принимается, пока сессия жива —
     * реальный таймаут наступает с тиком планировщика (до 30 c после expiresAt).
     */
    public void handleCallback(long chatId, long userId, String data, String callbackQueryId) {
        ParsedCallback parsed = parse(data);
        if (parsed == null || parsed.userId() != userId) {
            telegramApi.answerCallbackQuery(callbackQueryId, "Эта кнопка не для вас");
            return;
        }
        SessionKey key = new SessionKey(chatId, userId);
        CaptchaSession session = sessions.remove(key);
        if (session == null) {
            telegramApi.answerCallbackQuery(callbackQueryId, "Время капчи истекло");
            return;
        }
        if (parsed.optionIndex() == session.correctIndex) {
            Optional<Participant> participant = repository.findByChatIdAndUserId(chatId, userId);
            if (participant.isPresent()) {
                participant.get().pass(clock.instant());
                repository.save(participant.get());
            } else {
                // N4: юзеру рисуется «Принято», но PASSED не сохранён — на следующем
                // сообщении капча будет выдана заново; это надо видеть в логах
                log.warn("CAPTCHA_PASSED_NO_RECORD chat={} user={} username={}: записи в БД нет",
                        chatId, userId, session.username);
            }
            telegramApi.editMessageText(chatId, session.captchaMessageId, "✅ Принято, спасибо!");
            telegramApi.answerCallbackQuery(callbackQueryId, "✅ Принято");
            log.info("CAPTCHA_PASSED chat={} user={} username={}", chatId, userId, session.username);
        } else {
            boolean banned = false;
            try {
                banned = enforcement.ban(chatId, userId, session.username, "провалена капча (неверный ответ)",
                        sessionMessages(chatId, session));
            } catch (RuntimeException e) {
                log.error("BAN_ERROR chat={} user={}: {}", chatId, userId, e.getMessage());
            }
            telegramApi.answerCallbackQuery(callbackQueryId, "❌ Неверно");
            if (!banned) {
                // Бан не прошёл: вернуть сессию — таймаут повторит наказание (W3).
                // putIfAbsent: за окно failing-бана юзер мог написать новое сообщение,
                // и handleFirstMessage уже создал свежую сессию — не затираем её
                sessions.putIfAbsent(key, session);
            }
        }
    }

    /**
     * Банит тех, кто не ответил вовремя. Вызывается планировщиком; в тестах — напрямую.
     * Перед баном проверяет, что юзер ещё в чате: вышедший сам не наказывается
     * (иначе он оказывался бы в чёрном списке навсегда, blocker B1).
     * Неудачные баны повторяются не более {@value #MAX_BAN_ATTEMPTS} раз — иначе
     * стойкий сбой превращается в бесконечный шторм ретраев и уведомлений админу.
     */
    public void expireSessions(Instant now) {
        sessions.forEach((key, session) -> {
            try {
                expireOne(key, session, now);
            } catch (RuntimeException e) {
                // Один сбой не должен ронять обработку остальных сессий тика
                log.error("EXPIRE_SESSION_FAILED chat={} user={}: {}", key.chatId(), key.userId(), e.getMessage());
            }
        });
    }

    private void expireOne(SessionKey key, CaptchaSession session, Instant now) {
        if (session.expiresAt.isAfter(now)) {
            return;
        }
        String status;
        try {
            status = telegramApi.getChatMemberStatus(key.chatId(), key.userId());
        } catch (RuntimeException e) {
            // Не знаем, в чате ли юзер — сессию не снимаем, повтор на следующем тике
            log.error("CHAT_MEMBER_CHECK_FAILED chat={} user={}: {}", key.chatId(), key.userId(), e.getMessage());
            return;
        }
        if ("left".equals(status)) {
            if (!sessions.remove(key, session)) {
                return;
            }
            // Вышел сам: бана не было, приберём сообщение капчи; запись в БД остаётся
            // PENDING и самоисцелится — при возвращении капча будет выдана заново
            cleanupCaptchaMessage(key.chatId(), session);
            log.info("CAPTCHA_SKIPPED_LEFT chat={} user={} username={}", key.chatId(), key.userId(), session.username);
            return;
        }
        if ("kicked".equals(status) || "administrator".equals(status) || "creator".equals(status)) {
            // N6': kicked — уже в чёрном списке (повторный бан бессмыслен и дублирует
            // уведомление); administrator/creator — стал админом после выдачи капчи
            if (!sessions.remove(key, session)) {
                return;
            }
            cleanupCaptchaMessage(key.chatId(), session);
            log.info("CAPTCHA_SKIPPED_STATUS chat={} user={} username={} status={}",
                    key.chatId(), key.userId(), session.username, status);
            return;
        }
        // W3': атомарно забираем сессию ДО наказания. Между проверкой статуса и баном
        // секунды: если юзер за это окно ответил верно (handleCallback уже снял
        // сессию и записал PASSED) — бан отменяется, иначе PASSED-юзер получал
        // перманентный бан с перезаписью статуса в БД
        if (!sessions.remove(key, session)) {
            log.info("CAPTCHA_RESOLVED_DURING_EXPIRE chat={} user={} username={}",
                    key.chatId(), key.userId(), session.username);
            return;
        }
        boolean banned = false;
        try {
            banned = enforcement.ban(key.chatId(), key.userId(), session.username,
                    "провалена капча (истекло время)", sessionMessages(key.chatId(), session));
        } catch (RuntimeException e) {
            log.error("BAN_ERROR chat={} user={}: {}", key.chatId(), key.userId(), e.getMessage());
        }
        if (banned) {
            // Сессия уже снята выше
            return;
        }
        if (session.banAttempts >= MAX_BAN_ATTEMPTS - 1) {
            // Попытки исчерпаны: сессия уже снята, последний banFailed ушёл админу
            log.error("BAN_GAVE_UP chat={} user={} username={}: {} попыток",
                    key.chatId(), key.userId(), session.username, MAX_BAN_ATTEMPTS);
            return;
        }
        session.banAttempts++;
        // Вернуть сессию для ретрая на следующем тике; putIfAbsent — если её
        // параллельно успел забрать/пересоздать callback, не затираем чужую
        sessions.putIfAbsent(key, session);
    }

    private void cleanupCaptchaMessage(long chatId, CaptchaSession session) {
        try {
            telegramApi.deleteMessage(chatId, session.captchaMessageId);
        } catch (RuntimeException e) {
            // Штатный случай: сообщение могло быть уже удалено — не ERROR (N5')
            log.warn("DELETE_FAILED chat={} message={}: {}", chatId, session.captchaMessageId, e.getMessage());
        }
    }

    /**
     * Гасит активную капчу пользователя без бана (юзер уже наказан другим путём —
     * например, забанен за ссылку). Удаляет его отслеженные сообщения и сообщение
     * капчи, чтобы кнопки не висели в чате до таймаута. Повторный вызов безвреден.
     */
    public void cancelSession(long chatId, long userId) {
        CaptchaSession session = sessions.remove(new SessionKey(chatId, userId));
        if (session == null) {
            return;
        }
        for (EnforcementService.MessageRef ref : sessionMessages(chatId, session)) {
            try {
                telegramApi.deleteMessage(ref.chatId(), ref.messageId());
            } catch (RuntimeException e) {
                // Штатный случай: сообщение могло быть уже удалено при бане — не ERROR (N5')
                log.warn("DELETE_FAILED chat={} message={}: {}", ref.chatId(), ref.messageId(), e.getMessage());
            }
        }
        log.info("CAPTCHA_CANCELLED chat={} user={} username={}", chatId, userId, session.username);
    }

    private List<EnforcementService.MessageRef> sessionMessages(long chatId, CaptchaSession session) {
        List<EnforcementService.MessageRef> refs = new ArrayList<>();
        for (Long messageId : session.messageIds) {
            refs.add(new EnforcementService.MessageRef(chatId, messageId));
        }
        refs.add(new EnforcementService.MessageRef(chatId, session.captchaMessageId));
        return refs;
    }

    private static ParsedCallback parse(String data) {
        if (data == null || !data.startsWith(CALLBACK_PREFIX)) {
            return null;
        }
        String[] parts = data.substring(CALLBACK_PREFIX.length()).split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new ParsedCallback(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String prompt(String username, CaptchaQuestion question) {
        String name = username == null || username.isBlank() ? "Привет" : username;
        return properties.captcha().prompt().replace("{name}", name).replace("{question}", question.text());
    }

    private InlineKeyboardMarkup keyboard(long userId, CaptchaQuestion question) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 0; i < question.options().size(); i++) {
            InlineKeyboardButton button = new InlineKeyboardButton(question.options().get(i));
            button.setCallbackData(callbackData(userId, i));
            row.add(button);
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(row));
        return markup;
    }
}
