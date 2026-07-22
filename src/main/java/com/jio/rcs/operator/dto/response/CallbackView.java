package com.jio.rcs.operator.dto.response;

import com.jio.rcs.operator.model.CallbackAttempt;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallbackView {
    private String providerMessageId;
    private String callbackUrl;
    private String callbackStatus;
    private List<CallbackAttempt> attempts;
}
