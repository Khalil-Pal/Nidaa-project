package com.humanitarian.platform.persistence;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Message;
import com.humanitarian.platform.model.MessageType;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.UserRepository;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
class UserPersistenceTest extends PersistenceTestSupport {

    @Autowired private UserRepository userRepository;

    @Test
    void registrationWithoutAPhoneNumberPersists() {
        // Before V11 this failed at the database after the person had already
        // entered their verification code.
        User saved = em.persistAndFlush(User.builder()
                .email("nophone@example.test")
                .passwordHash("$2a$10$hash")
                .fullName("No Phone")
                .role(UserRole.BENEFICIARY)
                .build());
        em.clear();

        assertNull(em.find(User.class, saved.getId()).getPhone());
    }

    @Test
    void twoAccountsMayShareAPhoneNumber() {
        // households often share one phone; users_phone_key was dropped in V11
        em.persistAndFlush(User.builder().email("a@example.test").passwordHash("h").fullName("A")
                .phone("+7000000001").role(UserRole.BENEFICIARY).build());
        em.persistAndFlush(User.builder().email("b@example.test").passwordHash("h").fullName("B")
                .phone("+7000000001").role(UserRole.BENEFICIARY).build());
    }

    @Test
    void volunteerEntityDefaultRatingViolatesTheCheckConstraint() {
        // Documents the D-3 defect: the entity defaults rating to 0.0 while the
        // database requires 1..5. D-3 changes the default to null and the CHECK
        // to allow NULL; this test must be updated with it.
        User user = newUser(UserRole.VOLUNTEER, "vol-rating@example.test");
        Volunteer withEntityDefault = Volunteer.builder().user(user).isAvailable(true).build();

        assertEquals(0.0, withEntityDefault.getRating());
        assertThrows(PersistenceException.class, () -> em.persistAndFlush(withEntityDefault));
    }

    @Test
    void volunteerWithARatingInRangePersists() {
        User user = newUser(UserRole.VOLUNTEER, "vol-ok@example.test");
        Volunteer volunteer = em.persistAndFlush(Volunteer.builder().user(user).rating(3.5).isAvailable(true).build());
        em.clear();

        assertEquals(3.5, em.find(Volunteer.class, volunteer.getId()).getRating());
    }

    @Test
    void hardDeletingAUserWithMessagesFailsOnTheForeignKey() {
        // The audit's reproduction: messages.sender_id has no ON DELETE rule.
        User sender = newUser(UserRole.VOLUNTEER, "sender@example.test");
        User receiver = newUser(UserRole.BENEFICIARY, "receiver@example.test");
        em.persistAndFlush(Message.builder()
                .senderId(sender.getId()).receiverId(receiver.getId())
                .messageType(MessageType.DIRECT).content("hello").build());

        assertThrows(PersistenceException.class, () -> {
            em.remove(em.find(User.class, sender.getId()));
            em.flush();
        });
    }

    @Test
    void softDeleteAnonymisesInPlaceAndKeepsDependentRows() {
        User person = newUser(UserRole.BENEFICIARY, "leaving@example.test");
        User other = newUser(UserRole.VOLUNTEER, "staying@example.test");
        Message message = em.persistAndFlush(Message.builder()
                .senderId(person.getId()).receiverId(other.getId())
                .messageType(MessageType.DIRECT).content("kept").build());
        HelpRequest request = newHelpRequest(person.getId(), "FOOD", "HIGH", "COMPLETED");
        Long id = person.getId();
        em.clear();

        int changed = userRepository.softDelete(id, LocalDateTime.now());
        em.clear();

        assertEquals(1, changed);
        User gone = em.find(User.class, id);
        assertEquals("deleted-" + id + "@deleted.invalid", gone.getEmail());
        assertEquals("Deleted user", gone.getFullName());
        assertNull(gone.getPhone());
        assertFalse(gone.getIsActive());
        assertNotNull(gone.getDeletedAt());
        assertNotNull(gone.getTokensValidFrom());
        assertNotNull(em.find(Message.class, message.getId()), "message survives");
        assertEquals(id, em.find(HelpRequest.class, request.getId()).getBeneficiaryId(), "aid history survives");
        assertTrue(userRepository.findByEmail("leaving@example.test").isEmpty(), "old email no longer resolves");
        assertEquals(0, userRepository.softDelete(id, LocalDateTime.now()), "second delete is a no-op");
    }

    @Test
    void approvalShapedProviderRowsPersist() {
        // AdminController.approveUser inserts exactly these columns. Before V16
        // psychologists.specialization and organizations.registration_number were
        // NOT NULL, so approving either role failed on a fresh schema (Gate 3).
        User psy = newUser(UserRole.PSYCHOLOGIST, "approve-psy@example.test");
        User org = newUser(UserRole.ORGANIZATION, "approve-org@example.test");
        org.setFullName("Approved Aid Org");

        em.getEntityManager().createNativeQuery(
                        "INSERT INTO psychologists (user_id, is_on_duty) VALUES (:id, true)")
                .setParameter("id", psy.getId()).executeUpdate();
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO organizations (user_id, official_name, is_verified) VALUES (:id, :name, false)")
                .setParameter("id", org.getId()).setParameter("name", org.getFullName()).executeUpdate();
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO volunteers (user_id, is_available) VALUES (:id, true)")
                .setParameter("id", newUser(UserRole.VOLUNTEER, "approve-vol@example.test").getId()).executeUpdate();

        assertEquals(1L, em.getEntityManager()
                .createNativeQuery("SELECT count(*) FROM psychologists WHERE user_id = :id")
                .setParameter("id", psy.getId()).getSingleResult());
        assertEquals(1L, em.getEntityManager()
                .createNativeQuery("SELECT count(*) FROM organizations WHERE user_id = :id")
                .setParameter("id", org.getId()).getSingleResult());
    }

    @Test
    void filerMayNotBeTheBeneficiary() {
        User volunteer = newUser(UserRole.VOLUNTEER, "filer@example.test");
        HelpRequest selfFiled = HelpRequest.builder()
                .beneficiaryId(volunteer.getId()).filedByUserId(volunteer.getId())
                .title("t").description("d").helpType("FOOD").urgencyLevel("LOW").status("PENDING")
                .build();

        assertThrows(PersistenceException.class, () -> em.persistAndFlush(selfFiled));
    }
}
