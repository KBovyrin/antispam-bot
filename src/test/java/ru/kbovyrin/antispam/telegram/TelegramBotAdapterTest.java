package ru.kbovyrin.antispam.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.telegram.TelegramBotAdapter.TelegramRequestException;

/**
 * Адаптер проверяется через подкласс с перекрытым execute(): маппинг параметров
 * в методы Bot API и оборачивание ошибок — без реальной сети.
 */
class TelegramBotAdapterTest {

    static final long CHAT = -100L;
    static final long USER = 42L;

    static AntispamProperties properties() {
        return new AntispamProperties(true, "token-123", "test_bot", 1L, List.of(CHAT),
                new AntispamProperties.Captcha(Duration.ofMinutes(5), "q"));
    }

    /** Ответчик для перекрытого execute: умеет бросать TelegramApiException. */
    @FunctionalInterface
    interface Responder {
        Serializable apply(BotApiMethod<?> method) throws TelegramApiException;
    }

    static final class TestableAdapter extends TelegramBotAdapter {

        final Responder handler;
        BotApiMethod<?> last;

        TestableAdapter(Responder handler) {
            super(properties());
            this.handler = handler;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Serializable, Method extends BotApiMethod<T>> T execute(Method method)
                throws TelegramApiException {
            last = method;
            return (T) handler.apply(method);
        }
    }

    private static InlineKeyboardMarkup keyboard() {
        InlineKeyboardButton button = new InlineKeyboardButton("Разбанить");
        button.setCallbackData("unban:42");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(button)));
        return markup;
    }

    private static Message messageWithId(int id) {
        Message message = new Message();
        message.setMessageId(id);
        return message;
    }

    @Test
    void sendMessageWithKeyboardReturnsSentMessageId() {
        InlineKeyboardMarkup markup = keyboard();
        var adapter = new TestableAdapter(m -> messageWithId(77));

        long messageId = adapter.sendMessage(CHAT, "привет", markup);

        assertThat(messageId).isEqualTo(77);
        SendMessage sent = (SendMessage) adapter.last;
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getText()).isEqualTo("привет");
        assertThat(sent.getReplyMarkup()).isSameAs(markup);
    }

    @Test
    void sendMessageWithoutKeyboardOmitsMarkup() {
        var adapter = new TestableAdapter(m -> messageWithId(1));

        adapter.sendMessage(CHAT, "текст");

        assertThat(((SendMessage) adapter.last).getReplyMarkup()).isNull();
    }

    @Test
    void editMessageTextMapsAllParams() {
        var adapter = new TestableAdapter(m -> true);

        adapter.editMessageText(CHAT, 55L, "новый текст");

        EditMessageText sent = (EditMessageText) adapter.last;
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getMessageId()).isEqualTo(55);
        assertThat(sent.getText()).isEqualTo("новый текст");
    }

    @Test
    void deleteMessageMapsAllParams() {
        var adapter = new TestableAdapter(m -> true);

        adapter.deleteMessage(CHAT, 99L);

        DeleteMessage sent = (DeleteMessage) adapter.last;
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getMessageId()).isEqualTo(99);
    }

    @Test
    void banChatMemberMapsAllParams() {
        var adapter = new TestableAdapter(m -> true);

        adapter.banChatMember(CHAT, USER);

        BanChatMember sent = (BanChatMember) adapter.last;
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getUserId()).isEqualTo(USER);
    }

    @Test
    void unbanChatMemberUsesOnlyIfBanned() {
        // Контракт разбана: only_if_banned=true не выкидывает состоящих в чате
        var adapter = new TestableAdapter(m -> true);

        adapter.unbanChatMember(CHAT, USER);

        UnbanChatMember sent = (UnbanChatMember) adapter.last;
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getUserId()).isEqualTo(USER);
        assertThat(sent.getOnlyIfBanned()).isTrue();
    }

    @Test
    void getChatMemberStatusReturnsLibraryStatus() {
        ChatMember member = mock(ChatMember.class);
        when(member.getStatus()).thenReturn("member");
        var adapter = new TestableAdapter(m -> member);

        String status = adapter.getChatMemberStatus(CHAT, USER);

        assertThat(status).isEqualTo("member");
        GetChatMember sent = (GetChatMember) adapter.last;
        assertThat(sent.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(sent.getUserId()).isEqualTo(USER);
    }

    @Test
    void getChatAdminIdsMapsMembersToUserIds() {
        ChatMember first = mock(ChatMember.class);
        when(first.getUser()).thenReturn(user(777L));
        ChatMember second = mock(ChatMember.class);
        when(second.getUser()).thenReturn(user(888L));
        var adapter = new TestableAdapter(m -> (java.io.Serializable) List.of(first, second));

        List<Long> ids = adapter.getChatAdminIds(CHAT);

        assertThat(ids).containsExactly(777L, 888L);
        assertThat(adapter.last).isInstanceOf(GetChatAdministrators.class);
    }

    @Test
    void answerCallbackQueryMapsIdAndText() {
        var adapter = new TestableAdapter(m -> true);

        adapter.answerCallbackQuery("cb1", "ответ");

        AnswerCallbackQuery sent = (AnswerCallbackQuery) adapter.last;
        assertThat(sent.getCallbackQueryId()).isEqualTo("cb1");
        assertThat(sent.getText()).isEqualTo("ответ");
    }

    @Test
    void apiExceptionIsWrappedWithActionContext() {
        var adapter = new TestableAdapter(m -> {
            throw new TelegramApiException("api down");
        });

        assertThatThrownBy(() -> adapter.sendMessage(CHAT, "текст"))
                .isInstanceOf(TelegramRequestException.class)
                .hasMessageContaining("sendMessage");
        assertThatThrownBy(() -> adapter.unbanChatMember(CHAT, USER))
                .isInstanceOf(TelegramRequestException.class)
                .hasMessageContaining("unbanChatMember");
    }

    @Test
    void botCredentialsComeFromProperties() {
        var adapter = new TestableAdapter(m -> true);

        assertThat(adapter.getBotToken()).isEqualTo("token-123");
        assertThat(adapter.getBotUsername()).isEqualTo("test_bot");
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
