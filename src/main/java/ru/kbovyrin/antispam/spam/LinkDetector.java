package ru.kbovyrin.antispam.spam;

import java.net.IDN;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

/**
 * Определяет, содержит ли сообщение нарушение: ссылку или @упоминание.
 * <p>
 * Опирается на сущности Telegram (url, text_link, text_mention, mention — включая
 * подписи к медиа), а при их отсутствии — на regex-fallback по тексту.
 * <p>
 * Hardening аудита 2026-11: перед regex текст нормализуется — NFKC (фуллwidth→ASCII),
 * снятие невидимых символов (ZWSP и пр.), склейка «example .com», декодирование
 * punycode-меток (xn--…). Дополнительно те же паттерны проверяются против копии
 * со свёрткой конфузаблов (кириллица/греческий → визуально совпадающая латиница):
 * гомоглифные домены вида example.соm больше не проходят.
 */
@Component
public final class LinkDetector {

    private static final Pattern HTTP_URL = Pattern.compile("(?i)\\bhttps?://\\S+");
    private static final Pattern WWW_URL = Pattern.compile("(?i)\\bwww\\.[a-z0-9-]+(\\.[a-z0-9-]+)+");
    // t.me и его зеркала (telegram.dog, telegram.me) резолвятся одинаково
    private static final Pattern TELEGRAM_LINK = Pattern.compile("(?i)\\b(?:t\\.me|telegram\\.(?:me|dog))/\\S+");
    // tg://resolve?domain=... — кликабельная ссылка на канал без t.me
    private static final Pattern TG_SCHEME = Pattern.compile("(?i)\\btg://\\S+");
    // @упоминание только с границы слова: перед @ не буква/цифра/@.
    // \p{L} отсекает email-подобные конструкции вида "почта@домен".
    private static final Pattern MENTION = Pattern.compile("(?<![\\p{L}\\p{N}_@])@[A-Za-z0-9_]{4,}");

