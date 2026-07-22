package com.jio.rcs.operator.registry;

import com.jio.rcs.operator.model.MessageContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The service's entire "state" - a bounded, in-process, in-memory map of
 * in-flight/recently-completed messages. This deliberately is NOT a
 * database: nothing here is written to disk, there is no schema, and
 * {@link MessageStoreCleanupScheduler} continuously evicts terminal
 * entries once their retention window elapses. It exists solely so the
 * asynchronous pipeline stages (validation -> processing -> DLR ->
 * callback) can find the message they're operating on, and so
 * GET /v1/messages/{id} can answer a status query for a message that is
 * still in flight.
 */
@Component
public class MessageStore {

    private final Map<String, MessageContext> store = new ConcurrentHashMap<>();

    public void put(MessageContext context) {
        store.put(context.getProviderMessageId(), context);
    }

    public Optional<MessageContext> find(String providerMessageId) {
        return Optional.ofNullable(store.get(providerMessageId));
    }

    public Collection<MessageContext> all() {
        return store.values();
    }

    public List<MessageContext> byBatchId(String batchId) {
        return store.values().stream()
                .filter(m -> batchId.equals(m.getBatchId()))
                .toList();
    }

    public List<MessageContext> byStatus(String status) {
        return store.values().stream()
                .filter(m -> status.equalsIgnoreCase(m.getStatus()))
                .toList();
    }

    public void remove(String providerMessageId) {
        store.remove(providerMessageId);
    }

    public int size() {
        return store.size();
    }
}
