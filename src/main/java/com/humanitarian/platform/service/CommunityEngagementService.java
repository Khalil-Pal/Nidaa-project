package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.CommentResponse;
import com.humanitarian.platform.dto.MessageDeletionResponse;
import com.humanitarian.platform.dto.ReactionResponse;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.model.Message;
import com.humanitarian.platform.model.MessageComment;
import com.humanitarian.platform.model.MessageDeletion;
import com.humanitarian.platform.model.MessageReaction;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.MessageCommentRepository;
import com.humanitarian.platform.repository.MessageDeletionRepository;
import com.humanitarian.platform.repository.MessageReactionRepository;
import com.humanitarian.platform.repository.MessageRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.util.CommunityRules;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Likes and comments on community posts (CM-1), stored on the server so every
 * browser shows the same numbers. Same role gate as the feed, same
 * 1000-character cap, same moderator soft-delete-with-audit as
 * {@link MessageService#deleteMessage}: a removed comment stays in the table
 * with {@code is_deleted}, gets a {@code message_deletions} row with
 * {@code comment_id}, and its author is told the reason.
 *
 * Like and unlike are idempotent: liking twice leaves one like (the primary key
 * on the (post, user) pair refuses the second row when two clicks race), and
 * unliking what was never liked is not an error. Both answer with the post's
 * current state so the page can render it without a second call.
 */
@Service
public class CommunityEngagementService {

    private static final Logger log = LoggerFactory.getLogger(CommunityEngagementService.class);

    /** A post's engagement as the feed shows it. */
    public record Engagement(long likes, long comments, boolean likedByMe) {
        static final Engagement NONE = new Engagement(0, 0, false);
    }

    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final MessageCommentRepository commentRepository;
    private final MessageDeletionRepository messageDeletionRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final NotificationService notifications;

    public CommunityEngagementService(MessageRepository messageRepository,
                                      MessageReactionRepository reactionRepository,
                                      MessageCommentRepository commentRepository,
                                      MessageDeletionRepository messageDeletionRepository,
                                      UserRepository userRepository,
                                      UserService userService,
                                      NotificationService notifications) {
        this.messageRepository = messageRepository;
        this.reactionRepository = reactionRepository;
        this.commentRepository = commentRepository;
        this.messageDeletionRepository = messageDeletionRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.notifications = notifications;
    }

    @Transactional
    public ReactionResponse like(Long messageId) {
        User me = CommunityRules.requireCommunityRole(userService.getCurrentUser());
        Message post = visiblePost(messageId);
        if (!reactionRepository.existsByMessageIdAndUserId(post.getId(), me.getId())) {
            try {
                reactionRepository.saveAndFlush(MessageReaction.builder().messageId(post.getId()).userId(me.getId()).build());
            } catch (DataIntegrityViolationException raceLost) {
                // two clicks at once: pk_message_reactions kept the first one, which is what was wanted
            }
        }
        return new ReactionResponse(post.getId(), true, reactionRepository.countByMessageId(post.getId()));
    }

    @Transactional
    public ReactionResponse unlike(Long messageId) {
        User me = CommunityRules.requireCommunityRole(userService.getCurrentUser());
        Message post = visiblePost(messageId);
        reactionRepository.deleteByMessageIdAndUserId(post.getId(), me.getId());
        return new ReactionResponse(post.getId(), false, reactionRepository.countByMessageId(post.getId()));
    }

    @Transactional(readOnly = true)
    public Page<CommentResponse> listComments(Long messageId, int page, int size) {
        CommunityRules.requireCommunityRole(userService.getCurrentUser());
        Message post = visiblePost(messageId);
        CommunityRules.requirePage(page, size);
        Page<MessageComment> comments = commentRepository
                .findByMessageIdAndIsDeletedFalseOrderByCreatedAtAscIdAsc(post.getId(), PageRequest.of(page, size));
        Map<Long, User> authors = usersById(comments.getContent().stream()
                .map(MessageComment::getAuthorId).collect(Collectors.toSet()));
        return comments.map(c -> toResponse(c, authors.get(c.getAuthorId())));
    }

    @Transactional
    public CommentResponse addComment(Long messageId, String content) {
        User me = CommunityRules.requireCommunityRole(userService.getCurrentUser());
        Message post = visiblePost(messageId);
        MessageComment saved = commentRepository.save(MessageComment.builder()
                .messageId(post.getId())
                .authorId(me.getId())
                .content(CommunityRules.requireContent(content, "Comment"))
                .isDeleted(false)
                .build());
        return toResponse(saved, me);
    }

    @Transactional
    public MessageDeletionResponse deleteComment(Long messageId, Long commentId, String reason) {
        User admin = CommunityRules.requireAdmin(userService.getCurrentUser());
        String normalizedReason = CommunityRules.requireDeletionReason(reason);
        MessageComment comment = commentRepository.findByIdAndMessageIdAndIsDeletedFalse(commentId, messageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Comment not found with id: " + commentId + " on message " + messageId));

        comment.setIsDeleted(true);
        commentRepository.save(comment);

        MessageDeletion savedDeletion = messageDeletionRepository.save(MessageDeletion.builder()
                .messageId(comment.getMessageId())
                .commentId(comment.getId())
                .deletedByAdminId(admin.getId())
                .originalAuthorId(comment.getAuthorId())
                .reason(normalizedReason)
                .originalContent(comment.getContent())
                .build());
        log.info("Comment {} on message {} removed by admin {}", comment.getId(), comment.getMessageId(), admin.getId());
        User originalAuthor = userRepository.findById(comment.getAuthorId()).orElse(null);
        // the author is told, with the moderator's reason, not the moderator's name (as for a post)
        notifications.notify(comment.getAuthorId(), "A moderator removed your comment",
                "Your comment was removed. Reason: " + normalizedReason,
                NotificationService.REF_MESSAGE, comment.getMessageId());

        return MessageDeletionResponse.builder()
                .id(savedDeletion.getId())
                .messageId(savedDeletion.getMessageId())
                .commentId(savedDeletion.getCommentId())
                .originalAuthorId(savedDeletion.getOriginalAuthorId())
                .originalAuthorName(originalAuthor == null ? "Unknown user" : originalAuthor.getFullName())
                .deletedByAdminId(admin.getId())
                .deletedByAdminName(admin.getFullName())
                .reason(savedDeletion.getReason())
                .originalContent(savedDeletion.getOriginalContent())
                .deletedAt(savedDeletion.getDeletedAt())
                .build();
    }

    /**
     * Likes, visible comments and whether the viewer liked each of a page of posts,
     * in three grouped queries rather than three per post. Posts without any
     * engagement map to zeros.
     */
    @Transactional(readOnly = true)
    public Map<Long, Engagement> summarize(Collection<Long> messageIds, Long viewerId) {
        Map<Long, Engagement> result = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return result;
        }
        Map<Long, Long> likes = new HashMap<>();
        reactionRepository.countByMessageIds(messageIds).forEach(c -> likes.put(c.id(), c.count()));
        Map<Long, Long> comments = new HashMap<>();
        commentRepository.countVisibleByMessageIds(messageIds).forEach(c -> comments.put(c.id(), c.count()));
        Set<Long> mine = viewerId == null ? Set.of()
                : new HashSet<>(reactionRepository.likedMessageIds(viewerId, messageIds));
        for (Long id : messageIds) {
            result.put(id, new Engagement(likes.getOrDefault(id, 0L), comments.getOrDefault(id, 0L), mine.contains(id)));
        }
        return result;
    }

    // ---- helpers ----------------------------------------------------------------

    /** A community post that is not deleted; anything else is 404, the feed's own rule. */
    private Message visiblePost(Long messageId) {
        return messageRepository.findVisibleCommunityMessageById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Community message not found with id: " + messageId));
    }

    private Map<Long, User> usersById(Set<Long> userIds) {
        Map<Long, User> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds).forEach(u -> users.put(u.getId(), u));
        }
        return users;
    }

    private static CommentResponse toResponse(MessageComment c, User author) {
        return new CommentResponse(c.getId(), c.getMessageId(), c.getAuthorId(),
                author == null ? "Unknown user" : author.getFullName(),
                author == null ? null : author.getRole(),
                c.getContent(), c.getCreatedAt());
    }
}
