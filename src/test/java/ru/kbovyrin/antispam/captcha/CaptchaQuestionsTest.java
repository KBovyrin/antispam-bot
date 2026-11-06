package ru.kbovyrin.antispam.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

class CaptchaQuestionsTest {

    private final CaptchaQuestions questions = new CaptchaQuestions();

    @Test
    void questionIsEmojiPuzzleWithFourUniqueOptions() {
        for (int i = 0; i < 200; i++) {
            CaptchaQuestion q = questions.next();

            assertThat(q.options()).hasSize(4);
            assertThat(q.options()).doesNotHaveDuplicates();
            assertThat(q.correctOptionIndex()).isBetween(0, 3);

            // P3: ответ не вычисляется из текста — вопрос без чисел и операций
            assertThat(q.text()).doesNotContainPattern("\\d");
            assertThat(q.text()).isEqualTo("Выберите лишнее");
            // варианты — эмодзи, не числа: мгновенный солвер по тексту невозможен
            assertThat(q.options()).allMatch(o -> o.codePointAt(0) > 127);
        }
    }

    @Test
    void correctIndexReallyPointsToAnOption() {
        for (int i = 0; i < 200; i++) {
            CaptchaQuestion q = questions.next();
            assertThat(q.correctOptionIndex()).isBetween(0, q.options().size() - 1);
        }
    }

    @Test
    void seededRandomIsDeterministic() {
        CaptchaQuestions first = new CaptchaQuestions(new Random(42));
        CaptchaQuestions second = new CaptchaQuestions(new Random(42));

        assertThat(first.next()).isEqualTo(second.next());
    }
}
