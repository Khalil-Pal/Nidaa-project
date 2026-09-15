package com.humanitarian.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.humanitarian.platform.dto.NotificationDto;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.service.NotificationService;
import com.humanitarian.platform.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;

/**
 * N-1 against the real schema: the enum columns accept what the service writes,
 * reads are owner-scoped (a foreign id is not found), and "read all" touches
 * only the caller's rows.
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
@Import(NotificationService.class)
class NotificationPersistenceTest extends PersistenceTestSupport {

    @Autowired private NotificationService service;
    @MockBean private UserService userService;

    @Test
    void createStoresASentInAppRowAndTheOwnerSeesItNewestFirst() {
        User bene = newUser(UserRole.BENEFICIARY, "notif-owner@example.test");
        service.notify(bene.getId(), "First", "first content", NotificationService.REF_HELP_REQUEST, 10L);
        service.notify(bene.getId(), "Second", "second content", NotificationService.REF_PSYCHOLOGICAL_REQUEST, 11L);
        em.flush(); em.clear();

        Object[] row = (Object[]) em.getEntityManager()
                .createNativeQuery("SELECT CAST(type AS text), CAST(status AS text), sent_at IS NOT NULL, read_at IS NULL FROM notifications WHERE user_id = :id AND title = 'First'")
                .setParameter("id", bene.getId()).getSingleResult();
        assertEquals("IN_APP", row[0]);
        assertEquals("SENT", row[1]);
        assertEquals(Boolean.TRUE, row[2]);
        assertEquals(Boolean.TRUE, row[3]);

        when(userService.getCurrentUser()).thenReturn(bene);
        Page<NotificationDto> page = service.listMine(0, 20);
        assertEquals(2, page.getTotalElements());
        assertEquals("Second", page.getContent().get(0).title(), "newest first");
        assertFalse(page.getContent().get(0).read());
        assertEquals(2L, service.unreadCount());
    }

    @Test
    void aNullRecipientIsANoOpAndOtherChannelsAreRefused() {
        service.notify(null, "Nobody", "x", NotificationService.REF_USER, 1L);
        em.flush();
        assertEquals(0L, ((Number) em.getEntityManager()
                .createNativeQuery("SELECT count(*) FROM notifications WHERE title = 'Nobody'").getSingleResult()).longValue());

        User u = newUser(UserRole.VOLUNTEER, "notif-email@example.test");
        assertThrows(IllegalArgumentException.class,
                () -> service.create(u.getId(), "EMAIL", "t", "c", NotificationService.REF_USER, u.getId()));
    }

    @Test
    void markReadIsOwnerScopedAndReadAllTouchesOnlyTheCallersRows() {
        User owner = newUser(UserRole.BENEFICIARY, "notif-a@example.test");
        User other = newUser(UserRole.BENEFICIARY, "notif-b@example.test");
        var mine = service.create(owner.getId(), NotificationService.IN_APP, "Mine", "c", NotificationService.REF_HELP_REQUEST, 1L);
        service.notify(owner.getId(), "Mine too", "c", NotificationService.REF_HELP_REQUEST, 2L);
        var theirs = service.create(other.getId(), NotificationService.IN_APP, "Theirs", "c", NotificationService.REF_HELP_REQUEST, 3L);
        em.flush(); em.clear();

        when(userService.getCurrentUser()).thenReturn(owner);
        assertThrows(ResourceNotFoundException.class, () -> service.markRead(theirs.getId()),
                "another person's notification is not found, not forbidden");

        NotificationDto read = service.markRead(mine.getId());
        assertTrue(read.read());
        assertNotNull(read.readAt());
        em.flush(); em.clear();
        assertEquals("READ", em.getEntityManager()
                .createNativeQuery("SELECT CAST(status AS text) FROM notifications WHERE notification_id = :id")
                .setParameter("id", mine.getId()).getSingleResult());
        assertEquals(1L, service.unreadCount());

        // repeating is a no-op that keeps the first read_at
        NotificationDto again = service.markRead(mine.getId());
        // PostgreSQL keeps microseconds; the first DTO still carries Java's nanoseconds
        assertEquals(read.readAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                again.readAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));

        assertEquals(1, service.markAllRead(), "only the remaining unread row of the caller");
        assertEquals(0L, service.unreadCount());
        when(userService.getCurrentUser()).thenReturn(other);
        assertEquals(1L, service.unreadCount(), "the other person's row was not touched");
    }
}
