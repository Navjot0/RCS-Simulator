package com.jio.rcs.operator.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jio.rcs.operator.controller.MessageController;
import com.jio.rcs.operator.dto.request.SendMessageRequest;
import com.jio.rcs.operator.dto.response.MessageStatusResponse;
import com.jio.rcs.operator.dto.response.SendMessageResponse;
import com.jio.rcs.operator.dto.response.StatusHistoryEntryResponse;
import com.jio.rcs.operator.exception.GlobalExceptionHandler;
import com.jio.rcs.operator.exception.RateLimitExceededException;
import com.jio.rcs.operator.exception.ResourceNotFoundException;
import com.jio.rcs.operator.mapper.MessageMapper;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.processor.MessageProcessor;
import com.jio.rcs.operator.service.MessageQueryService;
import com.jio.rcs.operator.service.TpsLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    @Mock
    private MessageProcessor messageProcessor;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private MessageQueryService messageQueryService;

    @Mock
    private TpsLimiterService tpsLimiterService;

    @InjectMocks
    private MessageController messageController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(messageController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void send_whenTpsAllowed_returnsAcceptedAndResponse() {
        SendMessageRequest request = SendMessageRequest.builder()
                .agentId("agent-001")
                .to(List.of("+919876543210"))
                .messageType("text")
                .correlationId("corr-123")
                .build();

        MessageContext messageContext = MessageContext.builder()
                .providerMessageId("SIM-MSG-001")
                .status("ACCEPTED")
                .correlationId("corr-123")
                .acceptedAt(Instant.now())
                .build();

        SendMessageResponse expectedResponse = SendMessageResponse.builder()
                .providerMessageId("SIM-MSG-001")
                .status("ACCEPTED")
                .correlationId("corr-123")
                .timestamp(Instant.now())
                .build();

        when(tpsLimiterService.tryAcquire()).thenReturn(true);
        when(messageProcessor.ingest(eq(request), isNull())).thenReturn(messageContext);
        when(messageMapper.toSendResponse(messageContext)).thenReturn(expectedResponse);

        ResponseEntity<SendMessageResponse> response = messageController.send(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProviderMessageId()).isEqualTo("SIM-MSG-001");
        assertThat(response.getBody().getStatus()).isEqualTo("ACCEPTED");
        assertThat(response.getBody().getCorrelationId()).isEqualTo("corr-123");

        verify(tpsLimiterService).tryAcquire();
        verify(messageProcessor).ingest(request, null);
        verify(messageMapper).toSendResponse(messageContext);
    }

    @Test
    void send_whenTpsExceeded_throwsRateLimitExceededException() {
        SendMessageRequest request = SendMessageRequest.builder()
                .agentId("agent-001")
                .to(List.of("+919876543210"))
                .build();

        when(tpsLimiterService.tryAcquire()).thenReturn(false);

        assertThatThrownBy(() -> messageController.send(request))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Provider TPS limit exceeded");

        verify(tpsLimiterService).tryAcquire();
        verifyNoInteractions(messageProcessor);
        verifyNoInteractions(messageMapper);
    }

    @Test
    void sendEndpoint_viaMockMvc_returnsAccepted() throws Exception {
        SendMessageRequest request = SendMessageRequest.builder()
                .agentId("agent-001")
                .to(List.of("+919876543210"))
                .messageType("text")
                .correlationId("corr-456")
                .build();

        MessageContext messageContext = MessageContext.builder()
                .providerMessageId("SIM-MSG-002")
                .status("ACCEPTED")
                .correlationId("corr-456")
                .acceptedAt(Instant.now())
                .build();

        SendMessageResponse responseDto = SendMessageResponse.builder()
                .providerMessageId("SIM-MSG-002")
                .status("ACCEPTED")
                .correlationId("corr-456")
                .timestamp(Instant.now())
                .build();

        when(tpsLimiterService.tryAcquire()).thenReturn(true);
        when(messageProcessor.ingest(any(SendMessageRequest.class), isNull())).thenReturn(messageContext);
        when(messageMapper.toSendResponse(messageContext)).thenReturn(responseDto);

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.providerMessageId").value("SIM-MSG-002"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.correlationId").value("corr-456"));

        verify(tpsLimiterService).tryAcquire();
        verify(messageProcessor).ingest(any(SendMessageRequest.class), isNull());
    }

    @Test
    void sendEndpoint_viaMockMvc_whenRateLimited_returns429TooManyRequests() throws Exception {
        SendMessageRequest request = SendMessageRequest.builder()
                .agentId("agent-001")
                .to(List.of("+919876543210"))
                .build();

        when(tpsLimiterService.tryAcquire()).thenReturn(false);

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.message").value("Provider TPS limit exceeded; try again shortly"));

        verify(tpsLimiterService).tryAcquire();
        verifyNoInteractions(messageProcessor);
    }

    @Test
    void getStatus_whenMessageExists_returnsStatusResponse() {
        String providerMessageId = "SIM-MSG-001";
        MessageStatusResponse expectedResponse = MessageStatusResponse.builder()
                .providerMessageId(providerMessageId)
                .status("DELIVERED")
                .agentId("agent-001")
                .phoneNumber("+919876543210")
                .correlationId("corr-123")
                .acceptedAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .history(List.of(
                        StatusHistoryEntryResponse.builder()
                                .previousStatus(null)
                                .newStatus("ACCEPTED")
                                .transitionAt(Instant.now())
                                .build(),
                        StatusHistoryEntryResponse.builder()
                                .previousStatus("ACCEPTED")
                                .newStatus("DELIVERED")
                                .transitionAt(Instant.now())
                                .build()
                ))
                .build();

        when(messageQueryService.getStatus(providerMessageId)).thenReturn(expectedResponse);

        ResponseEntity<MessageStatusResponse> response = messageController.getStatus(providerMessageId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProviderMessageId()).isEqualTo(providerMessageId);
        assertThat(response.getBody().getStatus()).isEqualTo("DELIVERED");
        assertThat(response.getBody().getHistory()).hasSize(2);

        verify(messageQueryService).getStatus(providerMessageId);
    }

    @Test
    void getStatusEndpoint_viaMockMvc_returnsOk() throws Exception {
        String providerMessageId = "SIM-MSG-001";
        MessageStatusResponse expectedResponse = MessageStatusResponse.builder()
                .providerMessageId(providerMessageId)
                .status("DELIVERED")
                .correlationId("corr-123")
                .build();

        when(messageQueryService.getStatus(providerMessageId)).thenReturn(expectedResponse);

        mockMvc.perform(get("/v1/messages/{providerMessageId}", providerMessageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerMessageId").value(providerMessageId))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.correlationId").value("corr-123"));

        verify(messageQueryService).getStatus(providerMessageId);
    }

    @Test
    void getStatusEndpoint_viaMockMvc_whenNotFound_returns404() throws Exception {
        String providerMessageId = "SIM-NOT-FOUND";

        when(messageQueryService.getStatus(providerMessageId))
                .thenThrow(new ResourceNotFoundException("No message found with providerMessageId " + providerMessageId));

        mockMvc.perform(get("/v1/messages/{providerMessageId}", providerMessageId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No message found with providerMessageId " + providerMessageId));

        verify(messageQueryService).getStatus(providerMessageId);
    }
}
