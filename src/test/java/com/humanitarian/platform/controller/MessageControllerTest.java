package com.humanitarian.platform.controller;

import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.GlobalExceptionHandler;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Message;
import com.humanitarian.platform.model.MessageType;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.MessageDeletionRepository;
import com.humanitarian.platform.repository.MessageRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.MessageService;
import com.humanitarian.platform.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessageControllerTest {

    @Test
    void getMessagesNeverReturnsDirectMessageEvenIfRepositorySuppliesOne() throws Exception {
        MessageRepository messageRepository = mock(MessageRepository.class);
        MessageDeletionRepository deletionRepository = mock(MessageDeletionRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        UserService userService = mock(UserService.class);
        User volunteer = User.builder()
                .id(3L)
                .fullName("Volunteer")
                .role(UserRole.VOLUNTEER)
                .build();
        Message direct = Message.builder()
                .id(1L)
                .senderId(3L)
                .receiverId(4L)
                .messageType(MessageType.DIRECT)
                .content("Private")
                .isDeleted(false)
                .build();
        Message community = Message.builder()
                .id(2L)
                .senderId(3L)
                .messageType(MessageType.COMMUNITY)
                .communityCategory("UPDATE")
                .content("Shared")
                .isDeleted(false)
                .build();
        PageRequest pageable = PageRequest.of(0, 20);
        when(userService.getCurrentUser()).thenReturn(volunteer);
        when(messageRepository.findVisibleCommunityMessages(pageable))
                .thenReturn(new PageImpl<>(List.of(direct, community), pageable, 2));
        when(userRepository.findAllById(any())).thenReturn(List.of(volunteer));
        MessageService service = new MessageService(
                messageRepository, deletionRepository, userRepository, userService);

        mockMvc(service).perform(get("/api/community/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(2))
                .andExpect(jsonPath("$.data.content[0].content").value("Shared"));
    }

    @Test
    void beneficiaryCannotGetMessages() throws Exception {
        MessageService service = mock(MessageService.class);
        when(service.listMessages(0, 20)).thenThrow(new UnauthorizedException(
                "Only responders can access the community feed."));

        mockMvc(service).perform(get("/api/community/messages"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void beneficiaryCannotPostMessage() throws Exception {
        MessageService service = mock(MessageService.class);
        when(service.createMessage(any())).thenThrow(new UnauthorizedException(
                "Only responders can access the community feed."));

        mockMvc(service).perform(post("/api/community/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Hello\",\"communityCategory\":\"UPDATE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void nonAdminCannotDeleteMessage() throws Exception {
        MessageService service = mock(MessageService.class);
        when(service.deleteMessage(10L, "Spam")).thenThrow(new UnauthorizedException(
                "Only administrators can moderate community messages."));

        mockMvc(service).perform(delete("/api/community/messages/10")
                        .param("reason", "Spam"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void adminDeleteWithoutReasonReturnsBadRequest() throws Exception {
        MessageService service = mock(MessageService.class);
        when(service.deleteMessage(10L, null)).thenThrow(
                new BusinessException("A deletion reason is required."));

        mockMvc(service).perform(delete("/api/community/messages/10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("A deletion reason is required."));
    }

    private MockMvc mockMvc(MessageService service) {
        return MockMvcBuilders.standaloneSetup(new MessageController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
