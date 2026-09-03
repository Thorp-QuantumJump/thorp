package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Hibernate가 쓰는 것과 같은 기본 설정의 ObjectMapper(미지 키 거부)로 역직렬화 규약을 확인한다. */
class EquityPointJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Worker가 원소에 모르는 키를 추가해도 역직렬화가 실패하지 않는다")
    void ignoresUnknownKeys() throws Exception {
        String json =
                "{\"date\":\"2019-01-02\",\"equity\":10120000,\"cumulativeReturn\":0.012,"
                        + "\"drawdown\":0.0,\"cash\":500000}";

        EquityPointJson point = mapper.readValue(json, EquityPointJson.class);

        assertThat(point).isEqualTo(new EquityPointJson("2019-01-02", 10120000L, 0.012, 0.0));
    }
}
