package com.jio.rcs.operator.service;

import com.jio.rcs.operator.dto.response.MessageStatusResponse;
import com.jio.rcs.operator.dto.response.PagedResponse;
import com.jio.rcs.operator.exception.ResourceNotFoundException;
import com.jio.rcs.operator.mapper.MessageMapper;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.registry.MessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageQueryService {

    private final MessageStore messageStore;
    private final MessageMapper messageMapper;

    public MessageStatusResponse getStatus(String providerMessageId) {
        MessageContext message = messageStore.find(providerMessageId)
                .orElseThrow(() -> new ResourceNotFoundException("No message found with providerMessageId " + providerMessageId
                        + " (it may have already been evicted from the in-memory store, or never existed)"));
        return messageMapper.toStatusResponse(message);
    }

    public PagedResponse<MessageContext> listMessages(String status, int page, int size) {
        List<MessageContext> filtered = (status == null || status.isBlank()
                ? messageStore.all().stream()
                : messageStore.byStatus(status).stream())
                .sorted(Comparator.comparing(MessageContext::getAcceptedAt).reversed())
                .toList();
        return PagedResponse.of(filtered, page, size);
    }
}
