package com.humanitarian.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDeletionResponse {

    private Long id;
    private Long messageId;
    private Long originalAuthorId;
    private String originalAuthorName;
    private Long deletedByAdminId;
    private String deletedByAdminName;
    private String reason;
    private String originalContent;
    private LocalDateTime deletedAt;
}
