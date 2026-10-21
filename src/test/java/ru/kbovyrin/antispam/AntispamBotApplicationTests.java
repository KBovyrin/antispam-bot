package ru.kbovyrin.antispam;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ru.kbovyrin.antispam.config.AntispamProperties;

@SpringBootTest
class AntispamBotApplicationTests {

    @Autowired
    AntispamProperties properties;

    @Test
    void contextLoads() {
    }

    @Test
    void propertiesAreBoundFromTestConfiguration() {
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.botToken()).isEqualTo("test-token");
        assertThat(properties.groupIds()).containsExactly(-100999L);
        assertThat(properties.captcha().timeout()).isEqualTo(Duration.ofMinutes(5));
    }
}
