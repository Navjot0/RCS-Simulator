package com.jio.rcs.operator.mapper;

import com.jio.rcs.operator.dto.request.SendMessageRequest;
import com.jio.rcs.operator.dto.response.MessageStatusResponse;
import com.jio.rcs.operator.dto.response.SendMessageResponse;
import com.jio.rcs.operator.dto.response.StatusHistoryEntryResponse;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.model.StatusHistoryEntry;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Converts between API DTOs and the in-memory MessageContext. Rich-messaging
 * constructs (rich card / carousel / media / suggestions) are kept as plain
 * in-memory objects on the context - there is nothing to serialize or
 * persist, they simply live for the lifetime of the message.
 */
@Component
public class MessageMapper {

    public MessageContext toContext(SendMessageRequest request, String providerMessageId,
                                     String status, String callbackUrl) {
        Instant now = Instant.now();
        return MessageContext.builder()
                .providerMessageId(providerMessageId)
                .correlationId(request.getCorrelationId())
                .agentId(request.getAgentId())
                .phoneNumber(request.getPhoneNumber())
                .messageType(request.getMessageType())
                .content(request.getContent())
                .status(status)
                .acceptedAt(now)
                .lastUpdatedAt(now)
                .callbackUrl(callbackUrl)
                .callbackStatus("NONE")
                .build();
    }

    public SendMessageResponse toSendResponse(MessageContext message) {
        return SendMessageResponse.builder()
                .status(message.getStatus())
                .providerMessageId(message.getProviderMessageId())
                .correlationId(message.getCorrelationId())
                .timestamp(message.getAcceptedAt())
                .build();
    }

    public MessageStatusResponse toStatusResponse(MessageContext message) {
        return MessageStatusResponse.builder()
                .providerMessageId(message.getProviderMessageId())
                .correlationId(message.getCorrelationId())
                .agentId(message.getAgentId())
                .phoneNumber(message.getPhoneNumber())
                .status(message.getStatus())
                .errorCode(message.getErrorCode())
                .errorDescription(message.getErrorDescription())
                .acceptedAt(message.getAcceptedAt())
                .lastUpdatedAt(message.getLastUpdatedAt())
                .history(message.getHistory().stream().map(this::toHistoryEntry).toList())
                .build();
    }

    private StatusHistoryEntryResponse toHistoryEntry(StatusHistoryEntry history) {
        return StatusHistoryEntryResponse.builder()
                .previousStatus(history.getPreviousStatus())
                .newStatus(history.getNewStatus())
                .errorCode(history.getErrorCode())
                .errorDescription(history.getErrorDescription())
                .transitionAt(history.getTransitionAt())
                .build();
    }
}
