package com.jio.rcs.operator.service;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.dto.request.BulkMessageRequest;
import com.jio.rcs.operator.dto.request.SendMessageRequest;
import com.jio.rcs.operator.dto.response.BulkMessageResponse;
import com.jio.rcs.operator.dto.response.BulkMessageResultItem;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.processor.MessageProcessor;
import com.jio.rcs.operator.util.IdGenerator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles POST /v1/messages/bulk. Each entry is validated independently so
 * a single malformed message doesn't fail the whole batch - it is simply
 * counted as rejected, matching real bulk provider API semantics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkMessageService {

    private final Validator validator;
    private final MessageProcessor messageProcessor;
    private final ProviderProperties providerProperties;

    public BulkMessageResponse submit(BulkMessageRequest request) {
        int maxBatch = providerProperties.getBulk().getMaxMessagesPerBatch();
        if (request.getMessages().size() > maxBatch) {
            throw new IllegalArgumentException("Batch exceeds max allowed size of " + maxBatch);
        }

        String batchId = IdGenerator.batchId(providerProperties.getIdentity().getProviderCode());
        List<BulkMessageResultItem> results = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;

        for (SendMessageRequest item : request.getMessages()) {
            Set<ConstraintViolation<SendMessageRequest>> violations = validator.validate(item);
            if (!violations.isEmpty()) {
                rejected++;
                String detail = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining("; "));
                results.add(BulkMessageResultItem.builder()
                        .correlationId(item.getCorrelationId())
                        .status("REJECTED")
                        .errorCode("VALIDATION_FAILED")
                        .errorDescription(detail)
                        .build());
                continue;
            }

            MessageContext message = messageProcessor.ingest(item, batchId);
            accepted++;
            results.add(BulkMessageResultItem.builder()
                    .correlationId(message.getCorrelationId())
                    .providerMessageId(message.getProviderMessageId())
                    .status(message.getStatus())
                    .build());
        }

        log.info("Bulk batch {} accepted={} rejected={}", batchId, accepted, rejected);

        return BulkMessageResponse.builder()
                .batchId(batchId)
                .acceptedCount(accepted)
                .rejectedCount(rejected)
                .timestamp(Instant.now())
                .results(results)
                .build();
    }
}
