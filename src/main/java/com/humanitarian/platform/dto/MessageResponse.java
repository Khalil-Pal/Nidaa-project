package com.humanitarian.platform.dto;

import com.humanitarian.platform.model.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {

    private Long id;
    private Long authorId;
    private String authorName;
    private UserRole authorRole;
    private String content;
    private String communityCategory;
    private LocalDateTime sentAt;

    // CM-1: server-side engagement, so every browser shows the same numbers
    private long likeCount;
    private long commentCount;
    private boolean likedByMe;
}
