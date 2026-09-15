package com.humanitarian.platform.persistence;

import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import com.humanitarian.platform.service.AdminAuditService;
import com.humanitarian.platform.service.NotificationService;
import com.humanitarian.platform.service.UserApprovalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-2: approval is one transactional service call against the real schema.
 * This is the path that failed at Gate 3 on NOT NULL columns (fixed in V16)
 * and that gave every approved provider a 5.0 rating (fixed in V18).
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
@Import({UserApprovalService.class, AdminAuditService.class, NotificationService.class})
class UserApprovalPersistenceTest extends PersistenceTestSupport {

    // NotificationService reads the caller only for listing; approval only writes
    @org.springframework.boot.test.mock.mockito.MockBean private com.humanitarian.platform.service.UserService userService;

    @Autowired private UserApprovalService approvalService;
    @Autowired private UserRepository userRepository;
    @Autowired private VolunteerRepository volunteerRepository;
    @Autowired private PsychologistRepository psychologistRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private User pending(UserRole role, String email) {
        User u = newUser(role, email);
        u.setIsActive(false);
        return em.persistAndFlush(u);
    }

    @Test
    void approvingAVolunteerActivatesTheAccountAndCreatesAnAvailableProfile() {
        User u = pending(UserRole.VOLUNTEER, "approve-vol@example.test");

        approvalService.approve(u.getId());
        em.flush(); em.clear();

        assertTrue(em.find(User.class, u.getId()).getIsActive());
        Volunteer v = volunteerRepository.findByUserId(u.getId()).orElseThrow();
        assertTrue(v.getIsAvailable());
        assertNull(v.getRating(), "unrated, not 5.0");

        // N-1: an in-app notification waits for them, stored as SENT in the enum columns
        Object[] n = (Object[]) em.getEntityManager()
                .createNativeQuery("SELECT CAST(type AS text), CAST(status AS text), title, reference_type, reference_id FROM notifications WHERE user_id = :id")
                .setParameter("id", u.getId()).getSingleResult();
        assertEquals("IN_APP", n[0]);
        assertEquals("SENT", n[1]);
        assertEquals("Your application was approved", n[2]);
        assertEquals("USER", n[3]);
        assertEquals(u.getId(), ((Number) n[4]).longValue());
    }

