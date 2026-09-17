package com.humanitarian.platform.dto;

import com.humanitarian.platform.model.UserRole;
import java.time.LocalDateTime;

/** A comment on a community post as the feed shows it (CM-1). */
public record CommentResponse(Long id,
                              Long messageId,
                              Long authorId,
                              String authorName,
                              UserRole authorRole,
                              String content,
                              LocalDateTime createdAt) {
}
