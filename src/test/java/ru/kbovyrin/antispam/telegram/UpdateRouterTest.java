package ru.kbovyrin.antispam.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

import ru.kbovyrin.antispam.captcha.CaptchaService;
import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.moderation.LinkModerationService;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.User;

@ExtendWith(MockitoExtension.class)
class UpdateRouterTest {

    static final long CHAT = -100L;

    @Mock
    CaptchaService captchaService;
    @Mock
    LinkModerationService linkModerationService;
    @Mock
    OwnerCommandsService ownerCommands;
    @Mock
    TelegramApi telegramApi;

    UpdateRouter router;

    @BeforeEach
    void setUp() {
        var properties = new AntispamProperties(true, "tok", "bot", 1L, List.of(CHAT),
                new AntispamProperties.Captcha(java.time.Duration.ofMinutes(5), "q"));
        router = new UpdateRouter(properties, captchaService, linkModerationService, ownerCommands, telegramApi);
    }

    private static User user(long id, String username, boolean bot) {
        return User.builder().id(id).firstName("u" + id).userName(username).isBot(bot).build();
    }

    private static Chat chat(long id) {
        return new Chat(id, "group");
    }

    private static Message message(User from, long chatId, String text) {
        return Message.builder().messageId(5).chat(chat(chatId)).from(from).text(text).build();
    }

