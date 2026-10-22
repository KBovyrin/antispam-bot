package ru.kbovyrin.antispam.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DotenvEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    private final DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor();

    @Test
    void loadsVariablesFromEnvFile() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        Files.writeString(tempDir.resolve(".env"), """
                # комментарий игнорируется
                BOT_TOKEN=123456:abc-def
                QUOTED="значение с пробелами"
                SINGLE='одинарные'
                   SPACED  =  пробелы обрезаются
                EMPTY=
                БЕЗ_ЗНАКА_РАВНО
                =БЕЗ_КЛЮЧА
                """);

        processor.load(environment, tempDir.resolve(".env"));

        assertThat(environment.getProperty("BOT_TOKEN")).isEqualTo("123456:abc-def");
        assertThat(environment.getProperty("QUOTED")).isEqualTo("значение с пробелами");
        assertThat(environment.getProperty("SINGLE")).isEqualTo("одинарные");
        assertThat(environment.getProperty("SPACED")).isEqualTo("пробелы обрезаются");
        assertThat(environment.getProperty("EMPTY")).isEmpty();
    }

    @Test
    void realEnvironmentWinsOverDotenv() throws Exception {
        // В .env значение-ловушка: настоящий PATH из окружения должен победить
        Files.writeString(tempDir.resolve(".env"), "PATH=ИЗ_DOTENV\n");

        ConfigurableEnvironment environment = new StandardEnvironment();
        processor.load(environment, tempDir.resolve(".env"));

        assertThat(environment.getProperty("PATH")).isNotEqualTo("ИЗ_DOTENV");
    }

    @Test
    void missingFileIsIgnored() {
        ConfigurableEnvironment environment = new StandardEnvironment();

        processor.load(environment, tempDir.resolve("absent.env"));

        assertThat(environment.getPropertySources().contains("dotenv")).isFalse();
    }

    @Test
    void dotenvSourceIsNamedForDiagnostics() throws Exception {
        Files.writeString(tempDir.resolve(".env"), "A=1\n");
        ConfigurableEnvironment environment = new StandardEnvironment();

        processor.load(environment, tempDir.resolve(".env"));

        var source = environment.getPropertySources().get("dotenv");
        assertThat(source).isInstanceOf(MapPropertySource.class);
        assertThat(((MapPropertySource) source).getProperty("A")).isEqualTo("1");
    }

    @Test
    void laterLinesOverrideEarlierOnes() throws Exception {
        Files.writeString(tempDir.resolve(".env"), "A=first\nA=second\n");
        ConfigurableEnvironment environment = new StandardEnvironment();

        processor.load(environment, tempDir.resolve(".env"));

        assertThat(environment.getProperty("A")).isEqualTo("second");
    }

    @Test
    void exportPrefixAndInlineCommentsAreParsedLikeCompose() throws Exception {
        // N3': расхождение с docker-compose env_file делало переменные тихо нерабочими
        Files.writeString(tempDir.resolve(".env"), """
                export BOT_TOKEN=123456:abc
                ADMIN_CHAT_ID=42 # идентификатор владельца
                HASH=значение#без_пробела
                QUOTED="a # b"
                """);
        ConfigurableEnvironment environment = new StandardEnvironment();

        processor.load(environment, tempDir.resolve(".env"));

        assertThat(environment.getProperty("BOT_TOKEN")).isEqualTo("123456:abc");
        assertThat(environment.getProperty("ADMIN_CHAT_ID")).isEqualTo("42");
        // # вплотную к значению — часть значения (как в env_file compose)
        assertThat(environment.getProperty("HASH")).isEqualTo("значение#без_пробела");
        // # внутри кавычек не коммент
        assertThat(environment.getProperty("QUOTED")).isEqualTo("a # b");
    }

    @Test
    void dotenvStillAppliedWhenSystemEnvironmentSourceAbsent() throws Exception {
        // N11: в средах без источника systemEnvironment addAfter молча ничего
        // не добавлял — .env тихо не работал
        Files.writeString(tempDir.resolve(".env"), "BOT_TOKEN=123456:abc\n");
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove("systemEnvironment");

        processor.load(environment, tempDir.resolve(".env"));

        assertThat(environment.getProperty("BOT_TOKEN")).isEqualTo("123456:abc");
        // .env ниже systemProperties: системные свойства продолжают выигрывать
        var systemProperties = environment.getPropertySources().get("systemProperties");
        var dotenv = environment.getPropertySources().get("dotenv");
        assertThat(environment.getPropertySources().precedenceOf(systemProperties))
                .isLessThan(environment.getPropertySources().precedenceOf(dotenv));
    }
}
