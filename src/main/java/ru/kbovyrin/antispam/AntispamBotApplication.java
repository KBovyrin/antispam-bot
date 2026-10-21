package ru.kbovyrin.antispam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AntispamBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AntispamBotApplication.class, args);
    }
}
