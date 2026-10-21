package ru.kbovyrin.antispam.spam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

class LinkDetectorTest {

    private final LinkDetector detector = new LinkDetector();

    // --- чистый текст без нарушений ---

    @Test
    void plainTextIsAllowed() {
        assertThat(detector.isViolation("Привет, как дела?", List.of())).isFalse();
    }

    @Test
    void nullTextIsAllowed() {
        assertThat(detector.isViolation(null, List.of())).isFalse();
    }

    // --- regex-fallback: ссылки в тексте без entities ---

    @Test
    void httpLinkIsViolation() {
        assertThat(detector.isViolation("смотрите http://example.com/spam тут", List.of())).isTrue();
    }

    @Test
    void httpsLinkIsViolation() {
        assertThat(detector.isViolation("https://example.org", List.of())).isTrue();
    }

    @Test
    void wwwLinkIsViolation() {
        assertThat(detector.isViolation("заходите www.example.com прямо сейчас", List.of())).isTrue();
    }

    @Test
    void bareTelegramLinkIsViolation() {
        assertThat(detector.isViolation("канал тут: t.me/myspamchannel", List.of())).isTrue();
    }

    // --- голые домены без схемы (W5): закрытый список TLD ---

    @Test
    void bareDomainIsViolation() {
        assertThat(detector.isViolation("заходи на example.com вечером", List.of())).isTrue();
    }

    @Test
    void bareDomainWithSubdomainsIsViolation() {
        assertThat(detector.isViolation("мой-канал.site в подписи", List.of())).isTrue();
    }

    @Test
    void cyrillicIdnDomainIsViolation() {
        assertThat(detector.isViolation("смотри сайт.ру каждый день", List.of())).isTrue();
    }

    @Test
    void cyrillicTldRfIsViolation() {
        assertThat(detector.isViolation("новости.рф", List.of())).isTrue();
    }

    @Test
    void uppercaseDomainIsViolation() {
        assertThat(detector.isViolation("СПАМ EXAMPLE.COM", List.of())).isTrue();
    }

    @Test
    void cyrillicUppercaseDomainIsViolation() {
        // (?i) без UNICODE_CASE не фолдит кириллицу — обход капсом (ревью W5')
        assertThat(detector.isViolation("ЗАХОДИ САЙТ.РУ", List.of())).isTrue();
        assertThat(detector.isViolation("НОВОСТИ.РФ", List.of())).isTrue();
    }

    @Test
    void cyrillicTranslitTldsAreViolations() {
        // N2': «сайт.ком» — самый частый RU-вариант обхода
        assertThat(detector.isViolation("заходи на сайт.ком", List.of())).isTrue();
        assertThat(detector.isViolation("форум.орг", List.of())).isTrue();
        assertThat(detector.isViolation("видео.нет", List.of())).isTrue();
        assertThat(detector.isViolation("интернет.инфо", List.of())).isTrue();
    }

    @Test
    void domainNotInTldListIsNotViolation() {
        // Имена файлов и версии не должны попадать под фильтр — потому список TLD закрытый
        assertThat(detector.isViolation("отправляю report.pdf", List.of())).isFalse();
        assertThat(detector.isViolation("вышла версия 2.5", List.of())).isFalse();
        assertThat(detector.isViolation("см. пункт 3.10 правил", List.of())).isFalse();
    }

    // --- entities: даже скрытые ссылки ---

    @Test
    void urlEntityIsViolation() {
        var entity = new MessageEntity("url", 0, 5);
        assertThat(detector.isViolation("клик", List.of(entity))).isTrue();
    }

    @Test
    void textLinkWithHiddenUrlIsViolation() {
        var entity = new MessageEntity("text_link", 0, 4);
        entity.setUrl("http://evil.example");
        assertThat(detector.isViolation("тут", List.of(entity))).isTrue();
    }

    @Test
    void textLinkWithoutUrlIsStillSuspicious() {
        var entity = new MessageEntity("text_link", 0, 4);
        assertThat(detector.isViolation("тут", List.of(entity))).isTrue();
    }

    // --- @упоминания: по решению владельца считаются нарушением ---

    @Test
    void mentionEntityIsViolation() {
        var entity = new MessageEntity("mention", 8, 9);
        assertThat(detector.isViolation("пишите в @spamchannel", List.of(entity))).isTrue();
    }

    @Test
    void textMentionEntityIsViolation() {
        // N1': скрытое упоминание пользователя без username (text_mention)
        var entity = new MessageEntity("text_mention", 0, 6);
        assertThat(detector.isViolation("пишите ему", List.of(entity))).isTrue();
    }

    @Test
    void mentionAtWordStartIsViolationWithoutEntities() {
        assertThat(detector.isViolation("пиши @spammer прямо сейчас", List.of())).isTrue();
    }

    @Test
    void mentionAfterPunctuationIsViolation() {
        assertThat(detector.isViolation("реклама (@spammer) дешево", List.of())).isTrue();
    }

    // --- ложные срабатывания ---

    @Test
    void emailAddressIsNotAViolation() {
        // @ внутри email не на границе слова — упоминанием не считается
        assertThat(detector.isViolation("пишите на contact@example.com", List.of())).isFalse();
    }

    @Test
    void cyrillicWordBeforeAtIsNotAViolation() {
        // @ после кириллического слова — это не @упоминание с границы слова
        assertThat(detector.isViolation("события2026@новости.ру", List.of())).isFalse();
    }

    @Test
    void wordWithAtInsideIsNotAViolation() {
        assertThat(detector.isViolation("это просто тест_текст без адресов", List.of())).isFalse();
    }

    @Test
    void hashtagIsNotAViolation() {
        assertThat(detector.isViolation("новый пост #новости", List.of())).isFalse();
    }

    @Test
    void botCommandIsNotAViolation() {
        assertThat(detector.isViolation("/start", List.of())).isFalse();
    }

    // --- подписи к медиа обрабатываются тем же методом ---

    @Test
    void captionWithLinkIsViolation() {
        var entity = new MessageEntity("url", 0, 19);
        assertThat(detector.isViolation("https://spam.example купи", List.of(entity))).isTrue();
    }
}
