package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.MessageDeletionResponse;
import com.humanitarian.platform.dto.MessageDto;
import com.humanitarian.platform.dto.MessageResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Message;
import com.humanitarian.platform.model.MessageDeletion;
import com.humanitarian.platform.model.MessageType;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.MessageDeletionRepository;
import com.humanitarian.platform.repository.MessageRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.util.CommunityCategoryNormalizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MessageService {

    private static final Set<UserRole> COMMUNITY_ROLES = Set.of(
            UserRole.VOLUNTEER,
            UserRole.PSYCHOLOGIST,
            UserRole.ORGANIZATION,
            UserRole.ADMIN
    );

    private final MessageRepository messageRepository;
    private final MessageDeletionRepository messageDeletionRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    public MessageService(MessageRepository messageRepository,
                          MessageDeletionRepository messageDeletionRepository,
                          UserRepository userRepository,
                          UserService userService) {
        this.messageRepository = messageRepository;
        this.messageDeletionRepository = messageDeletionRepository;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Transactional
    public MessageResponse createMessage(MessageDto request) {
        User author = requireCommunityUser();
        String content = requireContent(request == null ? null : request.getContent());
        String category = CommunityCategoryNormalizer.normalize(
                request == null ? null : request.getCommunityCategory());

        Message message = Message.builder()
                .senderId(author.getId())
                .receiverId(null)
                .messageType(MessageType.COMMUNITY)
                .communityCategory(category)
                .content(content)
                .isRead(false)
                .isEncrypted(false)
                .isDeleted(false)
                .build();

        return toMessageResponse(messageRepository.save(message), author);
    }

    @Transactional(readOnly = true)
    public Page<MessageResponse> listMessages(int page, int size) {
        requireCommunityUser();
        PageRequest pageable = pageRequest(page, size);
        Page<Message> result = messageRepository.findVisibleCommunityMessages(pageable);
        Map<Long, User> authors = usersById(result.getContent().stream()
                .map(Message::getSenderId)
                .collect(Collectors.toSet()));

        List<MessageResponse> visibleMessages = result.getContent().stream()
                .filter(this::isVisibleCommunityMessage)
                .map(message -> toMessageResponse(message, authors.get(message.getSenderId())))
                .toList();

        long excludedCount = result.getNumberOfElements() - visibleMessages.size();
        long visibleTotal = Math.max(visibleMessages.size(), result.getTotalElements() - excludedCount);
        return new PageImpl<>(visibleMessages, pageable, visibleTotal);
    }

    @Transactional
    public MessageDeletionResponse deleteMessage(Long messageId, String reason) {
        User admin = requireAdmin();
        String normalizedReason = requireDeletionReason(reason);
        Message message = messageRepository.findVisibleCommunityMessageById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Community message not found with id: " + messageId));

        message.setIsDeleted(true);
        messageRepository.save(message);

        MessageDeletion deletion = MessageDeletion.builder()
                .messageId(message.getId())
                .deletedByAdminId(admin.getId())
                .originalAuthorId(message.getSenderId())
                .reason(normalizedReason)
                .originalContent(message.getContent())
                .build();
        MessageDeletion savedDeletion = messageDeletionRepository.save(deletion);
        User originalAuthor = userRepository.findById(message.getSenderId()).orElse(null);

        return toDeletionResponse(savedDeletion, originalAuthor, admin);
    }

    @Transactional(readOnly = true)
    public Page<MessageDeletionResponse> listDeletions(int page, int size) {
        requireAdmin();
        PageRequest pageable = pageRequest(page, size);
        Page<MessageDeletion> result =
                messageDeletionRepository.findAllByOrderByDeletedAtDescIdDesc(pageable);
        Set<Long> userIds = result.getContent().stream()
                .flatMap(deletion -> java.util.stream.Stream.of(
                        deletion.getOriginalAuthorId(), deletion.getDeletedByAdminId()))
                .collect(Collectors.toSet());
        Map<Long, User> users = usersById(userIds);

        return result.map(deletion -> toDeletionResponse(
                deletion,
                users.get(deletion.getOriginalAuthorId()),
                users.get(deletion.getDeletedByAdminId())));
    }

    private User requireCommunityUser() {
        User currentUser = userService.getCurrentUser();
        if (currentUser.getRole() == null || !COMMUNITY_ROLES.contains(currentUser.getRole())) {
            throw new UnauthorizedException(
                    "Only volunteers, psychologists, organizations, and administrators " +
                            "can access the community feed.");
        }
        return currentUser;
    }

    private User requireAdmin() {
        User currentUser = userService.getCurrentUser();
        if (currentUser.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedException("Only administrators can moderate community messages.");
        }
        return currentUser;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException("Message content is required.");
        }
        String normalized = content.trim();
        if (normalized.length() > 1000) {
            throw new BusinessException("Message content must not exceed 1000 characters.");
        }
        return normalized;
    }

    private String requireDeletionReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("A deletion reason is required.");
        }
        return reason.trim();
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0) {
            throw new BusinessException("Page number must be zero or greater.");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("Page size must be between 1 and 100.");
        }
        return PageRequest.of(page, size);
    }

    private boolean isVisibleCommunityMessage(Message message) {
        return message.getMessageType() == MessageType.COMMUNITY
                && !Boolean.TRUE.equals(message.getIsDeleted());
    }

    private Map<Long, User> usersById(Set<Long> userIds) {
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private MessageResponse toMessageResponse(Message message, User author) {
        return MessageResponse.builder()
                .id(message.getId())
                .authorId(message.getSenderId())
                .authorName(author == null ? "Unknown user" : author.getFullName())
                .authorRole(author == null ? null : author.getRole())
                .content(message.getContent())
                .communityCategory(message.getCommunityCategory())
                .sentAt(message.getSentAt())
                .build();
    }

    private MessageDeletionResponse toDeletionResponse(MessageDeletion deletion,
                                                        User originalAuthor,
                                                        User admin) {
        return MessageDeletionResponse.builder()
                .id(deletion.getId())
                .messageId(deletion.getMessageId())
                .originalAuthorId(deletion.getOriginalAuthorId())
                .originalAuthorName(
                        originalAuthor == null ? "Unknown user" : originalAuthor.getFullName())
                .deletedByAdminId(deletion.getDeletedByAdminId())
                .deletedByAdminName(admin == null ? "Unknown administrator" : admin.getFullName())
                .reason(deletion.getReason())
                .originalContent(deletion.getOriginalContent())
                .deletedAt(deletion.getDeletedAt())
                .build();
    }
}
