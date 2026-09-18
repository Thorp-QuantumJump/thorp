package com.ssafy.thorp.learning.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExampleAllocationTest {

    @Test
    @DisplayName("비중 0은 허용하고, 합계가 1000이 아니어도 저장할 수 있는 모양이다")
    void acceptsZeroWeightAndPartialSum() {
        ExampleAllocation example =
                new ExampleAllocation(
                        List.of(
                                new SectorWeight("SEMICONDUCTOR", 0),
                                new SectorWeight("BATTERY", 450)));

        assertThat(example.totalWeightTenths()).isEqualTo(450);
    }

    @Test
    @DisplayName("같은 섹터 코드가 두 번 나오면 거절한다")
    void rejectsDuplicateCodes() {
        assertThatThrownBy(
                        () ->
                                new ExampleAllocation(
                                        List.of(
                                                new SectorWeight("BATTERY", 500),
                                                new SectorWeight("BATTERY", 500))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("비중 1001과 음수는 거절한다")
    void rejectsWeightOutOfRange() {
        assertThatThrownBy(() -> new SectorWeight("BATTERY", 1001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SectorWeight("BATTERY", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("섹터 21개는 거절하고 20개는 받는다")
    void rejectsMoreThanTwentySectors() {
        List<SectorWeight> twenty =
                IntStream.range(0, 20).mapToObj(i -> new SectorWeight("S" + i, 50)).toList();
        assertThat(new ExampleAllocation(twenty).sectors()).hasSize(20);

        List<SectorWeight> twentyOne =
                IntStream.range(0, 21).mapToObj(i -> new SectorWeight("S" + i, 0)).toList();
        assertThatThrownBy(() -> new ExampleAllocation(twentyOne))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("001 섹터 코드 형식이 아니면 거절한다")
    void rejectsInvalidSectorCode() {
        assertThatThrownBy(() -> new SectorWeight("semiconductor", 500))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