    private static Update updateWithMessage(Message message) {
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    @Test
    void messageInWatchedGroupGoesToBothServices() {
        router.route(updateWithMessage(message(user(42L, "vasya", false), CHAT, "привет http://spam.example")));

        verify(captchaService).handleFirstMessage(CHAT, 42L, "vasya", 5);
        verify(linkModerationService).handle(eq(CHAT), eq(42L), eq("vasya"), eq(5L),
                eq("привет http://spam.example"), anyList());
    }

    @Test
    void linkBanCancelsActiveCaptchaAndSkipsNewOne() {
        when(linkModerationService.handle(eq(CHAT), eq(42L), eq("vasya"), eq(5L), any(), anyList()))
                .thenReturn(true);

        router.route(updateWithMessage(message(user(42L, "vasya", false), CHAT, "http://spam.example")));

        verify(captchaService).cancelSession(CHAT, 42L);
        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void linkBanOnEditedMessageCancelsActiveCaptcha() {
        when(linkModerationService.handle(eq(CHAT), eq(42L), eq("vasya"), eq(5L), any(), anyList()))
                .thenReturn(true);
        Update update = new Update();
        update.setEditedMessage(message(user(42L, "vasya", false), CHAT, "правка со ссылкой"));

        router.route(update);

        verify(captchaService).cancelSession(CHAT, 42L);
    }

    @Test
    void messageFromForeignChatIsIgnored() {
        router.route(updateWithMessage(message(user(42L, "vasya", false), -999L, "текст")));

        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void botMessageGetsLinkModerationButNoCaptcha() {
        // W1 (аудит 2026-11): спам-бот, добавленный в группу, больше не невидим —
        // его ссылки модерируются (бан bot-участника работает), но капчу бот не получает
        router.route(updateWithMessage(message(user(99L, "otherbot", true), CHAT, "текст бота http://spam.example")));

        verify(linkModerationService).handle(eq(CHAT), eq(99L), eq("otherbot"), eq(5L),
                eq("текст бота http://spam.example"), anyList());
        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void editedMessageIsCheckedByModerationButNotCaptcha() {
        Update update = new Update();
        update.setEditedMessage(message(user(42L, "vasya", false), CHAT, "правка со ссылкой http://spam.example"));

        router.route(update);

        verify(linkModerationService).handle(eq(CHAT), eq(42L), eq("vasya"), eq(5L),
                eq("правка со ссылкой http://spam.example"), anyList());
        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void captionIsCheckedByModeration() {
        Message media = Message.builder().messageId(7).chat(chat(CHAT))
                .from(user(42L, "vasya", false))
                .caption("https://spam.example дешево")
                .build();

        router.route(updateWithMessage(media));

        verify(linkModerationService).handle(eq(CHAT), eq(42L), eq("vasya"), eq(7L),
                eq("https://spam.example дешево"), anyList());
    }

    @Test
    void anonymousGroupAdminMessageIsIgnored() {
        // Анонимный админ группы пишет от имени самой группы: sender_chat = сам чат.
        // Модерировать его — банить собственную группу; пропускаем (доверенный отправитель)
        Message anonymous = message(user(1087968824L, null, true), CHAT, "сообщение админа со ссылкой http://example.com");
        anonymous.setSenderChat(chat(CHAT));

        router.route(updateWithMessage(anonymous));

        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void automaticForwardFromLinkedChannelIsIgnored() {
        // Автопересылка поста связанного канала: is_automatic_forward=true, from — Telegram
        Message forwarded = message(user(777000L, null, false), CHAT, "Текст поста канала со ссылкой http://example.com");
        forwarded.setSenderChat(chat(-500L));
        forwarded.setIsAutomaticForward(true);

        router.route(updateWithMessage(forwarded));

        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void userCommentingAsOwnChannelIsModerated() {
        // B1 (блокер аудита 2026-11): комментирование «от имени своего канала» —
        // sender_chat = сторонний канал, from = живой автор. Обход щита недопустим:
        // сообщение проходит ссылочную модерацию, автор — капчу
        Message asChannel = message(user(42L, "vasya", false), CHAT, "пост со ссылкой http://spam.example");
        asChannel.setSenderChat(chat(-500L));

        router.route(updateWithMessage(asChannel));

        verify(linkModerationService).handle(eq(CHAT), eq(42L), eq("vasya"), eq(5L),
                eq("пост со ссылкой http://spam.example"), anyList());
        verify(captchaService).handleFirstMessage(CHAT, 42L, "vasya", 5);
    }

    @Test
    void channelLinkBanCancelsCaptchaLikeAnyOther() {
        when(linkModerationService.handle(eq(CHAT), eq(42L), eq("vasya"), eq(5L), any(), anyList()))
                .thenReturn(true);
        Message asChannel = message(user(42L, "vasya", false), CHAT, "http://spam.example");
        asChannel.setSenderChat(chat(-500L));

        router.route(updateWithMessage(asChannel));

        verify(captchaService).cancelSession(CHAT, 42L);
        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void leftChatMemberServiceMessageIsIgnored() {
        // Выход из группы: from — сам вышедший, sender_chat нет; капча ему не нужна,
        // иначе через таймаут он окажется перманентно забанен (blocker B1)
        Message left = message(user(42L, "vasya", false), CHAT, null);
        left.setLeftChatMember(user(42L, "vasya", false));

        router.route(updateWithMessage(left));

        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void newChatMembersServiceMessageIsIgnored() {
        // Сообщение о входе не является «первым сообщением» из README
        Message joined = message(user(42L, "vasya", false), CHAT, null);
        joined.setNewChatMembers(List.of(user(42L, "vasya", false), user(43L, "petya", false)));

        router.route(updateWithMessage(joined));

        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void newChatTitleServiceMessageIsIgnored() {
        Message renamed = message(user(42L, "vasya", false), CHAT, null);
        renamed.setNewChatTitle("Новое название");

        router.route(updateWithMessage(renamed));

        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void pinnedMessageServiceMessageIsIgnored() {
        Message pinned = message(user(42L, "vasya", false), CHAT, null);
        pinned.setPinnedMessage(message(user(42L, "vasya", false), CHAT, "закреплённый текст"));

        router.route(updateWithMessage(pinned));

        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void forumTopicServiceMessageIsIgnored() {
        // Супергруппа с топиками: служебные сообщения о топиках — не контент (N9')
        Message topicCreated = message(user(42L, "vasya", false), CHAT, null);
        topicCreated.setForumTopicCreated(new org.telegram.telegrambots.meta.api.objects.forum.ForumTopicCreated());

        router.route(updateWithMessage(topicCreated));

        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    // --- личка владельца: команды разбана/бан-листа ---

    @Test
    void privateMessageFromOwnerGoesToOwnerCommands() {
        Message command = message(user(1L, "boss", false), 1L, "/banlist");

        router.route(updateWithMessage(command));

        verify(ownerCommands).handlePrivateMessage(1L, 1L, "/banlist");
        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    @Test
    void privateMessageFromStrangerIsIgnored() {
        router.route(updateWithMessage(message(user(999L, "someone", false), 999L, "/banlist")));

        verify(ownerCommands, never()).handlePrivateMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(captchaService, never()).handleFirstMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void editedOwnerCommandIsNotReexecuted() {
        // N7': правка сообщения в личке не должна повторно исполнять команду
        Update update = new Update();
        update.setEditedMessage(message(user(1L, "boss", false), 1L, "/banlist"));

        router.route(update);

        verify(ownerCommands, never()).handlePrivateMessage(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void callbackInOwnerPrivateChatGoesToOwnerCommands() {
        Message notification = message(user(1L, "boss", false), 1L, null);
        CallbackQuery callback = new CallbackQuery();
        callback.setId("cb9");
        callback.setFrom(user(1L, "boss", false));
        callback.setData("unban:42");
        callback.setMessage(notification);
        Update update = new Update();
        update.setCallbackQuery(callback);

        router.route(update);

        verify(ownerCommands).handleUnbanCallback(1L, 1L, 5L, "unban:42", "cb9");
        verify(captchaService, never()).handleCallback(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void callbackInForeignChatIsIgnored() {
        Message foreign = message(user(42L, "vasya", false), -555L, null);
        CallbackQuery callback = new CallbackQuery();
        callback.setId("cb8");
        callback.setFrom(user(42L, "vasya", false));
        callback.setData("captcha:42:1");
        callback.setMessage(foreign);
        Update update = new Update();
        update.setCallbackQuery(callback);

        router.route(update);

        verify(captchaService, never()).handleCallback(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(ownerCommands, never()).handleUnbanCallback(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleCallbackWithoutMessageIsAnswered() {
        // N4': сообщение-носитель могло быть удалено — юзеру не должны висеть «часики»
        CallbackQuery callback = new CallbackQuery();
        callback.setId("cb7");
        callback.setFrom(user(42L, "vasya", false));
        callback.setData("captcha:42:1");
        callback.setMessage(null);
        Update update = new Update();
        update.setCallbackQuery(callback);

        router.route(update);

        verify(telegramApi).answerCallbackQuery(eq("cb7"), any());
        verify(captchaService, never()).handleCallback(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void callbackQueryGoesToCaptchaService() {
        Message captchaMessage = message(user(42L, "vasya", false), CHAT, null);
        CallbackQuery callback = new CallbackQuery();
        callback.setId("cb1");
        callback.setFrom(user(42L, "vasya", false));
        callback.setData("captcha:42:2");
        callback.setMessage(captchaMessage);
        Update update = new Update();
        update.setCallbackQuery(callback);

        router.route(update);

        verify(captchaService).handleCallback(CHAT, 42L, "captcha:42:2", "cb1");
        verify(linkModerationService, never()).handle(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), anyList());
    }
}
