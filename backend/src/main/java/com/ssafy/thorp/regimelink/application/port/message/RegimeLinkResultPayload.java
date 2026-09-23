package com.ssafy.thorp.regimelink.application.port.message;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * {@code FINISHED(COMPLETED)}의 계산 결과. 011 화면의 숫자는 전부 여기서 나온다.
 *
 * <p>계약 기준: {@code docs/specs/regime-link-messaging/README.md} "{@code FINISHED.result}", 픽스처
 * {@code event-finished-completed.json}·{@code event-finished-incomplete63.json}.
 *
 * <p><b>{@code returnRate}의 JSON 키는 {@code return}이다.</b> {@code return}은 Java 예약어라 컴포넌트 이름으로 쓸 수
 * 없고, 이 패키지는 Jackson 애노테이션을 쓰지 않으므로(ArchUnit 규칙 7) 이름 대응은 infrastructure의 {@code
 * RegimeLinkMessageJson}이 mixin으로 붙인다.
 *
 * @param holdings 구간의 <b>모든</b> 거래일. 작업의 {@code dailyPositions}(수량이 바뀐 날만)와 {@code
 *     portfolioSeries}로 산출한다
 * @param trades 구간의 모든 거래. 개수가 {@code window63.tradeCount}와 같다
 * @param kospi {@code marketData.benchmarkInstrumentId}의 같은 날짜 종가로 낸 참고 값. 전략과의 차이는 계산하지 않는다
 * @param inputDigest 작업 메시지의 값을 그대로 돌려준다. 워커는 검증하지 않고 재현 대조는 백엔드 몫이다
 */
public record RegimeLinkResultPayload(
        WindowSummary window63,
        WindowSummary window21,
        List<Holding> holdings,
        List<ResultTrade> trades,
        Benchmark kospi,
        Basis basis,
        String inputDigest) {

    /**
     * 구간 요약. {@code window21}은 d0~d21, {@code window63}은 d0~min(d63, lastAvailable)이다.
     *
     * @param tradingDays 실제 구간 길이. {@code complete}가 63(또는 21)을 다 채웠는지다
     * @param returnRate 구간 수익률. JSON 키는 {@code return}이며 <b>연환산하지 않는다</b>
     * @param maxDrawdown 구간 최대 하락 폭. 연환산하지 않는다
     * @param tradesPerMonth {@code tradeCount ÷ (tradingDays ÷ 21)}, 소수 둘째 자리
     * @param turnover 실제 체결대금 기반 회전율. 단위는 {@code turnoverBasis}가 밝힌다
     * @param lossContribution 섹터별 구간 손익 ÷ 전체 구간 손익. 이익 종목도 빼지 않고 들어가 몫의 합이 1이며, 전체 구간 손익이 0일 때만 키가
     *     없다. 이름이 '손실'이지만 이익 종목을 빼지 않는다
     */
    public record WindowSummary(
            int tradingDays,
            BigDecimal returnRate,
            BigDecimal maxDrawdown,
            int tradeCount,
            BigDecimal tradesPerMonth,
            BigDecimal turnover,
            TurnoverBasis turnoverBasis,
            WindowCosts costs,
            Map<String, BigDecimal> lossContribution,
            boolean complete) {}

    /** 구간 비용 네 갈래와 합계. 체결 단위 {@code FillCosts}와 달리 {@code total}이 있다. */
    public record WindowCosts(
            BigDecimal commission,
            BigDecimal spread,
            BigDecimal slippage,
            BigDecimal tax,
            BigDecimal total) {}

    /**
     * @param actual 종목별 그날 비중(평가액 ÷ 자산). 목표 비중이 아니라 실제 보유 비중이다
     */
    public record Holding(LocalDate date, Map<String, BigDecimal> actual) {}

    /**
     * 표시용 거래 한 건. 작업의 {@code InputTrade}와 이름만 같고 모양이 다르다 — 시각 대신 날짜를, 비용 네 갈래 대신 한 값을 쓴다. 두 모델을 하나로
     * 합치지 않는다.
     *
     * @param cost 이 체결의 비용 합계
     */
    public record ResultTrade(
            LocalDate date,
            String instrumentId,
            FillSide side,
            long quantity,
            BigDecimal price,
            BigDecimal cost,
            TradeReason reason) {}

    /** 참고 지수의 두 구간 값. 전략 결과와 나란히 두기만 하고 초과 수익을 계산하지 않는다. */
    public record Benchmark(BenchmarkWindow window63, BenchmarkWindow window21) {}

    /**
     * @param returnRate JSON 키는 {@code return}이다
     */
    public record BenchmarkWindow(BigDecimal returnRate, BigDecimal maxDrawdown) {}

    /** 이 숫자가 어떤 자료 위에서 나왔는가. 화면이 역사 비교임을 밝히는 근거다(spec FR-015). */
    public record Basis(
            BasisKind kind, String dataVersion, String datasetId, String analyticsMethod) {}
}
