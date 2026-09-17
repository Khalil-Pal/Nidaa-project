package com.humanitarian.platform.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.humanitarian.platform.controller.CommunityEngagementController;
import com.humanitarian.platform.model.Message;
import com.humanitarian.platform.model.MessageComment;
import com.humanitarian.platform.model.MessageDeletion;
import com.humanitarian.platform.model.MessageType;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.MessageCommentRepository;
import com.humanitarian.platform.repository.MessageDeletionRepository;
import com.humanitarian.platform.repository.MessageReactionRepository;
import com.humanitarian.platform.repository.MessageRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.CommunityEngagementService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * CM-1 through the real security config and the real
 * {@link CommunityEngagementService} with repositories mocked: the feed's role
 * gate applies to likes and comments, like and unlike are idempotent and answer
 * with the post's state, a hidden post is 404, a comment is capped like a post,
 * and a moderator's removal of a comment is audited and notified like a post's.
 * The volunteer is user 3, another volunteer user 4, the administrator user 99.
 */
@WebMvcTest(CommunityEngagementController.class)
@Import(CommunityEngagementService.class)
class CommunityEngagementSecurityTest extends SecuritySliceTest {

    @MockBean private MessageRepository messageRepository;
    @MockBean private MessageReactionRepository reactionRepository;
    @MockBean private MessageCommentRepository commentRepository;
    @MockBean private MessageDeletionRepository deletionRepository;
    @MockBean private UserRepository userRepository;

    private static final String COMMENT = "{\"content\":\"Well done, everyone\"}";