    @Test
    void approvalIsWrittenToActivityLogsWithTheActor() {
        User admin = newUser(UserRole.ADMIN, "approve-actor@example.test");
        User u = pending(UserRole.PSYCHOLOGIST, "approve-audited@example.test");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getEmail(), null, List.of()));
        try {
            approvalService.approve(u.getId());
            em.flush(); em.clear();
        } finally {
            SecurityContextHolder.clearContext();
        }

        Object[] row = (Object[]) em.getEntityManager()
                .createNativeQuery("SELECT user_id, action, entity_type, entity_id, CAST(details AS text) FROM activity_logs "
                        + "WHERE entity_type = 'USER' AND entity_id = :id")
                .setParameter("id", u.getId()).getSingleResult();
        assertEquals(admin.getId(), ((Number) row[0]).longValue());
        assertEquals("USER_APPROVED", row[1]);
        assertEquals(u.getId(), ((Number) row[3]).longValue());
        assertTrue(String.valueOf(row[4]).contains("PSYCHOLOGIST"), "details jsonb holds the role: " + row[4]);
    }

    @Test
    void approvingAPsychologistCreatesAnOffDutyUnverifiedProfileWithoutSpecialization() {
        User u = pending(UserRole.PSYCHOLOGIST, "approve-psy@example.test");

        approvalService.approve(u.getId());
        em.flush(); em.clear();

        Psychologist p = psychologistRepository.findByUserId(u.getId()).orElseThrow();
        assertFalse(p.getIsOnDuty(), "the psychologist goes on duty themselves (UX-2)");
        assertFalse(p.getIsVerified(), "credentials are verified by a separate administrator action");
        assertNull(p.getSpecialization(), "filled in later by the psychologist (V16)");
        assertNull(p.getRating());
        assertTrue(psychologistRepository.findByIsVerifiedTrueAndIsOnDutyTrue().stream()
                .noneMatch(x -> x.getId().equals(p.getId())), "not in the crisis-routing pool after approval alone");
    }

    @Test
    void verifyingCredentialsSetsTheFlagsAuditsAndEntersTheRoutingPoolOnlyOnceOnDuty() {
        User admin = newUser(UserRole.ADMIN, "verify-actor@example.test");
        User u = pending(UserRole.PSYCHOLOGIST, "verify-psy@example.test");
        approvalService.approve(u.getId());
        em.flush(); em.clear();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getEmail(), null, List.of()));
        try {
            var result = approvalService.setPsychologistVerification(u.getId(), true);
            assertTrue(result.verified());
            assertFalse(result.onDuty());
            em.flush(); em.clear();
        } finally {
            SecurityContextHolder.clearContext();
        }

        Psychologist p = psychologistRepository.findByUserId(u.getId()).orElseThrow();
        assertTrue(p.getIsVerified());
        assertEquals(admin.getId(), p.getVerifiedBy());
        assertTrue(p.getVerifiedAt() != null, "verified_at is written");
        assertTrue(psychologistRepository.findByIsVerifiedTrueAndIsOnDutyTrue().stream()
                .noneMatch(x -> x.getId().equals(p.getId())), "verified but off duty: still not routed");

        Object[] row = (Object[]) em.getEntityManager()
                .createNativeQuery("SELECT user_id, action, CAST(details AS text) FROM activity_logs "
                        + "WHERE entity_type = 'USER' AND entity_id = :id AND action LIKE 'PSYCHOLOGIST_%'")
                .setParameter("id", u.getId()).getSingleResult();
        assertEquals(admin.getId(), ((Number) row[0]).longValue());
        assertEquals("PSYCHOLOGIST_VERIFIED", row[1]);
        assertTrue(String.valueOf(row[2]).contains(String.valueOf(p.getId())), "details name the profile: " + row[2]);

        p.setIsOnDuty(true);
        psychologistRepository.save(p);
        em.flush(); em.clear();
        assertTrue(psychologistRepository.findByIsVerifiedTrueAndIsOnDutyTrue().stream()
                .anyMatch(x -> x.getId().equals(p.getId())), "verified and on duty: in the crisis-routing pool");

        // repeating the same state is a no-op and writes no second audit row
        approvalService.setPsychologistVerification(u.getId(), true);
        em.flush(); em.clear();
        assertEquals(1L, ((Number) em.getEntityManager()
                .createNativeQuery("SELECT count(*) FROM activity_logs WHERE entity_id = :id AND action LIKE 'PSYCHOLOGIST_%'")
                .setParameter("id", u.getId()).getSingleResult()).longValue());

        // revoking clears the stamp and leaves the pool
        var revoked = approvalService.setPsychologistVerification(u.getId(), false);
        em.flush(); em.clear();
        assertFalse(revoked.verified());
        Psychologist after = psychologistRepository.findByUserId(u.getId()).orElseThrow();
        assertNull(after.getVerifiedAt());
        assertNull(after.getVerifiedBy());
        assertTrue(after.getIsOnDuty(), "revoking verification does not touch the duty flag");
        assertTrue(psychologistRepository.findByIsVerifiedTrueAndIsOnDutyTrue().stream()
                .noneMatch(x -> x.getId().equals(after.getId())));
    }

    @Test
    void credentialVerificationIsRefusedForOtherRolesAndBeforeApproval() {
        User volunteer = newUser(UserRole.VOLUNTEER, "verify-vol@example.test");
        assertThrows(BusinessException.class, () -> approvalService.setPsychologistVerification(volunteer.getId(), true));

        User notYetApproved = pending(UserRole.PSYCHOLOGIST, "verify-pending@example.test");
        assertThrows(BusinessException.class, () -> approvalService.setPsychologistVerification(notYetApproved.getId(), true),
                "no profile row exists before approval");
        assertFalse(psychologistRepository.findByUserId(notYetApproved.getId()).isPresent());
    }

    @Test
    void approvingAnOrganizationCreatesItsProfileWithoutRegistrationNumber() {
        User u = pending(UserRole.ORGANIZATION, "approve-org@example.test");
        u.setFullName("Approved Aid Org");
        em.persistAndFlush(u);

        approvalService.approve(u.getId());
        em.flush(); em.clear();

        Organization o = organizationRepository.findByUserId(u.getId()).orElseThrow();
        assertEquals("Approved Aid Org", o.getOfficialName());
        assertNull(o.getRegistrationNumber());
    }

    @Test
    void approvingTwiceDoesNotCreateASecondProfile() {
        User u = pending(UserRole.VOLUNTEER, "approve-twice@example.test");

        approvalService.approve(u.getId());
        em.flush();
        approvalService.approve(u.getId());
        em.flush(); em.clear();

        assertEquals(1L, em.getEntityManager()
                .createNativeQuery("SELECT count(*) FROM volunteers WHERE user_id = :id")
                .setParameter("id", u.getId()).getSingleResult());
    }

    @Test
    void rejectingAPendingApplicationRemovesItAndAnActiveAccountCannotBeRejected() {
        User pendingUser = pending(UserRole.VOLUNTEER, "reject-me@example.test");
        User active = newUser(UserRole.VOLUNTEER, "already-active@example.test");

        approvalService.reject(pendingUser.getId());
        em.flush(); em.clear();
        assertTrue(userRepository.findById(pendingUser.getId()).isEmpty());

        assertThrows(BusinessException.class, () -> approvalService.reject(active.getId()));
    }
}
