package com.humanitarian.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.Consultation;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.ConsultationRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * CS-1 against the real schema: {@code consultations.format} is the
 * {@code consultation_format} enum (the entity said varchar before, D-4),
 * {@code topics_discussed} is a {@code text[]} the entity now reads and writes as a
 * list, V20's {@code assignment_id} is a foreign key, a case holds as many session
 * rows as it needs listed oldest first (V22 dropped V20's one-per-case UNIQUE and
 * brought the plain index back), the rating CHECK holds 1..5, and completing a
 * case stamps {@code completed_at}.
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
class ConsultationPersistenceTest extends PersistenceTestSupport {

    @Autowired private ConsultationRepository consultationRepository;
    @Autowired private PsychologicalRequestRepository requestRepository;

    private record Closed(PsychologicalRequest request, Assignment assignment) {
    }

    /** A completed case with its psychologist and the assignment that closed it. */
    private Closed completedCase(String suffix) {
        User beneficiary = newUser(UserRole.BENEFICIARY, "cs-bene-" + suffix + "@example.test");
        User psychologistUser = newUser(UserRole.PSYCHOLOGIST, "cs-psy-" + suffix + "@example.test");
        Psychologist psychologist = em.persistAndFlush(Psychologist.builder().user(psychologistUser).isVerified(true).build());
        PsychologicalRequest request = em.persistAndFlush(PsychologicalRequest.builder()
                .beneficiaryId(beneficiary.getId())
                .assignedPsychologistId(psychologist.getId())
                .category("GRIEF")
                .supportType("INDIVIDUAL")
                .urgencyLevel("MEDIUM")
                .preferredFormat("VIDEO")
                .description("consultation record")
                .isAnonymous(true)
                .status("COMPLETED")
                .build());
        Assignment assignment = em.persistAndFlush(Assignment.builder()
                .psychologicalRequestId(request.getId())
                .requestType("PSYCHOLOGICAL_REQUEST")
                .psychologistId(psychologist.getId())
                .assignedBy(psychologistUser.getId())
                .assignmentSource("MANUAL")
                .status("COMPLETED")
                .assignedAt(LocalDateTime.now().minusHours(2))
                .completedAt(LocalDateTime.now())
                .build());
        return new Closed(request, assignment);
    }