    /**
     * Голый домен без схемы («заходи на example.com», кириллический IDN «сайт.ру»).
     * Список TLD закрытый: открытый \.\p{L}{2,} ловил бы имена файлов (report.pdf)
     * и версии (2.5). Метки допускают Unicode — иначе не матчится «мой-канал.site».
     * Lookbehind отсекает email (contact@example.com) и середину чужого матча.
     * Трейд-офф: домен в непопулярном TLD (kz, de…) без схемы проходит —
     * распознавание entities Telegram по-прежнему ловит кликабельные ссылки,
     * а homoglyph/punycode-варианты закрывает нормализация ниже.
     * Флаги (?iu): без UNICODE_CASE кириллица капсом («САЙТ.РУ») не фолдится.
     */
    private static final String TLD_LIST = "com|net|org|ru|su|ua|by|io|me|xyz|top|online|site|info|biz"
            + "|cc|co|tv|app|dev|link|live|store|pro|icu|vip|one|win|fun|click"
            + "|рф|ру|онлайн|сайт|магазин|москва|дети"
            + "|ком|орг|нет|инфо";
    private static final Pattern BARE_DOMAIN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_@.-])[\\p{L}\\p{N}-]{2,}(?:\\.[\\p{L}\\p{N}-]+)*\\.(?:" + TLD_LIST + ")(?![\\p{L}\\p{N}-])");

    private static final Pattern SUSPICIOUS_ENTITY_TYPES = Pattern.compile("url|text_link|text_mention|mention");

    /** Невидимые символы, разрывающие домен для regex, но не для глаза и копипасты. */
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\u00AD\\u061C\\u180E\\u200B-\\u200F\\u2060-\\u2064\\uFEFF]");
    /**
     * «example .com» → «example.com»: пробел ДО точки (после — опционально).
     * Пробел только ПОСЛЕ точки — это граница предложения («лучше. Нет»), не
     * склеивается: кириллические TLD (нет, сайт, дети…) — обычные русские слова,
     * и склейка банила невиновных (блокер ревью диффа 2026-11).
     */
    private static final Pattern SPACED_DOT = Pattern.compile("(?<=\\S)\\s+\\.\\s*(?=\\S)");
    /**
     * «example. com» → «example.com»: пробел только ПОСЛЕ точки склеивается,
     * лишь когда слева ASCII-доменоподобный токен, а справа — «безопасный»
     * латинский TLD, не являющийся словом живой речи (com/net/org… — но не
     * win/me/one/live/app: «Try. Win», «The app. Dev» — границы предложений).
     */
    private static final Pattern DOT_SPACE_TLD = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_.@-])([A-Za-z0-9-]{2,}(?:\\.[A-Za-z0-9-]+)*\\.)\\s+"
                    + "(com|org|ru|ua)(?![A-Za-z0-9-])");
    /** Хвостовая пунктуация вокруг punycode-токена: «(xn--…),» → «xn--…». */
    private static final Pattern DOMAIN_EDGE = Pattern.compile("^[^A-Za-z0-9.-]+|[^A-Za-z0-9.-]+$");

    /** Кириллица/греческий → визуально совпадающая латиница (для копии-свёртки). */
    private static final Map<Character, Character> CONFUSABLES = Map.ofEntries(
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
            Map.entry('с', 'c'), Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'),
            Map.entry('ѕ', 's'), Map.entry('ј', 'j'), Map.entry('һ', 'h'), Map.entry('ԁ', 'd'),
            Map.entry('α', 'a'), Map.entry('ο', 'o'), Map.entry('ν', 'v'), Map.entry('ρ', 'p'));

    /**
     * @param text     текст сообщения или подпись к медиа (может быть null)
     * @param entities сущности Telegram для этого текста (message_entities или caption_entities)
     * @return true, если сообщение нарушает правило «без ссылок и упоминаний»
     */
    public boolean isViolation(String text, List<MessageEntity> entities) {
        if (entities != null) {
            for (MessageEntity entity : entities) {
                if (entity != null && SUSPICIOUS_ENTITY_TYPES.matcher(entity.getType()).matches()) {
                    return true;
                }
            }
        }
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalize(text);
        return matchesAny(normalized) || matchesAny(foldConfusables(normalized));
    }

    private static boolean matchesAny(String text) {
        return HTTP_URL.matcher(text).find()
                || WWW_URL.matcher(text).find()
                || TELEGRAM_LINK.matcher(text).find()
                || TG_SCHEME.matcher(text).find()
                || BARE_DOMAIN.matcher(text).find()
                || MENTION.matcher(text).find();
    }

    /**
     * Канонизация текста перед regex: NFKC сворачивает полноширинные CJK-символы
     * в ASCII, невидимые разделители удаляются, домены с пробелами вокруг точки
     * склеиваются, punycode-метки (xn--) декодируются в Unicode.
     */
    private static String normalize(String text) {
        String normalized = DOT_SPACE_TLD.matcher(SPACED_DOT.matcher(
                INVISIBLE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("")
        ).replaceAll(".")).replaceAll("$1$2");
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("xn--")) {
            return normalized;
        }
        // punycode-детект регистронезависим (warn ревью диффа): «XN--…» декодируется
        // так же; хвостовая пунктуация токена обрезается до домена
        StringBuilder sb = new StringBuilder(normalized.length());
        for (String token : normalized.split("\\s+")) {
            String tokenLower = token.toLowerCase(java.util.Locale.ROOT);
            sb.append(tokenLower.contains("xn--") ? idnToUnicode(tokenLower) : token).append(' ');
        }
        return sb.toString();
    }

    private static String idnToUnicode(String lowerToken) {
        String domain = DOMAIN_EDGE.matcher(lowerToken).replaceAll("");
        try {
            return IDN.toUnicode(domain, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            return domain; // не ACE-домен — оставляем как есть
        }
    }

    /** Копия текста со свёрткой конфузаблов: «example.соm» (кирилл. о) → «example.com». */
    private static String foldConfusables(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(CONFUSABLES.getOrDefault(Character.toLowerCase(c), c));
        }
        return sb.toString();
    }
}
