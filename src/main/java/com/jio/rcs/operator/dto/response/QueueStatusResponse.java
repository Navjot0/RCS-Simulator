package com.jio.rcs.operator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueStatusResponse {
    private Map<String, Integer> depths;
    private int workerThreads;
    /** operator.tps.limit - the single global TPS ceiling. */
    private int tpsLimit;
    /** Current fixed-window request count against that ceiling. */
    private int currentWindowCount;
}
