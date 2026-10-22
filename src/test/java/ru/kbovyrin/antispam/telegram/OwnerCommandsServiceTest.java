package ru.kbovyrin.antispam.telegram;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.db.Participant;
import ru.kbovyrin.antispam.moderation.UnbanService;

@ExtendWith(MockitoExtension.class)
class OwnerCommandsServiceTest {

    static final long ADMIN = 777L;

    @Mock
    UnbanService unbanService;
    @Mock
    TelegramApi telegramApi;

    OwnerCommandsService service;

    @BeforeEach
    void setUp() {
        var props = new AntispamProperties(true, "tok", "bot", ADMIN, List.of(-100L),
                new AntispamProperties.Captcha(Duration.ofMinutes(5), "q"));
        service = new OwnerCommandsService(props, unbanService, telegramApi);
    }

    private static Participant banned(long chatId, long userId, String username) {
        Participant p = Participant.newPending(chatId, userId, username, java.time.Instant.now());
        p.ban(java.time.Instant.now());
        return p;
    }

    // --- справка ---

    @Test
    void startShowsHelpWithAllCommands() {
        service.handlePrivateMessage(ADMIN, ADMIN, "/start");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("/banlist"));
        verify(telegramApi).sendMessage(eq(ADMIN), contains("/unban"));
    }

    @Test
    void helpShowsSameHelp() {
        service.handlePrivateMessage(ADMIN, ADMIN, "/help");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("/banlist"));
    }

    @Test
    void commandWithBotSuffixWorks() {
        service.handlePrivateMessage(ADMIN, ADMIN, "/start@my_channel_bot");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("/banlist"));
    }

    // --- бан-лист ---

    @Test
    void emptyBanlistIsReported() {
        when(unbanService.bannedParticipants()).thenReturn(List.of());

        service.handlePrivateMessage(ADMIN, ADMIN, "/banlist");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("пуст"));
    }

    @Test
    void banlistShowsIdsUsernamesAndChats() {
        when(unbanService.bannedParticipants()).thenReturn(List.of(
                banned(-100L, 42L, "vasya"),
                banned(-100L, 43L, null)));

        service.handlePrivateMessage(ADMIN, ADMIN, "/banlist");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("@vasya (id=42)"));
        verify(telegramApi).sendMessage(eq(ADMIN), contains("id=43"));
        verify(telegramApi).sendMessage(eq(ADMIN), contains("-100"));
    }

    @Test
    void banlistShowsHumanReadableDate() {
        Participant p = Participant.newPending(-100L, 42L, "vasya", java.time.Instant.parse("2026-10-22T10:15:30Z"));
        p.ban(java.time.Instant.parse("2026-10-22T10:16:00Z"));
        when(unbanService.bannedParticipants()).thenReturn(List.of(p));

        service.handlePrivateMessage(ADMIN, ADMIN, "/banlist");

        // N3: дата для человека, не сырой ISO-8601 с миллис
        verify(telegramApi).sendMessage(eq(ADMIN), contains("22.10.2026 10:16 UTC"));
    }

    @Test
    void longBanlistIsCapped() {
        List<Participant> many = IntStream.rangeClosed(1, 55)
                .mapToLong(i -> i)
                .mapToObj(id -> banned(-100L, id, "user" + id))
                .toList();
        when(unbanService.bannedParticipants()).thenReturn(many);

        service.handlePrivateMessage(ADMIN, ADMIN, "/banlist");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("ещё 5"));
    }

    @Test
    void longBanlistIsSentInChunksUnderTelegramLimit() {
        // W1': 40 записей с 32-символьными username ≈ 4200+ символов — одним
        // сообщением Telegram его не примет (лимит 4096), админ не получил бы ничего
        List<Participant> many = IntStream.rangeClosed(1, 40)
                .mapToObj(i -> banned(-1001234567890L, 1000000000000L + i, "u".repeat(32)))
                .toList();
        when(unbanService.bannedParticipants()).thenReturn(many);

        service.handlePrivateMessage(ADMIN, ADMIN, "/banlist");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(telegramApi, org.mockito.Mockito.atLeast(2))
                .sendMessage(eq(ADMIN), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getAllValues())
                .allSatisfy(text -> org.assertj.core.api.Assertions.assertThat(text.length())
                        .isLessThanOrEqualTo(3500));
        String all = String.join("\n", captor.getAllValues());
        org.assertj.core.api.Assertions.assertThat(all).contains("id=1000000000001)");
        org.assertj.core.api.Assertions.assertThat(all).contains("id=1000000000040)");
    }

    // --- разбан командой ---

    @Test
    void unbanWithUserIdUnbansEverywhere() {
        when(unbanService.unbanEverywhere(42L)).thenReturn(2);

        service.handlePrivateMessage(ADMIN, ADMIN, "/unban 42");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("Разбанен"));
        verify(telegramApi).sendMessage(eq(ADMIN), contains("2 группах"));
    }

    @Test
    void unbanInSingleGroupUsesSingular() {
        when(unbanService.unbanEverywhere(42L)).thenReturn(1);

        service.handlePrivateMessage(ADMIN, ADMIN, "/unban 42");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("1 группе"));
    }

    @Test
    void unbanUnknownUserIsReported() {
        when(unbanService.unbanEverywhere(42L)).thenReturn(0);

        service.handlePrivateMessage(ADMIN, ADMIN, "/unban 42");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("не найден"));
    }

    @Test
    void unbanWithoutArgumentShowsUsage() {
        service.handlePrivateMessage(ADMIN, ADMIN, "/unban");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("Использование"));
        verify(unbanService, never()).unbanEverywhere(anyLong());
    }

    @Test
    void unbanWithGarbageArgumentShowsUsage() {
        service.handlePrivateMessage(ADMIN, ADMIN, "/unban abc");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("Использование"));
        verify(unbanService, never()).unbanEverywhere(anyLong());
    }

    // --- чужие люди и не-команды ---

    @Test
    void nonCommandTextIsIgnored() {
        service.handlePrivateMessage(ADMIN, ADMIN, "привет бот");

        verify(telegramApi, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void messageFromStrangerIsIgnored() {
        service.handlePrivateMessage(999L, 999L, "/banlist");

        verify(telegramApi, never()).sendMessage(anyLong(), anyString());
        verify(unbanService, never()).bannedParticipants();
    }

    @Test
    void unknownCommandShowsHint() {
        service.handlePrivateMessage(ADMIN, ADMIN, "/frobnicate");

        verify(telegramApi).sendMessage(eq(ADMIN), contains("/banlist"));
    }

    // --- кнопка «Разбанить» в уведомлении ---

    @Test
    void unbanCallbackUnbansAndEditsMessage() {
        when(unbanService.unbanEverywhere(42L)).thenReturn(1);

        service.handleUnbanCallback(ADMIN, ADMIN, 55L, "unban:42", "cb1");

        verify(telegramApi).editMessageText(eq(ADMIN), eq(55L), contains("Разбанен"));
        verify(telegramApi).answerCallbackQuery(eq("cb1"), anyString());
    }

    @Test
    void unbanCallbackEditFailureFallsBackToMessageAndStillAnswers() {
        when(unbanService.unbanEverywhere(42L)).thenReturn(2);
        org.mockito.Mockito.doThrow(new RuntimeException("message too old"))
                .when(telegramApi).editMessageText(eq(ADMIN), eq(55L), anyString());

        service.handleUnbanCallback(ADMIN, ADMIN, 55L, "unban:42", "cb1");

        // Разбан уже применён: сбой правки старого (>48 ч) уведомления не должен
        // прятать результат и оставлять владельцу «часики» без ответа
        verify(telegramApi).sendMessage(eq(ADMIN), contains("Разбанен"));
        verify(telegramApi).answerCallbackQuery(eq("cb1"), anyString());
    }

    @Test
    void unbanCallbackWithBadIdReportsError() {
        service.handleUnbanCallback(ADMIN, ADMIN, 55L, "unban:abc", "cb1");

        verify(telegramApi).editMessageText(eq(ADMIN), eq(55L), contains("разобрать"));
        verify(telegramApi).answerCallbackQuery(eq("cb1"), anyString());
        verify(unbanService, never()).unbanEverywhere(anyLong());
    }

    @Test
    void unbanCallbackWithUnknownDataIsRejected() {
        service.handleUnbanCallback(ADMIN, ADMIN, 55L, "other:1", "cb1");

        verify(telegramApi).answerCallbackQuery(eq("cb1"), anyString());
        verify(telegramApi, never()).editMessageText(anyLong(), anyLong(), anyString());
    }

    @Test
    void unbanCallbackFromStrangerIsRejected() {
        service.handleUnbanCallback(999L, 999L, 55L, "unban:42", "cb1");

        verify(telegramApi).answerCallbackQuery(eq("cb1"), contains("не для вас"));
        verify(unbanService, never()).unbanEverywhere(anyLong());
    }
}
