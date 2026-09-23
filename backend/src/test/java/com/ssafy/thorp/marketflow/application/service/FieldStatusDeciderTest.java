package com.ssafy.thorp.marketflow.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.marketflow.domain.model.IndicatorCollectionState;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import com.ssafy.thorp.marketflow.domain.vo.FieldStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldStatusDeciderTest {

    private final FieldStatusDecider decider = new FieldStatusDecider();

    @Test
    void prioritizesEmptyFailureExampleAndReadyWithoutUsingAge() {
        var metadata = List.of(metadata("one"));
        Instant veryOld = Instant.parse("2000-01-01T00:00:00Z");

        assertThat(decider.decide(List.of(), List.of())).isEqualTo(FieldStatus.EMPTY);
        assertThat(
                        decider.decide(
                                metadata,
                                List.of(new IndicatorCollectionState("one", veryOld, false, true))))
                .isEqualTo(FieldStatus.COLLECTION_FAILED);
        assertThat(
                        decider.decide(
                                metadata,
                                List.of(new IndicatorCollectionState("one", veryOld, true, true))))
                .isEqualTo(FieldStatus.EXAMPLE);
        assertThat(
                        decider.decide(
                                metadata,
                                List.of(new IndicatorCollectionState("one", veryOld, true, false))))
                .isEqualTo(FieldStatus.READY);
    }

    private static IndicatorMetadata metadata(String id) {
        return new IndicatorMetadata(id, FieldId.PRODUCTION, id, "%", "변환", "출처", "기관", "0.1", 1);
    }
}
