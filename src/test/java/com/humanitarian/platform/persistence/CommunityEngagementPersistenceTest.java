package com.humanitarian.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.humanitarian.platform.model.Message;
import com.humanitarian.platform.model.MessageComment;
import com.humanitarian.platform.model.MessageDeletion;
import com.humanitarian.platform.model.MessageReaction;
import com.humanitarian.platform.model.MessageType;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.MessageCommentRepository;
import com.humanitarian.platform.repository.MessageDeletionRepository;
import com.humanitarian.platform.repository.MessageReactionRepository;
import com.humanitarian.platform.service.CommunityEngagementService;
import com.humanitarian.platform.service.NotificationService;
import com.humanitarian.platform.service.UserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * CM-1 against the real schema (V21): the (post, user) primary key refuses a
 * second like, the grouped counts leave out removed comments, unlike deletes
 * exactly one row, and a comment's moderation audit row references the comment.
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
@Import(CommunityEngagementService.class)
class CommunityEngagementPersistenceTest extends PersistenceTestSupport {

    @Autowired private CommunityEngagementService engagement;
    @Autowired private MessageReactionRepository reactions;
    @Autowired private MessageCommentRepository comments;
    @Autowired private MessageDeletionRepository deletions;
    @MockBean private UserService userService;
    @MockBean private NotificationService notifications;

    private Message newPost(User author, String content) {
        return em.persistAndFlush(Message.builder().senderId(author.getId()).messageType(MessageType.COMMUNITY)
                .communityCategory("UPDATE").content(content).isRead(false).isEncrypted(false).isDeleted(false).build());
    }

    @Test
    void onePersonLikesAPostOnceAndUnlikeRemovesExactlyThatRow() {
        User a = newUser(UserRole.VOLUNTEER, "cm-like-a@example.test");
        User b = newUser(UserRole.PSYCHOLOGIST, "cm-like-b@example.test");
        Message post = newPost(a, "hello");
        reactions.saveAndFlush(MessageReaction.builder().messageId(post.getId()).userId(a.getId()).build());
        reactions.saveAndFlush(MessageReaction.builder().messageId(post.getId()).userId(b.getId()).build());
        em.clear();

        assertEquals(2, reactions.countByMessageId(post.getId()));
        assertEquals(1, reactions.deleteByMessageIdAndUserId(post.getId(), a.getId()));
        assertEquals(0, reactions.deleteByMessageIdAndUserId(post.getId(), a.getId()), "nothing left to remove");
        assertEquals(1, reactions.countByMessageId(post.getId()));
        assertEquals(List.of(post.getId()), reactions.likedMessageIds(b.getId(), List.of(post.getId())));
        assertTrue(reactions.likedMessageIds(a.getId(), List.of(post.getId())).isEmpty());

        // last, because the violation aborts the transaction: b liking again is refused by the key
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () ->
                reactions.saveAndFlush(MessageReaction.builder().messageId(post.getId()).userId(b.getId()).build()));
        assertTrue(String.valueOf(ex.getMostSpecificCause().getMessage()).contains("pk_message_reactions"),
                "the V21 key is what refused it: " + ex.getMostSpecificCause().getMessage());
    }

    @Test
    void summaryCountsLikesAndVisibleCommentsPerPostInGroupedQueries() {
        User a = newUser(UserRole.VOLUNTEER, "cm-sum-a@example.test");
        User b = newUser(UserRole.ORGANIZATION, "cm-sum-b@example.test");
        Message liked = newPost(a, "liked and discussed");
        Message quiet = newPost(a, "nothing yet");
        reactions.saveAndFlush(MessageReaction.builder().messageId(liked.getId()).userId(a.getId()).build());
        reactions.saveAndFlush(MessageReaction.builder().messageId(liked.getId()).userId(b.getId()).build());
        comments.saveAndFlush(MessageComment.builder().messageId(liked.getId()).authorId(b.getId()).content("one").isDeleted(false).build());
        comments.saveAndFlush(MessageComment.builder().messageId(liked.getId()).authorId(b.getId()).content("two").isDeleted(false).build());
        comments.saveAndFlush(MessageComment.builder().messageId(liked.getId()).authorId(a.getId()).content("removed").isDeleted(true).build());
        em.clear();

        Map<Long, CommunityEngagementService.Engagement> summary =
                engagement.summarize(List.of(liked.getId(), quiet.getId()), b.getId());
        assertEquals(new CommunityEngagementService.Engagement(2, 2, true), summary.get(liked.getId()), "the removed comment is not counted");
        assertEquals(new CommunityEngagementService.Engagement(0, 0, false), summary.get(quiet.getId()));
        assertTrue(engagement.summarize(List.of(liked.getId()), a.getId()).get(liked.getId()).likedByMe(), "a liked it too");
    }

    @Test
    void aModeratedCommentIsSoftDeletedAndAuditedWithItsId() {
        User author = newUser(UserRole.VOLUNTEER, "cm-mod-author@example.test");
        User admin = newUser(UserRole.ADMIN, "cm-mod-admin@example.test");
        Message post = newPost(author, "post");
        MessageComment comment = comments.saveAndFlush(MessageComment.builder().messageId(post.getId())
                .authorId(author.getId()).content("uncalled for").isDeleted(false).build());
        when(userService.getCurrentUser()).thenReturn(admin);

        engagement.deleteComment(post.getId(), comment.getId(), "Harassment");
        em.flush(); em.clear();

        assertTrue(em.find(MessageComment.class, comment.getId()).getIsDeleted(), "row kept, hidden");
        assertEquals(0, comments.countByMessageIdAndIsDeletedFalse(post.getId()));
        MessageDeletion audit = deletions.findAll().stream()
                .filter(d -> comment.getId().equals(d.getCommentId())).findFirst().orElseThrow();
        assertEquals(post.getId(), audit.getMessageId());
        assertEquals("uncalled for", audit.getOriginalContent());
        assertEquals(admin.getId(), audit.getDeletedByAdminId());

        // the FK: an audit row cannot point at a comment that does not exist
        assertThrows(DataIntegrityViolationException.class, () -> deletions.saveAndFlush(MessageDeletion.builder()
                .messageId(post.getId()).commentId(999_999L).deletedByAdminId(admin.getId())
                .originalAuthorId(author.getId()).reason("x").originalContent("x").build()));
    }
}
