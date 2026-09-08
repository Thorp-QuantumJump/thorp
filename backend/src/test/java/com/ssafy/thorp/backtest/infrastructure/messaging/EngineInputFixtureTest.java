package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.service.EngineInputAssembler;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 백엔드가 만드는 작업 메시지가 계약 예시와 정확히 같은지 확인한다.
 *
 * <p>픽스처 값으로 Job을 만들어 조립하고, 발행에 쓰는 것과 같은 ObjectMapper로 직렬화해 픽스처와 JSON 트리를 비교한다(키 순서만 무시). 이 테스트가
 * 통과하면 "백엔드가 발행하는 메시지 == docs/specs/backtest-messaging/examples/engine-input.json"이 보장된다. gradle
 * test의 작업 디렉터리는 backend/다.
 */
class EngineInputFixtureTest {

    private static final Path FIXTURE =
            Path.of("..", "docs", "specs", "backtest-messaging", "examples", "engine-input.json");

    private final ObjectMapper mapper = BacktestRabbitConfig.messageObjectMapper();
    private final EngineInputAssembler assembler = new EngineInputAssembler();

    /** 픽스처의 run/universe/strategy/portfolio/benchmark 값을 그대로 담은 Job. */
    private static BacktestJob fixtureJob(InstrumentId benchmark) {
        return BacktestJob.restore(
                1L,
                BacktestId.from("BT-20260907-000001"),
                "MA_CROSSOVER",
                "1.0.0",
                Map.of("shortWindow", 20, "longWindow", 60, "threshold", 0.05),
                List.of(new InstrumentId("005930.XKRX"), new InstrumentId("000660.XKRX")),
                new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                new BigDecimal("10000000"),
                PositionSizingMode.STRATEGY_DEFINED,
                new BigDecimal("0.50"),
                benchmark,
                42,
                BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("조립한 EngineInput의 직렬화 결과가 계약 픽스처와 같다")
    void assembledMessageMatchesFixture() throws IOException {
        EngineInput assembled = assembler.assemble(fixtureJob(new InstrumentId("069500.XKRX")));

        JsonNode actual = mapper.valueToTree(assembled);
        JsonNode expected = mapper.readTree(Files.readString(FIXTURE));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("벤치마크가 없는 Job은 benchmark 키를 생략한다")
    void omitsBenchmarkKeyWhenAbsent() {
        EngineInput assembled = assembler.assemble(fixtureJob(null));

        JsonNode actual = mapper.valueToTree(assembled);

        assertThat(actual.has("benchmark")).isFalse();
    }
}
