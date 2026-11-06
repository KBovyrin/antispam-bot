package ru.kbovyrin.antispam.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.telegram.TelegramBotAdapter.TelegramRequestException;

/**
 * Адаптер проверяется через мок {@link TelegramClient} (шов-конструктор, P4):
 * маппинг параметров в методы Bot API и оборачивание ошибок — без реальной сети.
 */
class TelegramBotAdapterTest {

    static final long CHAT = -100L;
    static final long USER = 42L;

    static AntispamProperties properties() {
        return new AntispamProperties(true, "token-123", "test_bot", 1L, List.of(CHAT),
                new AntispamProperties.Captcha(Duration.ofMinutes(5), "q"));
    }

    /** Ответчик для мока клиента: умеет бросать TelegramApiException. */
    @FunctionalInterface
    interface Responder {
        Serializable apply(BotApiMethod<?> method) throws TelegramApiException;
    }

    /** Мок TelegramClient: помнит последний метод и отвечает Responder-ом. */
    static final class Stub {
        private BotApiMethod<?> last;
        private final TelegramClient client = mock(TelegramClient.class);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Stub(Responder responder) {
            try {
                doAnswer(invocation -> {
                    last = invocation.getArgument(0);
                    return responder.apply((BotApiMethod<?>) invocation.getArgument(0));
                }).when(client).execute(any(BotApiMethod.class));
            } catch (TelegramApiException e) {
                throw new IllegalStateException("не должно падать на стабе", e);
            }
        }

        BotApiMethod<?> last() {
            return last;
        }

        TelegramBotAdapter adapter() {
            return new TelegramBotAdapter(properties(), client);
        }
    }

    private static InlineKeyboardMarkup keyboard() {
        InlineKeyboardButton button = InlineKeyboardButton.builder()
                .text("Разбанить")
                .callbackData("unban:42")
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(new InlineKeyboardRow(button)))
                .build();
    }

    private static Message messageWithId(int id) {
        return Message.builder().messageId(id).chat(new org.telegram.telegrambots.meta.api.objects.chat.Chat(CHAT, "group")).build();
    }

    @Test
    void sendMessageWithKeyboardReturnsSentMessageId() {
        InlineKeyboardMarkup markup = keyboard();
        var stub = new Stub(m -> messageWithId(77));
        var adapter = stub.adapter();

        long messageId = adapter.sendMessage(CHAT, "привет", markup);

        assertThat(messageId).isEqualTo(77);
        SendMessage sent = (SendMessage) stub.last();
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getText()).isEqualTo("привет");
        assertThat(sent.getReplyMarkup()).isSameAs(markup);
    }

    @Test
    void sendMessageWithoutKeyboardOmitsMarkup() {
        var stub = new Stub(m -> messageWithId(1));
        var adapter = stub.adapter();

        adapter.sendMessage(CHAT, "текст");

        assertThat(((SendMessage) stub.last()).getReplyMarkup()).isNull();
    }

    @Test
    void editMessageTextMapsAllParams() {
        var stub = new Stub(m -> true);
        var adapter = stub.adapter();

        adapter.editMessageText(CHAT, 55L, "новый текст");

        EditMessageText sent = (EditMessageText) stub.last();
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getMessageId()).isEqualTo(55);
        assertThat(sent.getText()).isEqualTo("новый текст");
    }

    @Test
    void deleteMessageMapsAllParams() {
        var stub = new Stub(m -> true);
        var adapter = stub.adapter();

        adapter.deleteMessage(CHAT, 99L);

        DeleteMessage sent = (DeleteMessage) stub.last();
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getMessageId()).isEqualTo(99);
    }

    @Test
    void banChatMemberMapsAllParams() {
        var stub = new Stub(m -> true);
        var adapter = stub.adapter();

        adapter.banChatMember(CHAT, USER);

        BanChatMember sent = (BanChatMember) stub.last();
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getUserId()).isEqualTo(USER);
    }

    @Test
    void unbanChatMemberUsesOnlyIfBanned() {
        // Контракт разбана: only_if_banned=true не выкидывает состоящих в чате
        var stub = new Stub(m -> true);
        var adapter = stub.adapter();

        adapter.unbanChatMember(CHAT, USER);

        UnbanChatMember sent = (UnbanChatMember) stub.last();
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getUserId()).isEqualTo(USER);
        assertThat(sent.getOnlyIfBanned()).isTrue();
    }

    @Test
    void getChatMemberStatusReturnsLibraryStatus() {
        ChatMember member = mock(ChatMember.class);
        when(member.getStatus()).thenReturn("member");
        var stub = new Stub(m -> member);
        var adapter = stub.adapter();

        String status = adapter.getChatMemberStatus(CHAT, USER);

        assertThat(status).isEqualTo("member");
        GetChatMember sent = (GetChatMember) stub.last();
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getUserId()).isEqualTo(USER);
    }

    @Test
    void getChatAdminIdsMapsMembersToUserIds() {
        ChatMember first = mock(ChatMember.class);
        when(first.getUser()).thenReturn(user(777L));
        ChatMember second = mock(ChatMember.class);
        when(second.getUser()).thenReturn(user(888L));
        var stub = new Stub(m -> (java.io.Serializable) List.of(first, second));
        var adapter = stub.adapter();

        List<Long> ids = adapter.getChatAdminIds(CHAT);

        assertThat(ids).containsExactly(777L, 888L);
        assertThat(stub.last()).isInstanceOf(GetChatAdministrators.class);
    }

    @Test
    void answerCallbackQueryMapsIdAndText() {
        var stub = new Stub(m -> true);
        var adapter = stub.adapter();

        adapter.answerCallbackQuery("cb1", "ответ");

        AnswerCallbackQuery sent = (AnswerCallbackQuery) stub.last();
        assertThat(sent.getCallbackQueryId()).isEqualTo("cb1");
        assertThat(sent.getText()).isEqualTo("ответ");
    }

    @Test
    void apiExceptionIsWrappedWithActionContext() {
        var stub = new Stub(m -> {
            throw new TelegramApiException("api down");
        });
        var adapter = stub.adapter();

        assertThatThrownBy(() -> adapter.sendMessage(CHAT, "текст"))
                .isInstanceOf(TelegramRequestException.class)
                .hasMessageContaining("sendMessage");
        assertThatThrownBy(() -> adapter.unbanChatMember(CHAT, USER))
                .isInstanceOf(TelegramRequestException.class)
                .hasMessageContaining("unbanChatMember");
    }

    @Test
    void botUsernameComesFromProperties() {
        var adapter = new Stub(m -> true).adapter();

        assertThat(adapter.getBotUsername()).isEqualTo("test_bot");
    }

    private static User user(long id) {
        return User.builder().id(id).firstName("u" + id).isBot(false).build();
    }
}
