package ru.kbovyrin.antispam.captcha;

import java.time.Clock;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодически банит тех, кто не ответил на капчу за отведённое время.
 */
@Component
public class CaptchaExpiryScheduler {

    private final CaptchaService captchaService;
    private final Clock clock;

    public CaptchaExpiryScheduler(CaptchaService captchaService, Clock clock) {
        this.captchaService = captchaService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT30S")
    public void expireTimedOutSessions() {
        captchaService.expireSessions(clock.instant());
    }
}
