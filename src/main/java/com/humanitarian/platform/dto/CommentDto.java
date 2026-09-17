package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body of POST /api/community/messages/{id}/comments (CM-1): the same 1000-character cap as a post. */
@Data
public class CommentDto {

    @NotBlank(message = "Comment content is required")
    @Size(max = 1000, message = "Comment content must not exceed 1000 characters")
    private String content;
}
