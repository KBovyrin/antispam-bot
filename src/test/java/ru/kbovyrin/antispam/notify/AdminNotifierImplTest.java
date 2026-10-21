package ru.kbovyrin.antispam.notify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import ru.kbovyrin.antispam.config.AntispamProperties;
import ru.kbovyrin.antispam.telegram.TelegramApi;

class AdminNotifierImplTest {

    private final TelegramApi telegramApi = mock(TelegramApi.class);

    private AdminNotifierImpl notifier() {
        return new AdminNotifierImpl(properties(), telegramApi);
    }

    private AntispamProperties properties() {
        return new AntispamProperties(true, "tok", "bot", 777L, java.util.List.of(),
                new AntispamProperties.Captcha(java.time.Duration.ofMinutes(5), "q"));
    }

    @Test
    void violationIsSentToAdminChatWithDetails() {
        notifier().violation(-100L, 42L, "vasya", "ссылка или @упоминание в сообщении");

        verify(telegramApi).sendMessage(eq(777L), contains("id=42"), any());
        verify(telegramApi).sendMessage(eq(777L), contains("vasya"), any());
        verify(telegramApi).sendMessage(eq(777L), contains("ссылка или @упоминание"), any());
    }

    @Test
    void nullUsernameIsShownReadable() {
        notifier().violation(-100L, 42L, null, "причина");

        verify(telegramApi).sendMessage(eq(777L), contains("без username"), any());
    }

    @Test
    void violationCarriesUnbanButton() {
        notifier().violation(-100L, 42L, "vasya", "причина");

        var captor = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(telegramApi).sendMessage(eq(777L), contains("id=42"), captor.capture());
        InlineKeyboardMarkup keyboard = captor.getValue();
        assertThat(keyboard.getKeyboard()).hasSize(1);
        InlineKeyboardButton button = keyboard.getKeyboard().get(0).get(0);
        assertThat(button.getText()).isEqualTo("Разбанить");
        assertThat(button.getCallbackData()).isEqualTo("unban:42");
    }

    @Test
    void sendFailureDoesNotThrow() {
        doThrow(new RuntimeException("api down"))
                .when(telegramApi).sendMessage(eq(777L), contains("id=42"), any());

        // Не должно бросить: уведомление best-effort
        notifier().violation(-100L, 42L, "vasya", "причина");
    }

    @Test
    void notificationsAreSkippedWhenAdminChatIdUnset() {
        // N6: дефолтный ADMIN_CHAT_ID=0 не должен порождать ERROR на каждом бане
        var props = new AntispamProperties(true, "tok", "bot", 0L, java.util.List.of(),
                new AntispamProperties.Captcha(java.time.Duration.ofMinutes(5), "q"));
        var notifier = new AdminNotifierImpl(props, telegramApi);

        notifier.violation(-100L, 42L, "vasya", "причина");
        notifier.banFailed(-100L, 42L, "vasya", "причина");

        verify(telegramApi, org.mockito.Mockito.never()).sendMessage(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), any());
        verify(telegramApi, org.mockito.Mockito.never()).sendMessage(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }
}
