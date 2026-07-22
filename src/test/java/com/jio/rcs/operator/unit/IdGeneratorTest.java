package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.util.IdGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    @Test
    void providerMessageIdStartsWithProviderCodeAndIsUnique() {
        String id1 = IdGenerator.providerMessageId("JIO");
        String id2 = IdGenerator.providerMessageId("JIO");

        assertThat(id1).startsWith("JIO");
        assertThat(id1).hasSize(3 + 12);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void batchIdContainsBatchMarker() {
        String batchId = IdGenerator.batchId("JIO");
        assertThat(batchId).startsWith("JIOBATCH");
    }

    @Test
    void mediaIdStartsWithMediaMarkerAndIsUnique() {
        String id1 = IdGenerator.mediaId();
        String id2 = IdGenerator.mediaId();

        assertThat(id1).startsWith("MEDIA");
        assertThat(id1).hasSize(5 + 16);
        assertThat(id1).isNotEqualTo(id2);
    }
}
