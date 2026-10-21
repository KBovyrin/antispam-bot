package ru.kbovyrin.antispam.captcha;

import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CaptchaExpirySchedulerTest {

    final Instant fixed = Instant.parse("2026-10-21T12:00:00Z");

    @Mock
    CaptchaService captchaService;

    @Test
    void schedulerPassesManagedClockInstantToService() {
        CaptchaExpiryScheduler scheduler = new CaptchaExpiryScheduler(captchaService,
                Clock.fixed(fixed, ZoneOffset.UTC));

        scheduler.expireTimedOutSessions();

        // N2: планировщик на управляемых часах, а не на Instant.now()
        verify(captchaService).expireSessions(fixed);
    }
}
