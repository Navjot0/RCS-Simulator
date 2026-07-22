package com.jio.rcs.operator.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Note: individual entries are deliberately NOT cascade-validated
 * (no @Valid on the list) so that one malformed message in a batch is
 * counted as "rejected" rather than failing the entire batch with a 400 -
 * matching how a real bulk provider API behaves.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkMessageRequest {

    @NotEmpty(message = "messages must contain at least one entry")
    private List<SendMessageRequest> messages;
}
