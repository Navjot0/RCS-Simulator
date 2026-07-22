package com.jio.rcs.operator.scheduler;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.registry.MessageStore;
import com.jio.rcs.operator.statemachine.MessageState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodically sweeps terminal messages out of the in-memory MessageStore
 * once they exceed operator.message-store.retention-minutes, so the
 * process's memory footprint stays bounded. This is the mechanism that
 * keeps the service honestly "stateless": nothing survives beyond a short,
 * configurable grace window past completion, and nothing survives a
 * restart at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageStoreCleanupScheduler {

    private final MessageStore messageStore;
    private final ProviderProperties providerProperties;

    @Scheduled(fixedDelayString = "${operator.message-store.cleanup-interval-millis:60000}")
    public void evictExpiredMessages() {
        long retentionMinutes = providerProperties.getMessageStore().getRetentionMinutes();
        Instant cutoff = Instant.now().minusSeconds(retentionMinutes * 60);
        int evicted = 0;

        for (MessageContext context : messageStore.all()) {
            boolean terminal = context.getStatus() != null && MessageState.valueOf(context.getStatus()).isTerminal();
            Instant reference = context.getLastUpdatedAt() != null ? context.getLastUpdatedAt() : context.getAcceptedAt();
            if (terminal && reference != null && reference.isBefore(cutoff)) {
                messageStore.remove(context.getProviderMessageId());
                evicted++;
            }
        }

        if (evicted > 0) {
            log.debug("Evicted {} expired message(s) from in-memory store; {} remaining", evicted, messageStore.size());
        }
    }
}
