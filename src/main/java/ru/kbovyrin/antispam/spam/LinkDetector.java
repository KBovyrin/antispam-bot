package ru.kbovyrin.antispam.spam;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

/**
 * Определяет, содержит ли сообщение нарушение: ссылку или @упоминание.
 * <p>
 * Опирается на сущности Telegram (url, text_link, mention — включая подписи к медиа),
 * а при их отсутствии — на regex-fallback по тексту.
 */
@Component
public final class LinkDetector {

    private static final Pattern HTTP_URL = Pattern.compile("(?i)\\bhttps?://\\S+");
    private static final Pattern WWW_URL = Pattern.compile("(?i)\\bwww\\.[a-z0-9-]+(\\.[a-z0-9-]+)+");
    private static final Pattern TELEGRAM_LINK = Pattern.compile("(?i)\\bt\\.me/\\S+");
    // @упоминание только с границы слова: перед @ не буква/цифра/@.
    // \p{L} отсекает email-подобные конструкции вида "почта@домен".
    private static final Pattern MENTION = Pattern.compile("(?<![\\p{L}\\p{N}_@])@[A-Za-z0-9_]{4,}");

    /**
     * Голый домен без схемы («заходи на example.com», кириллический IDN «сайт.ру»).
     * Список TLD закрытый: открытый \.\p{L}{2,} ловил бы имена файлов (report.pdf)
     * и версии (2.5). Метки допускают Unicode — иначе не матчится «мой-канал.site».
     * Lookbehind отсекает email (contact@example.com) и середину чужого матча.
     * Трейд-офф: домен в непопулярном TLD (kz, de, xn--…) проходит без схемы —
     * распознаваниеEntities Telegram по-прежнему ловит кликабельные ссылки.
     * Флаги (?iu): без UNICODE_CASE кириллица капсом («САЙТ.РУ») не фолдится.
     */
    private static final String TLD_LIST = "com|net|org|ru|su|ua|by|io|me|xyz|top|online|site|info|biz"
            + "|cc|co|tv|app|dev|link|live|store|pro|icu|vip|one|win|fun|click"
            + "|рф|ру|онлайн|сайт|магазин|москва|дети"
            + "|ком|орг|нет|инфо";
    private static final Pattern BARE_DOMAIN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_@.-])[\\p{L}\\p{N}-]{2,}(?:\\.[\\p{L}\\p{N}-]+)*\\.(?:" + TLD_LIST + ")(?![\\p{L}\\p{N}-])");

    private static final Pattern SUSPICIOUS_ENTITY_TYPES = Pattern.compile("url|text_link|text_mention|mention");

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
        return HTTP_URL.matcher(text).find()
                || WWW_URL.matcher(text).find()
                || TELEGRAM_LINK.matcher(text).find()
                || BARE_DOMAIN.matcher(text).find()
                || MENTION.matcher(text).find();
    }
}
