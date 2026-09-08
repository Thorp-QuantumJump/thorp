package com.ssafy.thorp.backtest.application.port.message;

/**
 * FINISHED 이벤트 payload. 성공/실패는 {@code engineOutput.run.status}로 판단한다.
 *
 * @param benchmark null = 벤치마크 미지정 또는 실패
 */
public record FinishedPayload(EngineOutput engineOutput, BenchmarkResult benchmark) {}
