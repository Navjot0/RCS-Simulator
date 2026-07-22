package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.util.ProbabilityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Default provider simulation: rolls the single global configured
 * failed/delivered/displayed percentages (operator.probability) and, on
 * failure, picks a weighted provider error code from
 * operator.error-simulation.codes.
 */
@Component
@RequiredArgsConstructor
public class DefaultDlrOutcomeStrategy implements DlrOutcomeStrategy {

    private final ProviderProperties providerProperties;

    @Override
    public DlrOutcome decideOutcome(MessageContext message) {
        var probability = providerProperties.getProbability();

        if (providerProperties.getErrorSimulation().isEnabled()
                && ProbabilityUtil.chance(probability.getFailedPercentage())) {
            var codes = providerProperties.getErrorSimulation().getCodes();
            var picked = ProbabilityUtil.weightedPick(codes, ProviderProperties.ErrorCode::getWeight);
            return new DlrOutcome(MessageState.FAILED, false, picked.getCode(), picked.getDescription());
        }

        if (ProbabilityUtil.chance(probability.getDeliveredPercentage())) {
            boolean displayed = ProbabilityUtil.chance(probability.getDisplayedPercentage());
            return new DlrOutcome(MessageState.DELIVERED, displayed, null, null);
        }

        return new DlrOutcome(MessageState.UNKNOWN, false, "UNKNOWN",
                "Delivery outcome could not be confirmed by the operator");
    }
}
