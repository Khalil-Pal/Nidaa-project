package com.humanitarian.platform.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Weighted, two-tier crisis detection for psychological requests (L-2).
 *
 * <p>Terms are matched on word boundaries, never as bare substrings, and each
 * term scores once however often it appears. A HIGH term (3 points) is
 * enough on its own to mark a crisis and route the case to an on-duty
 * psychologist; MED terms (1 point each) accumulate. A score of 1 or 2 is
 * flagged for human review without automatic routing, so a single
 * "hopeless" does not flood the crisis queue but is not lost either.
 *
 * <p>HIGH terms are stems: letters may follow the last word, so "suicid"
 * covers suicide, suicidal and suicidality, "self harm" covers self-harming,
 * "суицид" covers суициде, and Arabic stems accept the definite article.
 * Missing an inflected form is the failure that matters most on a crisis
 * detector, so recall is favoured for this tier. MED terms stay exact words,
 * so "hopelessly" or "worthlessness" do not score; a false review flag is
 * cheap, but the MED list is where ordinary vocabulary lives.
 *
 * <p>Generic words such as "urgent", "emergency", "срочно" and "помогите"
 * are deliberately absent: on a form that asks people why they need
 * psychological help they match a large share of ordinary submissions.
 *
 * <p>Known limitation: there is no negation handling. "I am not suicidal"
 * still scores as HIGH. That is the safer failure mode for this feature; a
 * psychologist dismisses a false alarm far more cheaply than the system
 * misses a real one.
 */
@Service
public class CrisisDetectorService {

    public static final int CRISIS_THRESHOLD = 3;
    static final int HIGH = 3;
    static final int MED = 1;

    public enum Level { NONE, REVIEW, CRISIS }

    /** Result of scoring one description. */
    public record Assessment(int score, Level level, List<String> matchedTerms) {
        public boolean isCrisis() { return level == Level.CRISIS; }
        public boolean needsReview() { return level == Level.REVIEW; }
    }

    private record Term(String text, int weight, Pattern pattern) {
        /** The last word is a stem: any letters may follow it. */
        static Term stem(String text, int weight) {
            return new Term(text, weight, compile(text, true));
        }

        /** Every word must match exactly. */
        static Term exact(String text, int weight) {
            return new Term(text, weight, compile(text, false));
        }
    }

    private static final List<Term> TERMS = List.of(
            // English, HIGH
            Term.stem("suicid", HIGH),                 // suicide, suicidal, suicidality
            Term.stem("self harm", HIGH),              // self-harm, self-harming, self-harmed
            Term.exact("kill myself", HIGH),
            Term.exact("killing myself", HIGH),
            Term.exact("end my life", HIGH),
            Term.exact("ending my life", HIGH),
            Term.exact("ended my life", HIGH),
            // English, MED
            Term.exact("hopeless", MED),
            Term.exact("can't go on", MED),
            Term.exact("worthless", MED),
            // Arabic, HIGH
            Term.stem("انتحار", HIGH),                 // الانتحار, انتحاري
            Term.stem("أريد أن أموت", HIGH),
            Term.stem("لا أريد أن أعيش", HIGH),
            Term.stem("أؤذي نفسي", HIGH),
            // Arabic, MED
            Term.exact("أزمة", MED),
            // Russian, HIGH
            Term.stem("суицид", HIGH),                 // суициде, суицидальный
            Term.stem("самоубийств", HIGH),            // самоубийство, самоубийства, самоубийством
            Term.stem("не хочу жить", HIGH),
            // Russian, MED
            Term.exact("кризис", MED),
            Term.exact("опасность", MED));

    /**
     * Word-boundary pattern for a term. Java's {@code \b} treats only ASCII
     * letters as word characters unless told otherwise, so boundaries are
     * expressed with Unicode letter/digit lookarounds, which work for Arabic
     * and Cyrillic too. Whitespace inside a phrase matches any run of spaces.
     * For a stem the last word may continue with letters; Arabic terms also
     * accept the definite article prefix.
     */
    private static Pattern compile(String term, boolean stem) {
        String[] words = term.trim().split("\\s+");
        boolean arabic = words[0].codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC);
        StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}_])");
        if (arabic && stem) regex.append("(?:ال)?");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) regex.append("\\s+");
            regex.append(Pattern.quote(words[i]));
        }
        if (stem) regex.append("\\p{L}*");
        regex.append("(?![\\p{L}\\p{N}_])");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public Assessment assess(String category, String description) {
        String normalizedCategory = category == null
                ? ""
                : category.toUpperCase().trim().replace(" ", "_").replace("-", "_");
        if ("CRISIS_SUPPORT".equals(normalizedCategory) || "CRISIS".equals(normalizedCategory)) {
            // The person asked for crisis support explicitly; no scoring needed.
            return new Assessment(CRISIS_THRESHOLD, Level.CRISIS, List.of("category:" + normalizedCategory));
        }
        if (description == null || description.isBlank()) {
            return new Assessment(0, Level.NONE, List.of());
        }

        String text = normalise(description);
        int score = 0;
        List<String> matched = new ArrayList<>();
        for (Term term : TERMS) {
            if (term.pattern().matcher(text).find()) {
                score += term.weight();
                matched.add(term.text());
            }
        }
        Level level = score >= CRISIS_THRESHOLD ? Level.CRISIS : score > 0 ? Level.REVIEW : Level.NONE;
        return new Assessment(score, level, List.copyOf(matched));
    }

    /** Convenience for callers that only need the routing decision. */
    public boolean detect(String category, String description) {
        return assess(category, description).isCrisis();
    }

    private static String normalise(String description) {
        return description
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'')   // curly apostrophe in "can’t"
                .replace('-', ' ');        // "self-harm" -> "self harm"
    }
}