    @BeforeEach
    void fixtures() {
        Message post = Message.builder().id(10L).senderId(4L).messageType(MessageType.COMMUNITY)
                .communityCategory("UPDATE").content("Shared").isDeleted(false).build();
        when(messageRepository.findVisibleCommunityMessageById(10L)).thenReturn(Optional.of(post));
        when(messageRepository.findVisibleCommunityMessageById(11L)).thenReturn(Optional.empty());   // deleted or absent
        when(commentRepository.save(any(MessageComment.class))).thenAnswer(inv -> {
            MessageComment c = inv.getArgument(0); c.setId(500L); c.setCreatedAt(LocalDateTime.now()); return c; });
        when(deletionRepository.save(any(MessageDeletion.class))).thenAnswer(inv -> {
            MessageDeletion d = inv.getArgument(0); d.setId(900L); d.setDeletedAt(LocalDateTime.now()); return d; });
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/community/messages/10/like")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/community/messages/10/comments")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryIsOutsideTheCommunityLikeTheFeed() throws Exception {
        actingAs(1L, UserRole.BENEFICIARY);
        mockMvc.perform(post("/api/community/messages/10/like")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/community/messages/10/comments").contentType(MediaType.APPLICATION_JSON).content(COMMENT))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/community/messages/10/comments")).andExpect(status().isForbidden());
        verify(reactionRepository, never()).saveAndFlush(any());
        verify(commentRepository, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void likeIsIdempotentAndUnlikeAnswersWithTheNewCount() throws Exception {
        actingAs(3L, UserRole.VOLUNTEER);
        when(reactionRepository.existsByMessageIdAndUserId(10L, 3L)).thenReturn(false);
        when(reactionRepository.countByMessageId(10L)).thenReturn(1L);
        mockMvc.perform(post("/api/community/messages/10/like")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likedByMe").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));
        verify(reactionRepository).saveAndFlush(any());

        when(reactionRepository.existsByMessageIdAndUserId(10L, 3L)).thenReturn(true);
        mockMvc.perform(post("/api/community/messages/10/like")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likedByMe").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));
        verify(reactionRepository).saveAndFlush(any());   // still once: liking twice leaves one like

        when(reactionRepository.countByMessageId(10L)).thenReturn(0L);
        mockMvc.perform(delete("/api/community/messages/10/like")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likedByMe").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));
        verify(reactionRepository).deleteByMessageIdAndUserId(10L, 3L);
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void aHiddenPostCannotBeLikedOrCommentedOn() throws Exception {
        actingAs(3L, UserRole.VOLUNTEER);
        mockMvc.perform(post("/api/community/messages/11/like")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/community/messages/11/comments").contentType(MediaType.APPLICATION_JSON).content(COMMENT))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/community/messages/11/comments")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void volunteerCommentsAndReadsTheThreadWithAuthorNames() throws Exception {
        actingAs(3L, UserRole.VOLUNTEER);
        mockMvc.perform(post("/api/community/messages/10/comments").contentType(MediaType.APPLICATION_JSON).content(COMMENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(500))
                .andExpect(jsonPath("$.data.messageId").value(10))
                .andExpect(jsonPath("$.data.authorId").value(3))
                .andExpect(jsonPath("$.data.authorName").value("VOLUNTEER 3"))
                .andExpect(jsonPath("$.data.content").value("Well done, everyone"));

        MessageComment stored = MessageComment.builder().id(500L).messageId(10L).authorId(4L).content("Well done, everyone")
                .createdAt(LocalDateTime.now()).isDeleted(false).build();
        when(commentRepository.findByMessageIdAndIsDeletedFalseOrderByCreatedAtAscIdAsc(eq(10L), any()))
                .thenReturn(new PageImpl<>(List.of(stored), PageRequest.of(0, 50), 1));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(4L, UserRole.VOLUNTEER)));
        mockMvc.perform(get("/api/community/messages/10/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].authorName").value("VOLUNTEER 4"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void blankAndOverlongCommentsAreRefusedBeforeTheService() throws Exception {
        actingAs(3L, UserRole.VOLUNTEER);
        mockMvc.perform(post("/api/community/messages/10/comments").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.content").exists());
        String tooLong = "{\"content\":\"" + "x".repeat(1001) + "\"}";
        mockMvc.perform(post("/api/community/messages/10/comments").contentType(MediaType.APPLICATION_JSON).content(tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.content").exists());
        verify(commentRepository, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRemovesACommentWithAReasonAndTheAuthorIsToldLikeAPost() throws Exception {
        actingAs(99L, UserRole.ADMIN);
        MessageComment stored = MessageComment.builder().id(500L).messageId(10L).authorId(4L).content("Rude words")
                .createdAt(LocalDateTime.now()).isDeleted(false).build();
        when(commentRepository.findByIdAndMessageIdAndIsDeletedFalse(500L, 10L)).thenReturn(Optional.of(stored));
        when(userRepository.findById(4L)).thenReturn(Optional.of(user(4L, UserRole.VOLUNTEER)));

        mockMvc.perform(delete("/api/community/messages/10/comments/500")).andExpect(status().isBadRequest());
        verify(commentRepository, never()).save(any());

        mockMvc.perform(delete("/api/community/messages/10/comments/500").param("reason", "Harassment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value(10))
                .andExpect(jsonPath("$.data.commentId").value(500))
                .andExpect(jsonPath("$.data.originalAuthorName").value("VOLUNTEER 4"))
                .andExpect(jsonPath("$.data.reason").value("Harassment"))
                .andExpect(jsonPath("$.data.originalContent").value("Rude words"));
        ArgumentCaptor<MessageComment> saved = ArgumentCaptor.forClass(MessageComment.class);
        verify(commentRepository).save(saved.capture());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getValue().getIsDeleted(), "soft-deleted, not removed");
        ArgumentCaptor<MessageDeletion> audit = ArgumentCaptor.forClass(MessageDeletion.class);
        verify(deletionRepository).save(audit.capture());
        org.junit.jupiter.api.Assertions.assertEquals(500L, audit.getValue().getCommentId());
        org.junit.jupiter.api.Assertions.assertEquals(10L, audit.getValue().getMessageId());
        verify(notifications).notify(eq(4L), eq("A moderator removed your comment"), anyString(), eq("MESSAGE"), eq(10L));

        when(commentRepository.findByIdAndMessageIdAndIsDeletedFalse(500L, 10L)).thenReturn(Optional.empty());
        mockMvc.perform(delete("/api/community/messages/10/comments/500").param("reason", "again")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void onlyAdminsRemoveComments() throws Exception {
        actingAs(3L, UserRole.VOLUNTEER);
        mockMvc.perform(delete("/api/community/messages/10/comments/500").param("reason", "x")).andExpect(status().isForbidden());
        verify(commentRepository, never()).save(any());
    }
}
