package com.humanitarian.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.Consultation;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.Report;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.service.ProviderStatsService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * AGG-1 against the real schema: the four counter caches are recomputed in full
 * from reports and consultations, the stored numeric(3,2) equals the value the
 * service computed (G3: persisted == computed), no ratings is NULL (D-3), and a
 * corrected rating fixes the cache because nothing is averaged incrementally.
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
@Import(ProviderStatsService.class)
class ProviderStatsPersistenceTest extends PersistenceTestSupport {

    @Autowired private ProviderStatsService stats;

    private Volunteer newVolunteer(String suffix) {
        User user = newUser(UserRole.VOLUNTEER, "agg-vol-" + suffix + "@example.test");
        return em.persistAndFlush(Volunteer.builder().user(user).isAvailable(true).build());
    }

    /** One completed help request, assigned to the volunteer, with its report carrying the rating (null = not rated). */
    private Report newReport(Volunteer volunteer, User beneficiary, Integer rating) {
        HelpRequest request = newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "COMPLETED");
        Assignment assignment = em.persistAndFlush(Assignment.builder()
                .requestId(request.getId()).requestType("HELP_REQUEST").volunteerId(volunteer.getId())
                .assignedBy(volunteer.getUser().getId()).assignmentSource("MANUAL").status("COMPLETED")
                .assignedAt(LocalDateTime.now().minusHours(2)).completedAt(LocalDateTime.now()).build());
        return em.persistAndFlush(Report.builder().assignmentId(assignment.getId()).volunteerId(volunteer.getId())
                .description("delivered").beneficiaryRating(rating).build());
    }

    private Psychologist newPsychologist(String suffix) {
        User user = newUser(UserRole.PSYCHOLOGIST, "agg-psy-" + suffix + "@example.test");
        return em.persistAndFlush(Psychologist.builder().user(user).isVerified(true).build());
    }

    private Consultation newConsultation(Psychologist psychologist, User beneficiary, Integer rating) {
        PsychologicalRequest request = em.persistAndFlush(PsychologicalRequest.builder()
                .beneficiaryId(beneficiary.getId()).assignedPsychologistId(psychologist.getId())
                .category("ANXIETY").supportType("INDIVIDUAL").urgencyLevel("MEDIUM").preferredFormat("CHAT")
                .description("counter").status("COMPLETED").build());
        return em.persistAndFlush(Consultation.builder().psychologicalRequestId(request.getId())
                .psychologistId(psychologist.getId()).beneficiaryId(beneficiary.getId()).format("CHAT")
                .startedAt(LocalDateTime.now().minusHours(1)).durationMinutes(30).rating(rating).build());
    }

    /** The raw column, so the assertion is on what PostgreSQL stores and not on a Double round trip. */
    private String storedVolunteerCounters(Long volunteerId) {
        return (String) em.getEntityManager().createNativeQuery(
                "SELECT CAST(total_completed_requests AS text) || '/' || COALESCE(CAST(rating AS text), 'NULL') FROM volunteers WHERE volunteer_id = :id")
                .setParameter("id", volunteerId).getSingleResult();
    }

    private String storedPsychologistCounters(Long psychologistId) {
        return (String) em.getEntityManager().createNativeQuery(
                "SELECT CAST(consultation_count AS text) || '/' || COALESCE(CAST(rating AS text), 'NULL') FROM psychologists WHERE psychologist_id = :id")
                .setParameter("id", psychologistId).getSingleResult();
    }

    private static Double meanOf(List<Integer> ratings) {
        return ProviderStatsService.roundToHundredths(ratings.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN));
    }

    /** Gate 5, AGG-1: reports rated 5, 4, 3 give rating = 4.00 and total_completed_requests = 3. */
    @Test
    void volunteerWithReportsRatedFiveFourThreeHasMeanFourAndCountThree() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "agg-bene-543@example.test");
        Volunteer v = newVolunteer("543");
        for (int rating : List.of(5, 4, 3)) newReport(v, beneficiary, rating);
        assertEquals("0/NULL", storedVolunteerCounters(v.getId()), "nothing written until a refresh");

        stats.refreshVolunteer(v.getId());
        em.clear();

        assertEquals("3/4.00", storedVolunteerCounters(v.getId()));
        Volunteer reloaded = em.find(Volunteer.class, v.getId());
        assertEquals(3, reloaded.getTotalCompletedRequests());
        assertEquals(meanOf(List.of(5, 4, 3)), reloaded.getRating(), "persisted == computed");
    }

    /** Gate 5, AGG-1: a volunteer with no reports has NULL, never 0 (D-3). */
    @Test
    void volunteerWithNoReportsHasNullRatingAndZeroCount() {
        Volunteer v = newVolunteer("none");
        stats.refreshVolunteer(v.getId());
        em.clear();

        assertEquals("0/NULL", storedVolunteerCounters(v.getId()));
        assertNull(em.find(Volunteer.class, v.getId()).getRating());
    }

    @Test
    void unratedReportCountsAsCompletedButNotInTheMean() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "agg-bene-unrated@example.test");
        Volunteer v = newVolunteer("unrated");
        newReport(v, beneficiary, 5);
        newReport(v, beneficiary, null);

        stats.refreshVolunteer(v.getId());
        em.clear();

        assertEquals("2/5.00", storedVolunteerCounters(v.getId()));
    }

    /** Recompute, not incremental averaging: a corrected rating changes the stored mean to the new true mean. */
    @Test
    void aCorrectedRatingIsReflectedBecauseTheMeanIsRecomputed() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "agg-bene-fix@example.test");
        Volunteer v = newVolunteer("fix");
        newReport(v, beneficiary, 5);
        Report second = newReport(v, beneficiary, 4);
        newReport(v, beneficiary, 4);
        stats.refreshVolunteer(v.getId());
        em.clear();
        assertEquals("3/4.33", storedVolunteerCounters(v.getId()));
        assertEquals(meanOf(List.of(5, 4, 4)), em.find(Volunteer.class, v.getId()).getRating());

        Report corrected = em.find(Report.class, second.getId());
        corrected.setBeneficiaryRating(2);
        em.persistAndFlush(corrected);
        stats.refreshVolunteer(v.getId());
        em.clear();

        assertEquals("3/3.67", storedVolunteerCounters(v.getId()), "(5 + 2 + 4) / 3, not the old mean nudged");
        assertEquals(meanOf(List.of(5, 2, 4)), em.find(Volunteer.class, v.getId()).getRating());
        assertEquals(0, new BigDecimal("3.67").compareTo(BigDecimal.valueOf(em.find(Volunteer.class, v.getId()).getRating())));
    }

    @Test
    void psychologistCountersFollowConsultationsAndAreNullWithoutAny() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "agg-bene-psy@example.test");
        Psychologist rated = newPsychologist("rated");
        Psychologist idle = newPsychologist("idle");
        newConsultation(rated, beneficiary, 5);
        newConsultation(rated, beneficiary, 4);
        newConsultation(rated, beneficiary, null);

        stats.refreshPsychologist(rated.getId());
        stats.refreshPsychologist(idle.getId());
        em.clear();

        assertEquals("3/4.50", storedPsychologistCounters(rated.getId()));
        assertEquals(meanOf(List.of(5, 4)), em.find(Psychologist.class, rated.getId()).getRating());
        assertEquals(3, em.find(Psychologist.class, rated.getId()).getConsultationCount());
        assertEquals("0/NULL", storedPsychologistCounters(idle.getId()));
        assertNull(em.find(Psychologist.class, idle.getId()).getRating());
    }
}
