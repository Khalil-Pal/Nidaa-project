package com.humanitarian.platform.security;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.humanitarian.platform.controller.NotificationController;
import com.humanitarian.platform.dto.NotificationDto;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * N-1: notifications are the caller's own. The service scopes every call; the
 * HTTP layer must require a session and surface a foreign id as 404.
 */
@WebMvcTest(NotificationController.class)
class NotificationSecurityTest extends SecuritySliceTest {

    private static NotificationDto dto(long id, boolean read) {
        return new NotificationDto(id, "IN_APP", "Your request was accepted", "Volunteer One accepted \"Need food\".",
                "HELP_REQUEST", 10L, read, LocalDateTime.now(), read ? LocalDateTime.now() : null);
    }

    @Test
    void anonymousGetsNothing() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/notifications/1/read")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        verify(notifications, never()).listMine(anyInt(), anyInt());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void anySignedInRoleListsItsOwnNotificationsInTheEnvelope() throws Exception {
        when(notifications.listMine(0, 20)).thenReturn(new PageImpl<>(List.of(dto(1, false), dto(2, true)), PageRequest.of(0, 20), 2));
        when(notifications.unreadCount()).thenReturn(1L);

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].read").value(false))
                .andExpect(jsonPath("$.data.content[0].referenceType").value("HELP_REQUEST"));
        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(1));
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void someoneElsesNotificationIsNotFoundNotForbidden() throws Exception {
        when(notifications.markRead(77L)).thenThrow(new ResourceNotFoundException("Notification not found"));

        mockMvc.perform(put("/api/notifications/77/read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void markingReadReturnsTheUpdatedNotificationAndReadAllTheCount() throws Exception {
        when(notifications.markRead(1L)).thenReturn(dto(1, true));
        when(notifications.markAllRead()).thenReturn(3);

        mockMvc.perform(put("/api/notifications/1/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));
        mockMvc.perform(put("/api/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(3));
    }
}
