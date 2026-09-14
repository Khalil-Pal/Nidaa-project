package com.humanitarian.platform.persistence;

import com.humanitarian.platform.dto.PsychologistCaseLoad;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q-1: crisis routing ranks psychologists by open case load with one grouped
 * query instead of one COUNT per comparison. The query compares the enum-typed
 * {@code status} column against a string parameter, so it is checked here
 * against the real schema rather than only through a mock.
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
class PsychologicalRequestPersistenceTest extends PersistenceTestSupport {

    @Autowired private PsychologicalRequestRepository repository;
    @Autowired private PsychologistRepository psychologistRepository;

    private Psychologist newPsychologist(String email) {
        User user = newUser(UserRole.PSYCHOLOGIST, email);
        return em.persistAndFlush(Psychologist.builder().user(user).isVerified(true).isOnDuty(true).build());
    }

    private void newPsychRequest(Long beneficiaryId, Long psychologistId, String status) {
        em.persistAndFlush(PsychologicalRequest.builder()
                .beneficiaryId(beneficiaryId)
                .category("ANXIETY")
                .supportType("INDIVIDUAL")
                .urgencyLevel("MEDIUM")
                .preferredFormat("CHAT")
                .description("case load")
                .status(status)
                .assignedPsychologistId(psychologistId)
                .build());
    }

    @Test
    void caseLoadCountsOnlyOpenCasesAndOmitsIdlePsychologists() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "caseload-bene@example.test");
        Psychologist busy = newPsychologist("caseload-busy@example.test");
        Psychologist lighter = newPsychologist("caseload-lighter@example.test");
        Psychologist idle = newPsychologist("caseload-idle@example.test");

        newPsychRequest(beneficiary.getId(), busy.getId(), "ASSIGNED");
        newPsychRequest(beneficiary.getId(), busy.getId(), "ASSIGNED");
        newPsychRequest(beneficiary.getId(), busy.getId(), "COMPLETED");   // closed: not counted
        newPsychRequest(beneficiary.getId(), lighter.getId(), "ASSIGNED");
        newPsychRequest(beneficiary.getId(), null, "PENDING");             // unassigned: no row
        em.clear();

        List<PsychologistCaseLoad> rows = repository.caseLoadByPsychologist("ASSIGNED");
        Map<Long, Long> byPsychologist = rows.stream()
                .collect(Collectors.toMap(PsychologistCaseLoad::psychologistId, PsychologistCaseLoad::openCases));

        assertEquals(2L, byPsychologist.get(busy.getId()));
        assertEquals(1L, byPsychologist.get(lighter.getId()));
        assertFalse(byPsychologist.containsKey(idle.getId()), "no open cases means no row, callers default to 0");
    }

    /** UX-2: the duty flag a psychologist sets is exactly what crisis routing selects on. */
    @Test
    void goingOffDutyRemovesAPsychologistFromTheCrisisRoutingPool() {
        Psychologist verified = newPsychologist("duty-verified@example.test");
        Psychologist unverified = newPsychologist("duty-unverified@example.test");
        unverified.setIsVerified(false);
        em.persistAndFlush(unverified);
        em.clear();

        List<Long> pool = psychologistRepository.findByIsVerifiedTrueAndIsOnDutyTrue().stream().map(Psychologist::getId).toList();
        assertTrue(pool.contains(verified.getId()), "verified and on duty: routable");
        assertFalse(pool.contains(unverified.getId()), "on duty but unverified: not routable");

        Psychologist reloaded = em.find(Psychologist.class, verified.getId());
        reloaded.setIsOnDuty(false);
        em.persistAndFlush(reloaded);
        em.clear();

        assertFalse(psychologistRepository.findByIsVerifiedTrueAndIsOnDutyTrue().stream()
                .anyMatch(p -> p.getId().equals(verified.getId())), "off duty: no crisis case is routed here");
    }
}
