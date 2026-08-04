package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.MessageDeletionResponse;
import com.humanitarian.platform.dto.MessageDto;
import com.humanitarian.platform.dto.MessageResponse;
import com.humanitarian.platform.service.MessageService;
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

@RestController
@RequestMapping("/api/community/messages")
@PreAuthorize("hasAnyRole('VOLUNTEER', 'PSYCHOLOGIST', 'ORGANIZATION', 'ADMIN')")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<MessageResponse>>> listMessages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                "Community messages retrieved", messageService.listMessages(page, size)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MessageResponse>> createMessage(
            @Valid @RequestBody MessageDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Community message created", messageService.createMessage(request)));
    }

    @DeleteMapping("/{messageId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MessageDeletionResponse>> deleteMessage(
            @PathVariable Long messageId,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.success(
                "Community message deleted", messageService.deleteMessage(messageId, reason)));
    }
}
