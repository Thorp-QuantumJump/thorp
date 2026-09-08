package com.ssafy.thorp.backtest.application.command;

import com.ssafy.thorp.backtest.application.port.message.BenchmarkResult;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import java.util.Objects;

/**
 * FINISHED 이벤트의 결과를 저장하라는 명령.
 *
 * <p>결과에는 지킬 불변식이 없고 계약 검증은 수신 측이 이미 끝냈으므로 결과 도메인 모델을 두지 않는다. 계약 DTO({@link EngineOutput}, {@link
 * BenchmarkResult})를 그대로 넘기고 저장소 구현이 엔티티로 매핑한다.
 *
 * @param backtestJobId Job PK
 * @param executionId 어느 실행 시도의 결과인지
 * @param eventId 처리한 FINISHED 이벤트. 추적용
 * @param benchmark null = 벤치마크 미지정
 */
public record SaveBacktestResultCommand(
        Long backtestJobId,
        String executionId,
        String eventId,
        EngineOutput engineOutput,
        BenchmarkResult benchmark) {

    public SaveBacktestResultCommand {
        Objects.requireNonNull(backtestJobId, "backtestJobId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(engineOutput, "engineOutput must not be null");
    }
}
