package com.humanitarian.platform.dto;

/** The state of a post's likes after the caller's like or unlike (CM-1). */
public record ReactionResponse(Long messageId, boolean likedByMe, long likeCount) {
}
