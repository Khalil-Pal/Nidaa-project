package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.MessageDeletionResponse;
import com.humanitarian.platform.dto.MessageDto;
import com.humanitarian.platform.dto.MessageResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Message;
import com.humanitarian.platform.model.MessageDeletion;
import com.humanitarian.platform.model.MessageType;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.MessageDeletionRepository;
import com.humanitarian.platform.repository.MessageRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private MessageDeletionRepository messageDeletionRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserService userService;

    @InjectMocks private MessageService service;

    @Test
    void createMessageExplicitlyCreatesCommunityMessageWithoutReceiver() {
        User admin = user(1L, "Admin User", UserRole.ADMIN);
        MessageDto request = messageDto("  Service update  ", "success-stories");
        when(userService.getCurrentUser()).thenReturn(admin);
        when(messageRepository.save(any(Message.class)))
                .thenAnswer(invocation -> {
                    Message saved = invocation.getArgument(0);
                    saved.setId(50L);
                    return saved;
                });

        MessageResponse response = service.createMessage(request);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        Message saved = captor.getValue();
        assertEquals(MessageType.COMMUNITY, saved.getMessageType());
        assertNull(saved.getReceiverId());
        assertEquals("SUCCESS_STORIES", saved.getCommunityCategory());
        assertEquals("Service update", saved.getContent());
        assertEquals(50L, response.getId());
    }

    @Test
    void beneficiaryCannotListMessages() {
        when(userService.getCurrentUser())
                .thenReturn(user(2L, "Beneficiary", UserRole.BENEFICIARY));

        assertThrows(UnauthorizedException.class, () -> service.listMessages(0, 20));

        verify(messageRepository, never()).findVisibleCommunityMessages(any());
    }

    @Test
    void beneficiaryCannotCreateMessage() {
        when(userService.getCurrentUser())
                .thenReturn(user(2L, "Beneficiary", UserRole.BENEFICIARY));

        assertThrows(UnauthorizedException.class,
                () -> service.createMessage(messageDto("Hello", "UPDATE")));

        verify(messageRepository, never()).save(any());
    }

    @Test
    void nonAdminCannotDeleteMessage() {
        when(userService.getCurrentUser())
                .thenReturn(user(3L, "Volunteer", UserRole.VOLUNTEER));

        assertThrows(UnauthorizedException.class,
                () -> service.deleteMessage(10L, "Policy violation"));

        verify(messageRepository, never()).findVisibleCommunityMessageById(any());
        verify(messageDeletionRepository, never()).save(any());
    }

    @Test
    void adminCannotDeleteWithoutReason() {
        when(userService.getCurrentUser()).thenReturn(user(1L, "Admin", UserRole.ADMIN));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.deleteMessage(10L, "   "));

        assertEquals("A deletion reason is required.", exception.getMessage());
        verify(messageRepository, never()).findVisibleCommunityMessageById(any());
        verify(messageDeletionRepository, never()).save(any());
    }

    @Test
    void adminDeleteSoftDeletesMessageAndCreatesAuditSnapshot() {
        User admin = user(1L, "Admin", UserRole.ADMIN);
        User author = user(4L, "Original Author", UserRole.PSYCHOLOGIST);
        Message message = communityMessage(10L, 4L, "Original content", false);
        when(userService.getCurrentUser()).thenReturn(admin);
        when(messageRepository.findVisibleCommunityMessageById(10L))
                .thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenReturn(message);
        when(messageDeletionRepository.save(any(MessageDeletion.class)))
                .thenAnswer(invocation -> {
                    MessageDeletion saved = invocation.getArgument(0);
                    saved.setId(80L);
                    saved.setDeletedAt(LocalDateTime.of(2026, 8, 4, 12, 0));
                    return saved;
                });
        when(userRepository.findById(4L)).thenReturn(Optional.of(author));

        MessageDeletionResponse response =
                service.deleteMessage(10L, "  Contains private information  ");

        assertTrue(message.getIsDeleted());
        verify(messageRepository).save(message);
        ArgumentCaptor<MessageDeletion> captor = ArgumentCaptor.forClass(MessageDeletion.class);
        verify(messageDeletionRepository).save(captor.capture());
        MessageDeletion audit = captor.getValue();
        assertEquals(10L, audit.getMessageId());
        assertEquals(1L, audit.getDeletedByAdminId());
        assertEquals(4L, audit.getOriginalAuthorId());
        assertEquals("Contains private information", audit.getReason());
        assertEquals("Original content", audit.getOriginalContent());
        assertEquals("Original Author", response.getOriginalAuthorName());
    }

    @Test
    void communityListingNeverReturnsDirectOrDeletedMessages() {
        User volunteer = user(3L, "Volunteer", UserRole.VOLUNTEER);
        Message direct = Message.builder()
                .id(1L)
                .senderId(3L)
                .receiverId(4L)
                .messageType(MessageType.DIRECT)
                .content("Private")
                .isDeleted(false)
                .build();
        Message visible = communityMessage(2L, 3L, "Shared", false);
        Message deleted = communityMessage(3L, 3L, "Removed", true);
        PageRequest pageable = PageRequest.of(0, 20);
        when(userService.getCurrentUser()).thenReturn(volunteer);
        when(messageRepository.findVisibleCommunityMessages(pageable))
                .thenReturn(new PageImpl<>(List.of(direct, visible, deleted), pageable, 3));
        when(userRepository.findAllById(any())).thenReturn(List.of(volunteer));

        Page<MessageResponse> result = service.listMessages(0, 20);

        assertEquals(1, result.getContent().size());
        assertEquals(2L, result.getContent().get(0).getId());
        assertFalse(result.getContent().stream().anyMatch(item -> item.getId().equals(1L)));
        assertFalse(result.getContent().stream().anyMatch(item -> item.getId().equals(3L)));
    }

    @Test
    void deletedMessageRemainsAvailableInAdminAuditLog() {
        User admin = user(1L, "Admin", UserRole.ADMIN);
        User author = user(4L, "Original Author", UserRole.ORGANIZATION);
        MessageDeletion deletion = MessageDeletion.builder()
                .id(80L)
                .messageId(10L)
                .deletedByAdminId(1L)
                .originalAuthorId(4L)
                .reason("Spam")
                .originalContent("Removed content")
                .deletedAt(LocalDateTime.of(2026, 8, 4, 12, 0))
                .build();
        PageRequest pageable = PageRequest.of(0, 20);
        when(userService.getCurrentUser()).thenReturn(admin);
        when(messageDeletionRepository.findAllByOrderByDeletedAtDescIdDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(deletion), pageable, 1));
        when(userRepository.findAllById(any())).thenReturn(List.of(admin, author));

        Page<MessageDeletionResponse> result = service.listDeletions(0, 20);

        assertEquals(1, result.getTotalElements());
        assertEquals("Removed content", result.getContent().get(0).getOriginalContent());
        assertEquals("Spam", result.getContent().get(0).getReason());
        assertEquals("Original Author", result.getContent().get(0).getOriginalAuthorName());
        assertEquals("Admin", result.getContent().get(0).getDeletedByAdminName());
    }

    private MessageDto messageDto(String content, String category) {
        MessageDto request = new MessageDto();
        request.setContent(content);
        request.setCommunityCategory(category);
        return request;
    }

    private User user(Long id, String fullName, UserRole role) {
        return User.builder().id(id).fullName(fullName).role(role).build();
    }

    private Message communityMessage(Long id, Long authorId, String content, boolean deleted) {
        return Message.builder()
                .id(id)
                .senderId(authorId)
                .receiverId(null)
                .messageType(MessageType.COMMUNITY)
                .communityCategory("UPDATE")
                .content(content)
                .isDeleted(deleted)
                .build();
    }
}
