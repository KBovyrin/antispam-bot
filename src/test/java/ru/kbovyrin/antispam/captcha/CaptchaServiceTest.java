package ru.kbovyrin.antispam.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.db.Participant;
import ru.kbovyrin.antispam.db.ParticipantRepository;
import ru.kbovyrin.antispam.moderation.EnforcementService;
import ru.kbovyrin.antispam.notify.AdminNotifier;
import ru.kbovyrin.antispam.telegram.TelegramApi;

@ExtendWith(MockitoExtension.class)
class CaptchaServiceTest {

    static final long CHAT = -100L;
    static final long USER = 42L;
    static final long TRIGGER_MSG = 123L;
    static final long CAPTCHA_MSG = 555L;
    static final String USERNAME = "vasya";

    final Instant now = Instant.parse("2026-10-20T12:00:00Z");

    @Mock
    ParticipantRepository repository;
    @Mock
    TelegramApi telegramApi;
    @Mock
    AdminNotifier adminNotifier;
    @Mock
    ru.kbovyrin.antispam.moderation.AdminRegistry adminRegistry;

    CaptchaQuestions questions;
    CaptchaService service;
    MutableClock clock;

    @BeforeEach
    void setUp() {
        questions = mock(CaptchaQuestions.class);

        var props = new AntispamProperties(true, "tok", "bot", 777L, List.of(),
                new AntispamProperties.Captcha(Duration.ofMinutes(5), "{name}, вопрос: {question}"));

        clock = new MutableClock(now);
        service = serviceWithScoring(Duration.ZERO, 10);
    }

    /** Сервис с конкретными порогами скоринга (P3): по умолчанию в тестах выключены. */
    CaptchaService serviceWithScoring(Duration minAnswerTime, int floodBanThreshold) {
        return new CaptchaService(
                new AntispamProperties(true, "tok", "bot", 777L, List.of(),
                        new AntispamProperties.Captcha(Duration.ofMinutes(5), "{name}, вопрос: {question}")),
                new ru.kbovyrin.antispam.config.CaptchaScoringProperties(minAnswerTime, floodBanThreshold),
                clock, repository, telegramApi,
                new EnforcementService(repository, telegramApi, adminNotifier, clock),
                questions, adminRegistry);
    }

    void stubQuestion() {
        when(questions.next()).thenReturn(new CaptchaQuestion("7 + 3", 2, List.of("8", "9", "10", "11")));
    }

    void stubSave() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    void stubCaptchaMessageId() {
        when(telegramApi.sendMessage(eq(CHAT), contains("7 + 3"), any())).thenReturn(CAPTCHA_MSG);
    }

    /** Каждое обращение к репозиторию возвращает свежего PENDING-участника. */
    void stubExistingPendingParticipant() {
        when(repository.findByChatIdAndUserId(CHAT, USER))
                .thenAnswer(inv -> Optional.of(Participant.newPending(CHAT, USER, USERNAME, now)));
    }

    private void callback(String data) {
        service.handleCallback(CHAT, USER, data, "cb1");
    }

    /** Подвижные часы: тесты скоринга двигают время сами. */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    // --- P3: скоринг — мгновенный верный ответ и шторм сообщений ---

    @Test
    void instantCorrectAnswerIsTreatedAsBot() {
        // Юзербот решает капчу за миллисекунды — верный ответ раньше порога = бан
        service = serviceWithScoring(Duration.ofSeconds(1), 10);
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        stubExistingPendingParticipant();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);

        callback(CaptchaService.callbackData(USER, 2));

