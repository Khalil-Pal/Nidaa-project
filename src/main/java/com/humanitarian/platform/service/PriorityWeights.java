package com.humanitarian.platform.service;

/**
 * The numbers behind the priority model, so they can be named, defended
 * (`docs/SCORING.md`) and varied in the sensitivity analysis of EV-1 without
 * touching the production model. {@link #DEFAULT} is what the platform runs;
 * nothing else in `src/main` constructs anything but the default.
 *
 * @param critical      urgency points for CRITICAL
 * @param high          urgency points for HIGH
 * @param medium        urgency points for MEDIUM
 * @param low           urgency points for LOW and for anything unrecognised
 * @param children      bonus when the household includes children
 * @param elderly       bonus when it includes elderly people
 * @param disabled      bonus when it includes a person with a disability
 * @param perPerson     points per person in the household
 * @param peopleCap     ceiling on the household-size term
 * @param agingPerHour  points added per full hour waited
 * @param agingCap      ceiling on the waiting term
 * @param crisisBonus   bonus for a psychological request the crisis detector flagged
 */
public record PriorityWeights(int critical, int high, int medium, int low,
                              int children, int elderly, int disabled,
                              int perPerson, int peopleCap,
                              double agingPerHour, int agingCap,
                              int crisisBonus) {

    /** The production model, defended weight by weight in `docs/SCORING.md`. */
    public static final PriorityWeights DEFAULT =
            new PriorityWeights(40, 30, 20, 10, 10, 10, 15, 2, 20, 0.5, 20, 35);

    /** EV-1 sensitivity: the three vulnerability factors weigh the same. */
    public PriorityWeights withLevelledVulnerability() {
        return new PriorityWeights(critical, high, medium, low, 10, 10, 10,
                perPerson, peopleCap, agingPerHour, agingCap, crisisBonus);
    }

    /** EV-1 sensitivity: urgency dominates everything else twice as strongly. */
    public PriorityWeights withDoubledUrgency() {
        return new PriorityWeights(critical * 2, high * 2, medium * 2, low * 2,
                children, elderly, disabled, perPerson, peopleCap, agingPerHour, agingCap, crisisBonus);
    }

    /** EV-1 sensitivity: waiting is never capped, so ageing eventually overtakes urgency. */
    public PriorityWeights withUncappedWaiting() {
        return new PriorityWeights(critical, high, medium, low, children, elderly, disabled,
                perPerson, peopleCap, agingPerHour, PriorityScoreService.MAX_SCORE, crisisBonus);
    }

    /** EV-1 sensitivity: vulnerability counts for nothing, to isolate what it buys. */
    public PriorityWeights withoutVulnerability() {
        return new PriorityWeights(critical, high, medium, low, 0, 0, 0,
                perPerson, peopleCap, agingPerHour, agingCap, crisisBonus);
    }
}