    private static Consultation.ConsultationBuilder record(Closed c, String format) {
        return Consultation.builder()
                .psychologicalRequestId(c.request().getId())
                .assignmentId(c.assignment().getId())
                .psychologistId(c.request().getAssignedPsychologistId())
                .beneficiaryId(c.request().getBeneficiaryId())
                .format(format)
                .startedAt(LocalDateTime.now().minusHours(1))
                .durationMinutes(45);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHAT", "AUDIO", "VIDEO"})
    void everyFormatRoundTripsWithTheTopicsArrayAndTheAssignmentLink(String format) {
        Closed c = completedCase("fmt-" + format.toLowerCase());
        Consultation saved = consultationRepository.saveAndFlush(record(c, format)
                .topicsDiscussed(List.of("sleep", "grief & loss"))
                .recommendations("Keep a sleep diary")
                .notesForPsychologist("private")
                .build());
        em.clear();

        Consultation reloaded = em.find(Consultation.class, saved.getId());
        assertEquals(format, reloaded.getFormat());
        assertEquals(List.of("sleep", "grief & loss"), reloaded.getTopicsDiscussed(), "text[] round trip");
        assertEquals(c.assignment().getId(), reloaded.getAssignmentId());
        assertNull(reloaded.getRating(), "no rating until the beneficiary gives one");
        assertEquals(false, reloaded.getIsCrisis());
        String column = (String) em.getEntityManager()
                .createNativeQuery("SELECT CAST(format AS text) FROM consultations WHERE consultation_id = :id")
                .setParameter("id", saved.getId()).getSingleResult();
        assertEquals(format, column, "stored in the enum column, not as text");
    }

    /** Owner decision after Gate 5 (V22): sessions are rows of the case, in the order they took place. */
    @Test
    void aCaseHoldsOneRowPerSessionListedOldestFirst() {
        Closed c = completedCase("sessions");
        LocalDateTime first = LocalDateTime.of(2026, 9, 10, 10, 0);
        Consultation second = consultationRepository.saveAndFlush(record(c, "CHAT").startedAt(first.plusDays(7)).build());
        Consultation earliest = consultationRepository.saveAndFlush(record(c, "VIDEO").startedAt(first).rating(5).build());
        Consultation third = consultationRepository.saveAndFlush(record(c, "AUDIO").startedAt(first.plusDays(14)).build());
        em.clear();

        List<Consultation> sessions = consultationRepository
                .findByPsychologicalRequestIdOrderByStartedAtAscIdAsc(c.request().getId());
        assertEquals(List.of(earliest.getId(), second.getId(), third.getId()),
                sessions.stream().map(Consultation::getId).toList(), "three rows on one case, by started_at");
        assertEquals(List.of("VIDEO", "CHAT", "AUDIO"), sessions.stream().map(Consultation::getFormat).toList());
        assertEquals(5, sessions.get(0).getRating(), "a rating belongs to its session");
        assertNull(sessions.get(1).getRating());

        assertTrue(consultationRepository.findByIdAndPsychologicalRequestId(second.getId(), c.request().getId()).isPresent());
        Closed other = completedCase("sessions-other");
        assertTrue(consultationRepository.findByIdAndPsychologicalRequestId(second.getId(), other.request().getId()).isEmpty(),
                "a session is addressed under its own case only");
        Long constraints = (Long) em.getEntityManager()
                .createNativeQuery("SELECT count(*) FROM pg_constraint WHERE conname = 'uq_consultations_psych_request'")
                .getSingleResult();
        assertEquals(0L, constraints, "V22 dropped the one-per-case constraint");
    }

    @Test
    void assignmentLinkIsAForeignKey() {
        Closed c = completedCase("fk");
        assertThrows(DataIntegrityViolationException.class, () ->
                consultationRepository.saveAndFlush(record(c, "CHAT").assignmentId(999_999L).build()));
    }

    @Test
    void ratingStaysInsideOneToFive() {
        Closed c = completedCase("rating");
        Consultation r = consultationRepository.saveAndFlush(record(c, "AUDIO").build());
        r.setRating(6);
        assertThrows(DataIntegrityViolationException.class, () -> consultationRepository.saveAndFlush(r));
    }

    @Test
    void completingACaseStampsCompletedAtAndCancellingDoesNot() {
        Closed done = completedCase("stamp");
        PsychologicalRequest open = done.request();
        open.setStatus("ASSIGNED");
        em.persistAndFlush(open);
        em.clear();

        LocalDateTime at = LocalDateTime.of(2026, 9, 16, 10, 30);
        assertEquals(1, requestRepository.updateStatusCompleted(open.getId(), "COMPLETED", at, "ASSIGNED"));
        assertEquals(0, requestRepository.updateStatusCompleted(open.getId(), "COMPLETED", at, "ASSIGNED"), "guarded (B-4)");
        PsychologicalRequest completed = em.find(PsychologicalRequest.class, open.getId());
        assertEquals("COMPLETED", completed.getStatus());
        assertNotNull(completed.getCompletedAt());
        assertEquals(at, completed.getCompletedAt());

        Closed other = completedCase("cancel");
        PsychologicalRequest toCancel = other.request();
        toCancel.setStatus("ASSIGNED");
        em.persistAndFlush(toCancel);
        em.clear();
        assertEquals(1, requestRepository.updateStatusNative(toCancel.getId(), "CANCELLED", "ASSIGNED"));
        assertNull(em.find(PsychologicalRequest.class, toCancel.getId()).getCompletedAt());
    }
}
