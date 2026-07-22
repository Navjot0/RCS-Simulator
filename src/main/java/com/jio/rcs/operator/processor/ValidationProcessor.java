package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.model.MessageContext;
import org.springframework.stereotype.Component;

/**
 * Business-level validation stage in the pipeline (Controller -&gt;
 * Validation -&gt; Message Engine -&gt; DLR Engine -&gt; Callback Engine). This
 * simulator is open/unauthenticated - there's no registered-agent registry
 * to check a message's agentId against anymore, so this stage is currently
 * a pass-through. It's kept as its own pipeline stage (rather than removed)
 * so the REJECTED path and the Validation queue stay wired up for whatever
 * business rules get added later (payload-shape validation already happens
 * separately via Bean Validation at the controller).
 */
@Component
public class ValidationProcessor {

    public ValidationResult validate(MessageContext message) {
        return ValidationResult.ok();
    }
}