        verify(telegramApi).banChatMember(CHAT, USER);
        verify(telegramApi, never()).editMessageText(anyLong(), anyLong(), contains("Принято"));
        verify(telegramApi).answerCallbackQuery(eq("cb1"), contains("Неверно"));
        verify(adminNotifier).violation(eq(CHAT), eq(USER), eq(USERNAME), contains("мгновенный"));
    }

    @Test
    void delayedCorrectAnswerPasses() {
        // Человек с мышкой отвечает за пару секунд — порог его не задевает
        service = serviceWithScoring(Duration.ofSeconds(1), 10);
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        stubExistingPendingParticipant();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        clock.advance(Duration.ofSeconds(2));

        callback(CaptchaService.callbackData(USER, 2));

        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(telegramApi).editMessageText(eq(CHAT), eq(CAPTCHA_MSG), contains("Принято"));
    }

    @Test
    void messageFloodBeforeAnswerBansImmediately() {
        // Шторм сообщений в окне капчи — бан не дожидаясь таймаута
        service = serviceWithScoring(Duration.ZERO, 3);
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, 100L);
        service.handleFirstMessage(CHAT, USER, USERNAME, 101L);

        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());

        service.handleFirstMessage(CHAT, USER, USERNAME, 102L);

        verify(telegramApi).banChatMember(CHAT, USER);
        verify(telegramApi).deleteMessage(CHAT, CAPTCHA_MSG);
        verify(telegramApi).deleteMessage(CHAT, 102L);
        verify(adminNotifier).violation(eq(CHAT), eq(USER), eq(USERNAME), contains("шторм"));

        // Сессия снята: таймаут не баниет повторно
        service.expireSessions(now.plus(Duration.ofMinutes(6)));
        verify(telegramApi, times(1)).banChatMember(anyLong(), anyLong());
    }

    @Test
    void floodBanWorksBeyondDeletionCap() {
        // warn ревью P3: порог шторма выше капа списка удаления (30) обязан
        // работать — счётчик шторма не упирается в кап
        service = serviceWithScoring(Duration.ZERO, 35);
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        for (long id = 1; id <= 34; id++) {
            service.handleFirstMessage(CHAT, USER, USERNAME, id);
        }
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());

        service.handleFirstMessage(CHAT, USER, USERNAME, 35L);

        verify(telegramApi).banChatMember(CHAT, USER);
    }

    // --- первое сообщение ---

    @Test
    void adminIsNotCaptchaed() {
        when(adminRegistry.isAdmin(CHAT, 777L)).thenReturn(true);

        service.handleFirstMessage(CHAT, 777L, "boss", TRIGGER_MSG);

        verify(telegramApi, never()).sendMessage(eq(CHAT), anyString(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void firstMessageCreatesPendingParticipantAndSendsCaptcha() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();

        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);

        verify(telegramApi).sendMessage(eq(CHAT), contains("vasya, вопрос: 7 + 3"), any());
        verify(repository).save(any(Participant.class));
    }

    @Test
    void secondMessageFromPendingUserDoesNotReissueCaptcha() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();

        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        service.handleFirstMessage(CHAT, USER, USERNAME, 124L);

        verify(telegramApi, times(1)).sendMessage(eq(CHAT), anyString(), any());
    }

    @Test
    void pendingUserWithChangedUsernameGetsTargetedUpdate() {
        stubQuestion();
        stubCaptchaMessageId();
        when(repository.findByChatIdAndUserId(CHAT, USER))
                .thenAnswer(inv -> Optional.of(Participant.newPending(CHAT, USER, "old_name", now)));

        service.handleFirstMessage(CHAT, USER, "new_name", TRIGGER_MSG);

        // W2 (гонка PENDING↔BANNED): обновление username обязано быть точечным UPDATE —
        // save всей сущности мог затереть параллельно записанный BANNED, и /unban
        // перестал бы видеть забаненного
        verify(repository).updateUsername(CHAT, USER, "new_name", now);
        verify(repository, never()).save(any());
    }

    @Test
    void alreadyPassedUserIsNotCheckedAgain() {
        Participant passed = Participant.newPending(CHAT, USER, USERNAME, now);
        passed.pass(now);
        when(repository.findByChatIdAndUserId(CHAT, USER)).thenReturn(Optional.of(passed));

        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);

        verify(telegramApi, never()).sendMessage(eq(CHAT), anyString(), any());
    }

    // --- верный ответ ---

    @Test
    void correctAnswerPassesUser() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        stubExistingPendingParticipant();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);

        callback(CaptchaService.callbackData(USER, 2));

        // Единственный save — фиксация PASSED: выдача капчи существующей
        // неизменённой записи больше не пишется в БД (N1)
        var captor = org.mockito.ArgumentCaptor.forClass(Participant.class);
        verify(repository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Participant.Status.PASSED);

        verify(telegramApi).editMessageText(eq(CHAT), eq(CAPTCHA_MSG), contains("Принято"));
        verify(telegramApi).answerCallbackQuery(eq("cb1"), contains("Принято"));
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(telegramApi, never()).deleteMessage(anyLong(), anyLong());
    }

    // --- неверный ответ ---

    @Test
    void wrongAnswerDeletesMessagesBansUserAndNotifiesAdmin() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        stubExistingPendingParticipant();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);

        callback(CaptchaService.callbackData(USER, 0));

        verify(telegramApi).deleteMessage(CHAT, TRIGGER_MSG);
        verify(telegramApi).deleteMessage(CHAT, CAPTCHA_MSG);
        verify(telegramApi).banChatMember(CHAT, USER);
        verify(telegramApi).answerCallbackQuery(eq("cb1"), contains("Неверно"));
        verify(adminNotifier).violation(eq(CHAT), eq(USER), eq(USERNAME), contains("капча"));
        var captor = org.mockito.ArgumentCaptor.forClass(Participant.class);
        verify(repository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Participant.Status.BANNED);
        verify(telegramApi, never()).editMessageText(anyLong(), anyLong(), anyString());
    }

    // --- таймаут ---

    @Test
    void messagesSentWhilePendingAreDeletedOnWrongAnswer() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, 123L);
        // Пользователь ещё пишет, пока капча активна
        service.handleFirstMessage(CHAT, USER, USERNAME, 124L);

        callback(CaptchaService.callbackData(USER, 0));

        verify(telegramApi).deleteMessage(CHAT, 123L);
        verify(telegramApi).deleteMessage(CHAT, 124L);
        verify(telegramApi).deleteMessage(CHAT, CAPTCHA_MSG);
    }

    @Test
    void timeoutBansUserAndNotifiesAdmin() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("member");
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);

        service.expireSessions(now.plus(Duration.ofMinutes(6)));

        verify(telegramApi).deleteMessage(CHAT, TRIGGER_MSG);
        verify(telegramApi).deleteMessage(CHAT, CAPTCHA_MSG);
        verify(telegramApi).banChatMember(CHAT, USER);
        verify(adminNotifier).violation(eq(CHAT), eq(USER), eq(USERNAME), contains("капча"));
    }

    @Test
    void timeoutSkipsBanWhenUserAlreadyLeftGroup() {
        // Защита в глубину (B1): вышедший сам не должен баниться по таймауту капчи
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("left");

        service.expireSessions(now.plus(Duration.ofMinutes(6)));

        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(adminNotifier, never()).violation(anyLong(), anyLong(), anyString(), anyString());
        verify(telegramApi).deleteMessage(CHAT, CAPTCHA_MSG);
        // Сессия снята: повторный тик планировщика не банит
        service.expireSessions(now.plus(Duration.ofMinutes(7)));
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
    }

    @Test
    void timeoutSkipsBanWhenUserAlreadyKicked() {
        // N6': юзер уже в чёрном списке (забанен вручную) — повторный бан бессмыслен,
        // а уведомление админу дублировало бы уже известное
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("kicked");

        service.expireSessions(now.plus(Duration.ofMinutes(6)));

        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(adminNotifier, never()).violation(anyLong(), anyLong(), anyString(), anyString());
        verify(adminNotifier, never()).banFailed(anyLong(), anyLong(), anyString(), anyString());
        verify(telegramApi).deleteMessage(CHAT, CAPTCHA_MSG);
    }

    @Test
    void timeoutSkipsBanWhenUserBecameAdmin() {
        // N6': повысили в админы после выдачи капчи — не наказываем
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("administrator");

        service.expireSessions(now.plus(Duration.ofMinutes(6)));

        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(adminNotifier, never()).banFailed(anyLong(), anyLong(), anyString(), anyString());
        verify(telegramApi).deleteMessage(CHAT, CAPTCHA_MSG);

        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("creator");
        service.handleFirstMessage(CHAT, USER, USERNAME, 124L);
        // сессия снята — повторной капчи нет, но и бана нет
        service.expireSessions(now.plus(Duration.ofMinutes(7)));
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
    }

    @Test
    void timeoutRetriesBanWhenPresenceCheckFails() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(telegramApi.getChatMemberStatus(CHAT, USER))
                .thenThrow(new RuntimeException("api down"))
                .thenReturn("member");

        service.expireSessions(now.plus(Duration.ofMinutes(6)));
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());

        // Сессия сохранена — на следующем тике бан проходит
        service.expireSessions(now.plus(Duration.ofMinutes(7)));
        verify(telegramApi).banChatMember(CHAT, USER);
    }

    @Test
    void statusCheckFailuresAreLimited() {
        // W3 (аудит 2026-11): стойкий сбой getChatMember не должен вечно держать
        // сессию и штормить API каждый тик — лимит попыток на сессию
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenThrow(new RuntimeException("api down"));

        service.expireSessions(now.plus(Duration.ofMinutes(6)));
        service.expireSessions(now.plus(Duration.ofMinutes(7)));
        service.expireSessions(now.plus(Duration.ofMinutes(8)));
        service.expireSessions(now.plus(Duration.ofMinutes(9)));

        verify(telegramApi, times(3)).getChatMemberStatus(CHAT, USER);
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(adminNotifier, never()).banFailed(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void statusCheckBudgetDoesNotEatBanRetries() {
        // warn ревью диффа: сбои проверки статуса и сбои бана считают РАЗНЫЕ бюджеты —
        // три попытки бана обязаны остаться тремя, даже если до этого дважды
        // падал getChatMember
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(telegramApi.getChatMemberStatus(CHAT, USER))
                .thenThrow(new RuntimeException("api down"))
                .thenThrow(new RuntimeException("api down"))
                .thenReturn("member");
        org.mockito.Mockito.doThrow(new RuntimeException("api down")).doThrow(new RuntimeException("x"))
                .doThrow(new RuntimeException("y"))
                .when(telegramApi).banChatMember(CHAT, USER);

        for (int tick = 6; tick <= 10; tick++) {
            service.expireSessions(now.plus(Duration.ofMinutes(tick)));
        }

        verify(telegramApi, times(3)).banChatMember(CHAT, USER);
        verify(adminNotifier, times(3)).banFailed(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void timeoutWithFailedBanRetriesOnNextTick() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("member");
        org.mockito.Mockito.doThrow(new RuntimeException("api down")).doNothing()
                .when(telegramApi).banChatMember(CHAT, USER);

        service.expireSessions(now.plus(Duration.ofMinutes(6)));

        verify(adminNotifier).banFailed(anyLong(), anyLong(), anyString(), contains("капча"));
        verify(telegramApi, times(1)).banChatMember(CHAT, USER);

        // Сессия не снята — следующий тик повторяет бан успешно (W3)
        service.expireSessions(now.plus(Duration.ofMinutes(7)));
        verify(telegramApi, times(2)).banChatMember(CHAT, USER);
        verify(adminNotifier).violation(anyLong(), anyLong(), anyString(), contains("капча"));
    }

    @Test
    void banRetriesAreLimited() {
        // Шторм уведомлений (ревью W3'): не более 3 попыток на сессию
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("member");
        org.mockito.Mockito.doThrow(new RuntimeException("api down")).doThrow(new RuntimeException("x"))
                .doThrow(new RuntimeException("y")).doNothing()
                .when(telegramApi).banChatMember(CHAT, USER);

        service.expireSessions(now.plus(Duration.ofMinutes(6)));
        service.expireSessions(now.plus(Duration.ofMinutes(7)));
        service.expireSessions(now.plus(Duration.ofMinutes(8)));
        service.expireSessions(now.plus(Duration.ofMinutes(9)));

        verify(telegramApi, times(3)).banChatMember(CHAT, USER);
        verify(adminNotifier, times(3)).banFailed(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void correctAnswerDuringExpireCancelsBan() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        stubExistingPendingParticipant();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        // Гонка W3': юзер нажимает верную кнопку, пока expireOne между проверкой
        // статуса и баном (два сетевых вызова — окно в секунды)
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenAnswer(inv -> {
            service.handleCallback(CHAT, USER, CaptchaService.callbackData(USER, 2), "cb-race");
            return "member";
        });

        service.expireSessions(now.plus(Duration.ofMinutes(6)));

        // Ответ успел: PASSED зафиксирован, бан не выносится
        verify(telegramApi).editMessageText(eq(CHAT), eq(CAPTCHA_MSG), contains("Принято"));
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(adminNotifier, never()).violation(anyLong(), anyLong(), anyString(), anyString());
        verify(adminNotifier, never()).banFailed(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void dbFailureInExpireIsContainedAndNotifies() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("member");
        when(repository.markBanned(eq(CHAT), eq(USER), any())).thenThrow(new RuntimeException("db busy"));

        // Сбой БД после успешного бана не должен ронять весь тик планировщика
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                service.expireSessions(now.plus(Duration.ofMinutes(6))));

        verify(telegramApi).banChatMember(CHAT, USER);
        verify(adminNotifier).violation(anyLong(), anyLong(), anyString(), contains("капча"));
    }

    @Test
    void wrongAnswerWithDbFailureStillAnswersAndNotifies() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        when(repository.markBanned(eq(CHAT), eq(USER), any())).thenThrow(new RuntimeException("db busy"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                callback(CaptchaService.callbackData(USER, 0)));

        verify(telegramApi).answerCallbackQuery(eq("cb1"), contains("Неверно"));
        verify(adminNotifier).violation(anyLong(), anyLong(), anyString(), contains("капча"));
        verify(adminNotifier, never()).banFailed(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void wrongAnswerWithFailedBanKeepsSessionForRetry() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        stubExistingPendingParticipant();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        org.mockito.Mockito.doThrow(new RuntimeException("api down")).doNothing()
                .when(telegramApi).banChatMember(CHAT, USER);
        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("member");

        callback(CaptchaService.callbackData(USER, 0));

        verify(adminNotifier).banFailed(anyLong(), anyLong(), anyString(), contains("капча"));
        verify(telegramApi).answerCallbackQuery(eq("cb1"), contains("Неверно"));

        // Сессия возвращена: таймаут повторяет бан
        service.expireSessions(now.plus(Duration.ofMinutes(6)));
        verify(telegramApi, times(2)).banChatMember(CHAT, USER);
        verify(adminNotifier).violation(anyLong(), anyLong(), anyString(), contains("капча"));
    }

    @Test
    void correctAnswerAfterFailedWrongBanDoesNotPass() {
        stubQuestion();
        stubCaptchaMessageId();
        stubExistingPendingParticipant();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        // Бан не проходит (бот потерял права): неверный ответ не должен
        // открывать перебор — «верная» кнопка после провала не спасает
        org.mockito.Mockito.doThrow(new RuntimeException("api down"))
                .when(telegramApi).banChatMember(CHAT, USER);

        callback(CaptchaService.callbackData(USER, 0));
        callback(CaptchaService.callbackData(USER, 2));

        verify(telegramApi, never()).editMessageText(eq(CHAT), eq(CAPTCHA_MSG), contains("Принято"));
        verify(telegramApi, times(2)).answerCallbackQuery(eq("cb1"), contains("Неверно"));
        verify(telegramApi, times(2)).banChatMember(CHAT, USER);
    }

    @Test
    void wrongAnswerRetriesAreLimitedWhenBanKeepsFailing() {
        stubQuestion();
        stubCaptchaMessageId();
        stubExistingPendingParticipant();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        org.mockito.Mockito.doThrow(new RuntimeException("api down"))
                .when(telegramApi).banChatMember(CHAT, USER);

        callback(CaptchaService.callbackData(USER, 0));
        callback(CaptchaService.callbackData(USER, 0));
        callback(CaptchaService.callbackData(USER, 0));
        // Попытки исчерпаны: сессия снята, дальнейшие нажатия — «истекло»
        callback(CaptchaService.callbackData(USER, 0));

        verify(telegramApi, times(3)).banChatMember(CHAT, USER);
        verify(telegramApi).answerCallbackQuery(eq("cb1"), contains("истекло"));
    }

    @Test
    void answerFailureAfterFailedBanKeepsSessionForTimeoutRetry() {
        stubQuestion();
        stubCaptchaMessageId();
        stubExistingPendingParticipant();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        org.mockito.Mockito.doThrow(new RuntimeException("api down"))
                .when(telegramApi).banChatMember(CHAT, USER);
        org.mockito.Mockito.doThrow(new RuntimeException("answer failed"))
                .when(telegramApi).answerCallbackQuery("cb1", "❌ Неверно");

        // Сбой ответа на callback не должен ронять ретрай бана (W2)
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> callback(CaptchaService.callbackData(USER, 0)));

        when(telegramApi.getChatMemberStatus(CHAT, USER)).thenReturn("member");
        service.expireSessions(now.plus(Duration.ofMinutes(6)));
        verify(telegramApi, times(2)).banChatMember(CHAT, USER);
    }

    @Test
    void noBanBeforeTimeout() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);

        service.expireSessions(now.plus(Duration.ofMinutes(4)));

        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(adminNotifier, never()).violation(anyLong(), anyLong(), anyString(), anyString());
    }

    // --- чужие и неактуальные ответы игнорируются ---

    @Test
    void callbackFromAnotherUserIsIgnored() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);

        service.handleCallback(CHAT, 99L, CaptchaService.callbackData(USER, 2), "cb1");

        verify(telegramApi).answerCallbackQuery(eq("cb1"), anyString());
        verify(telegramApi, never()).editMessageText(anyLong(), anyLong(), anyString());
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
    }

    @Test
    void callbackWithoutSessionIsIgnored() {
        callback(CaptchaService.callbackData(USER, 2));

        verify(telegramApi).answerCallbackQuery(eq("cb1"), anyString());
        verify(telegramApi, never()).editMessageText(anyLong(), anyLong(), anyString());
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(repository, never()).save(any());
    }

    @Test
    void nonCaptchaCallbackIsIgnored() {
        callback("other:1");

        verify(telegramApi, never()).editMessageText(anyLong(), anyLong(), anyString());
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
    }

    @Test
    void unparsableCallbackDataGetsNoApiAnswer() {
        // Шторм сфабрикованных callback-запросов (мусорный data от юзербота) не должен
        // расходовать лимит answerCallbackQuery бота — тихо игнорируем (hardening 2026-11)
        callback("captcha:abc:1");
        callback("captcha:42");
        callback("captcha:42:x:y");
        callback("unban:123");
        service.handleCallback(CHAT, USER, null, "cb1");

        verify(telegramApi, never()).answerCallbackQuery(anyString(), anyString());
        verify(telegramApi, never()).editMessageText(anyLong(), anyLong(), anyString());
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
    }

    // --- идемпотентность ---

    @Test
    void repeatedCallbackAfterPassIsIgnored() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);

        callback(CaptchaService.callbackData(USER, 2));
        callback(CaptchaService.callbackData(USER, 2));

        verify(telegramApi, times(1)).editMessageText(anyLong(), anyLong(), anyString());
    }

    // --- отмена капчи (бан по ссылке, W1) ---

    @Test
    void cancelSessionRemovesMessagesAndPreventsTimeoutBan() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        service.handleFirstMessage(CHAT, USER, USERNAME, 124L);

        service.cancelSession(CHAT, USER);

        verify(telegramApi).deleteMessage(CHAT, TRIGGER_MSG);
        verify(telegramApi).deleteMessage(CHAT, 124L);
        verify(telegramApi).deleteMessage(CHAT, CAPTCHA_MSG);
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(adminNotifier, never()).violation(anyLong(), anyLong(), anyString(), anyString());

        // Сессии больше нет: таймаут не банит повторно
        service.expireSessions(now.plus(Duration.ofMinutes(6)));
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
    }

    @Test
    void cancelSessionWithoutActiveSessionIsNoop() {
        service.cancelSession(CHAT, USER);

        verify(telegramApi, never()).deleteMessage(anyLong(), anyLong());
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
    }

    @Test
    void messageFloodDuringCaptchaDeletesOnlyRecentMessages() {
        // nit ревью (N1): шторм сообщений в окне капчи не должен превращаться
        // в сотню deleteMessage — удаляем только последние MAX_TRACKED_MESSAGES.
        // Порог скоринга поднят выше 35: здесь проверяется КАП, а не шторм-бан
        service = serviceWithScoring(Duration.ZERO, 100);
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        service.handleFirstMessage(CHAT, USER, USERNAME, 1000L);
        for (long id = 1001L; id <= 1034L; id++) {
            service.handleFirstMessage(CHAT, USER, USERNAME, id);
        }

        callback(CaptchaService.callbackData(USER, 0));

        // Первые сообщения шторма (1000..1004) не удаляются — кап на 30
        verify(telegramApi, never()).deleteMessage(CHAT, 1000L);
        verify(telegramApi, never()).deleteMessage(CHAT, 1004L);
        verify(telegramApi).deleteMessage(CHAT, 1005L);
        verify(telegramApi).deleteMessage(CHAT, 1034L);
        verify(telegramApi).deleteMessage(CHAT, CAPTCHA_MSG);
        verify(telegramApi).banChatMember(CHAT, USER);
    }

    // --- устойчивость путей выдачи и ответа ---

    @Test
    void captchaSendFailureLeavesNoSessionForTimeoutBan() {
        stubQuestion();
        stubSave();
        when(telegramApi.sendMessage(eq(CHAT), contains("7 + 3"), any()))
                .thenThrow(new RuntimeException("api down"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG));

        // N3: сбой отправки не оставляет сессию — таймаут не забанит молча
        service.expireSessions(now.plus(Duration.ofMinutes(6)));
        verify(telegramApi, never()).banChatMember(anyLong(), anyLong());
        verify(adminNotifier, never()).violation(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void correctAnswerWithoutDbRecordCreatesPassedParticipant() {
        stubQuestion();
        stubSave();
        stubCaptchaMessageId();
        when(repository.findByChatIdAndUserId(CHAT, USER)).thenReturn(Optional.empty());

        service.handleFirstMessage(CHAT, USER, USERNAME, TRIGGER_MSG);
        callback(CaptchaService.callbackData(USER, 2));

        // N2: юзеру показали «Принято» — статус обязан сохраниться, иначе
        // следующее сообщение внезапно выдаст капчу повторно
        verify(telegramApi).editMessageText(eq(CHAT), eq(CAPTCHA_MSG), contains("Принято"));
        var captor = org.mockito.ArgumentCaptor.forClass(Participant.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(Participant.Status.PASSED);
    }
}
