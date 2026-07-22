package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.util.ProbabilityUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProbabilityUtilTest {

    @Test
    void chanceIsAlwaysTrueAt100Percent() {
        for (int i = 0; i < 50; i++) {
            assertThat(ProbabilityUtil.chance(100)).isTrue();
        }
    }

    @Test
    void chanceIsAlwaysFalseAtZeroPercent() {
        for (int i = 0; i < 50; i++) {
            assertThat(ProbabilityUtil.chance(0)).isFalse();
        }
    }

    @Test
    void weightedPickAlwaysReturnsTheOnlyNonZeroWeightedItem() {
        List<Integer> weights = List.of(0, 0, 100, 0);
        for (int i = 0; i < 50; i++) {
            Integer picked = ProbabilityUtil.weightedPick(weights, w -> w);
            assertThat(picked).isEqualTo(100);
        }
    }

    @Test
    void randomBetweenRespectsBounds() {
        for (int i = 0; i < 50; i++) {
            long value = ProbabilityUtil.randomBetween(50, 300);
            assertThat(value).isBetween(50L, 300L);
        }
    }
}
