package ru.kbovyrin.antispam.captcha;

import java.util.List;

/**
 * Вопрос капчи: текст, варианты ответов и индекс верного.
 */
public record CaptchaQuestion(String text, int correctOptionIndex, List<String> options) {
}
