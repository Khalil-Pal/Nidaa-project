package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.CommentDto;
import com.humanitarian.platform.dto.CommentResponse;
import com.humanitarian.platform.dto.MessageDeletionResponse;
import com.humanitarian.platform.dto.ReactionResponse;
import com.humanitarian.platform.service.CommunityEngagementService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Likes and comments on community posts (CM-1): the same role gate as the
 * feed at the class level, the moderator's delete narrowed to ADMIN.
 */
@RestController
@RequestMapping("/api/community/messages")
@PreAuthorize("hasAnyRole('VOLUNTEER', 'PSYCHOLOGIST', 'ORGANIZATION', 'ADMIN')")
public class CommunityEngagementController {

    private final CommunityEngagementService engagement;

    public CommunityEngagementController(CommunityEngagementService engagement) {
        this.engagement = engagement;
    }

    @PostMapping("/{messageId}/like")
    public ResponseEntity<ApiResponse<ReactionResponse>> like(@PathVariable Long messageId) {
        return ResponseEntity.ok(ApiResponse.success("Liked", engagement.like(messageId)));
    }

    @DeleteMapping("/{messageId}/like")
    public ResponseEntity<ApiResponse<ReactionResponse>> unlike(@PathVariable Long messageId) {
        return ResponseEntity.ok(ApiResponse.success("Like removed", engagement.unlike(messageId)));
    }

    @GetMapping("/{messageId}/comments")
    public ResponseEntity<ApiResponse<Page<CommentResponse>>> listComments(
            @PathVariable Long messageId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success("Comments retrieved",
                engagement.listComments(messageId, page, size)));
    }

    @PostMapping("/{messageId}/comments")
    public ResponseEntity<ApiResponse<CommentResponse>> addComment(
            @PathVariable Long messageId, @Valid @RequestBody CommentDto body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Comment added",
                engagement.addComment(messageId, body.getContent())));
    }

    @DeleteMapping("/{messageId}/comments/{commentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MessageDeletionResponse>> deleteComment(
            @PathVariable Long messageId,
            @PathVariable Long commentId,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.success("Comment deleted",
                engagement.deleteComment(messageId, commentId, reason)));
    }
}
