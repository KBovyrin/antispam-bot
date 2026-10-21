package ru.kbovyrin.antispam.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaptchaQuestionsTest {

    private final CaptchaQuestions questions = new CaptchaQuestions();

    @Test
    void questionContainsExactlyFourDistinctNonNegativeOptionsIncludingAnswer() {
        for (int i = 0; i < 200; i++) {
            CaptchaQuestion q = questions.next();

            assertThat(q.options()).hasSize(4);
            assertThat(q.options()).doesNotHaveDuplicates();
            assertThat(q.options()).allMatch(o -> Integer.parseInt(o) >= 0);

            // Текст вопроса — «a + b», верный ответ присутствует среди вариантов
            String[] parts = q.text().split(" \\+ ");
            int expectedSum = Integer.parseInt(parts[0]) + Integer.parseInt(parts[1]);
            assertThat(q.options()).contains(String.valueOf(expectedSum));
            assertThat(q.correctOptionIndex()).isBetween(0, 3);
            assertThat(q.options().get(q.correctOptionIndex())).isEqualTo(String.valueOf(expectedSum));
        }
    }
}
