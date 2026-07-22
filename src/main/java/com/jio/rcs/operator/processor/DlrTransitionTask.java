package com.jio.rcs.operator.processor;

public record DlrTransitionTask(String providerMessageId, String newState, String errorCode, String errorDescription) {
}
