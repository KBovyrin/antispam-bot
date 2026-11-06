package ru.kbovyrin.antispam.captcha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Банк загадок «Выберите лишнее» (P3 плана 2026-11).
 * <p>
 * Каждая загадка: три однородных эмодзи + один лишний из другой категории.
 * Ответ требует семантики и НЕ вычисляется из текста вопроса — в отличие от
 * прежней арифметики («7 + 3»), которую юзербот решал парсингом сообщения.
 */
@Component
public class CaptchaQuestions {

    static final String QUESTION_TEXT = "Выберите лишнее";

    /** Загадка: лишний эмодзи + группа однородных. */
    private record Puzzle(String oddOne, List<String> group) {
    }

    private static final List<Puzzle> PUZZLES = List.of(
            new Puzzle("🚗", List.of("🍎", "🍌", "🍇")),
            new Puzzle("🐟", List.of("🐶", "🐱", "🐭")),
            new Puzzle("✈️", List.of("🚲", "🛴", "🛵")),
            new Puzzle("🌧", List.of("😊", "😄", "😆")),
            new Puzzle("🐝", List.of("🌹", "🌷", "🌼")),
            new Puzzle("🍕", List.of("🥕", "🥔", "🧅")),
            new Puzzle("⚽", List.of("🎨", "🎵", "🎬")),
            new Puzzle("🐧", List.of("🕊", "🦅", "🦆")),
            new Puzzle("🐄", List.of("🥛", "🧀", "🍦")),
            new Puzzle("👟", List.of("🧤", "🧣", "🧦")),
            new Puzzle("🔑", List.of("🚪", "🪟", "🛏")),
            new Puzzle("⌚", List.of("📚", "📓", "📰")),
            new Puzzle("🐴", List.of("🚗", "🚌", "🚲")),
            new Puzzle("☂️", List.of("🎩", "🧣", "🎒")),
            new Puzzle("🍪", List.of("🥦", "🌽", "🥬")),
            new Puzzle("🎸", List.of("⚽", "🏀", "🎾")));

    private final Random random;

    public CaptchaQuestions() {
        this(new Random());
    }

    CaptchaQuestions(Random random) {
        this.random = random;
    }

    /** @return загадка «что лишнее» с четырьмя перемешанными кнопками-эмодзи. */
    public CaptchaQuestion next() {
        Puzzle puzzle = PUZZLES.get(random.nextInt(PUZZLES.size()));
        List<String> options = new ArrayList<>(puzzle.group());
        options.add(puzzle.oddOne());
        Collections.shuffle(options, random);
        return new CaptchaQuestion(QUESTION_TEXT, options.indexOf(puzzle.oddOne()), List.copyOf(options));
    }
}
