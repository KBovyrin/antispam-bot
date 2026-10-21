package ru.kbovyrin.antispam.captcha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Генератор простых арифметических вопросов капчи.
 */
@Component
public class CaptchaQuestions {

    private final Random random;

    public CaptchaQuestions() {
        this(new Random());
    }

    CaptchaQuestions(Random random) {
        this.random = random;
    }

    /** @return вопрос вида «7 + 3» с четырьмя вариантами ответа. */
    public CaptchaQuestion next() {
        int a = 2 + random.nextInt(19);
        int b = 2 + random.nextInt(19);
        int answer = a + b;

        Set<Integer> options = new LinkedHashSet<>();
        options.add(answer);
        while (options.size() < 4) {
            int candidate = answer + random.nextInt(7) - 3; // дистрактор в диапазоне ±3
            if (candidate >= 0) {
                options.add(candidate);
            }
        }

        List<Integer> shuffled = new ArrayList<>(options);
        Collections.shuffle(shuffled, random);
        List<String> labels = shuffled.stream().map(String::valueOf).toList();
        return new CaptchaQuestion(a + " + " + b, labels.indexOf(String.valueOf(answer)), labels);
    }
}
