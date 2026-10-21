package ru.kbovyrin.antispam.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Подхватывает .env из рабочей директории (та же конвенция, что в docker-compose env_file):
 * локальный запуск без экспорта переменных работает так же, как контейнер.
 * <p>
 * Переменные реального окружения имеют приоритет над .env — им можно точечно
 * переопределять значения (например, ANTISPAM_ENABLED=false для проверки).
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = Path.of(".env");
        if (Files.isRegularFile(envFile)) {
            load(environment, envFile);
        }
    }

    void load(ConfigurableEnvironment environment, Path envFile) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String rawLine : Files.readAllLines(envFile)) {
                parseLine(rawLine, values);
            }
        } catch (Exception e) {
            // Не читается — работаем без .env, как раньше
            return;
        }
        if (!values.isEmpty()) {
            // После systemEnvironment: окружение важнее файла
            environment.getPropertySources().addAfter("systemEnvironment",
                    new MapPropertySource("dotenv", values));
        }
    }

    private static void parseLine(String rawLine, Map<String, Object> values) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        // N3': "export KEY=VALUE" — как в docker-compose env_file, иначе ключ
        // получался "export KEY" и переменная тихо не работала
        if (line.startsWith("export ") || line.startsWith("export\t")) {
            line = line.substring("export".length()).strip();
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = line.substring(0, eq).strip();
        String value = stripQuotes(stripInlineComment(line.substring(eq + 1).strip()));
        values.put(key, value);
    }

    /**
     * Обрезает inline-комментарий «KEY=VALUE # коммент» (без пробела перед #
     * — часть значения, внутри кавычек — тоже; конвенция docker-compose env_file).
     */
    private static String stripInlineComment(String value) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            char quote = value.charAt(0);
            int close = value.indexOf(quote, 1);
            if (close > 0) {
                String rest = value.substring(close + 1).strip();
                return rest.startsWith("#") ? value.substring(0, close + 1) : value;
            }
            return value;
        }
        int hash = value.indexOf(" #");
        return hash >= 0 ? value.substring(0, hash).strip() : value;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
