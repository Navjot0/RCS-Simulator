package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

public class AgentBlockedException extends ProviderException {
    public AgentBlockedException(String message) {
        super("AGENT_BLOCKED", message, HttpStatus.FORBIDDEN);
    }
}
