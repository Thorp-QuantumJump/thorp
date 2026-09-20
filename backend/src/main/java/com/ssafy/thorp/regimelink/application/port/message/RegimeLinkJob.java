package com.ssafy.thorp.regimelink.application.port.message;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 사례 연결 작업 메시지 본문(schemaVersion 1). 백엔드가 조립해 {@code regime-link.jobs}로 발행하고 워커 {@code
 * worker.regime_link.slicer}가 그대로 받는다.
 *
 * <p>계약 기준: {@code docs/specs/regime-link-messaging/README.md}, 픽스처 {@code
 * job-continuous.json}·{@code job-fresh-cash.json}·{@code job-partial-delisted.json}. JSON 키 =
 * record 컴포넌트 이름(camelCase)이고 BigDecimal 문자열·null 키 생략 같은 직렬화 규칙은 infrastructure의 {@code
 * RegimeLinkMessageJson}이 맡는다.
 *
 * <p>{@code schemaVersion}은 본문에 없다 — 작업은 AMQP 헤더로만 버전을 싣고 이벤트만 본문 첫 필드에 싣는다(계약 "표기 규칙").
 *
 * <p><b>구간을 잘라 담는 것은 백엔드다.</b> 워커는 여기 실린 범위 밖을 요청하지 않고, 범위가 모자라면 채우지 않고 {@code INPUT_INCOMPLETE}로
 * 실패한다. 자료 부족 판정은 발행 전 백엔드 몫이라 여기까지 온 작업은 이미 계산할 수 있다고 본 것이다(research R13).
 */
public record RegimeLinkJob(
        JobSpec job,
        WindowSpec window,
        RunSpec run,
        UniverseSpec universe,
        MarketDataSpec marketData,
        InputsSpec inputs,
        String inputDigest) {

    /**
     * @param linkId 연결 식별자. 이벤트 envelope의 {@code linkId}·{@code correlation_id}와 같다
     * @param attempt 이 연결의 발행 시도 번호. {@code message_id = "RLJ-" + linkId + "-" + attempt}
     */
    public record JobSpec(String linkId, int attempt, RegimeLinkScenario scenario) {}

    /**
     * 사후 구간의 날짜 경계. 날짜는 전부 거래일이다.
     *
     * @param d0 사례 종료 다음 거래일. 구간의 첫날
     * @param d21 보조 구간의 마지막 거래일
     * @param d63 주 구간의 마지막 거래일
     * @param lastAvailable 실제로 계산할 수 있는 마지막 거래일. 구간 안 상장폐지면 <b>폐지 전날까지로 이미 줄인 값</b>이라 워커가 다시 줄이지
     *     않는다
     * @param completedTradingDays d0~lastAvailable의 거래일 수. {@code inputs.portfolioSeries}의 행 수는 이 값
     *     + 1이다 (baselineDate 행이 하나 더 있다)
     * @param baselineDate d0 직전 거래일. {@code CONTINUOUS}의 구간 기준 평가액이 이 날의 것이고 {@code FRESH_CASH}에서는
     *     참고용이다
     */
    public record WindowSpec(
            LocalDate d0,
            LocalDate d21,
            LocalDate d63,
            LocalDate lastAvailable,
            int completedTradingDays,
            LocalDate baselineDate) {}

    /**
     * 잘라낼 원천 실행(004). 실행 자체는 고치지 않고 읽기만 한다.
     *
     * @param effectiveStart 원천 실행의 성과 집계 시작일. d0보다 뒤면 애초에 발행되지 않는다({@code OUT_OF_PERIOD})
     * @param initialCash {@code FRESH_CASH}의 구간 기준값
     * @param analyticsMethod 지표 산출 방식 버전. 결과 {@code basis}에 그대로 실린다
     */
    public record RunSpec(
            String runId,
            LocalDate effectiveStart,
            BigDecimal initialCash,
            String analyticsMethod) {}

    public record UniverseSpec(String mappingVersion, List<UniverseItem> items) {}

    /**
     * @param delistedOn 상장폐지일. 구간 안이면 그 전날까지만 계산하고 결과가 {@code partial=true}다. 폐지되지 않았으면 키가 없다
     */
    public record UniverseItem(String sectorId, String instrumentId, LocalDate delistedOn) {}

    public record MarketDataSpec(
            String datasetId, String dataVersion, String benchmarkInstrumentId) {}

    /**
     * 004 저장 결과에서 구간만큼 잘라 담은 입력(계약 "구간 입력의 출처와 제약", DEP-004-011).
     *
     * @param portfolioSeries baselineDate~lastAvailable의 <b>모든</b> 거래일
     * @param openingPositions baselineDate 이전 마지막 보유 스냅샷. {@code FRESH_CASH}는 빈 목록
     * @param dailyPositions d0~lastAvailable 안에서 004가 남긴 스냅샷(수량이 바뀐 날)만. 백엔드는 채우지 않는다
     * @param trades d0~lastAvailable 안의 개별 체결. 결과의 {@code trades}와 이름만 같고 모양이 다르다
     * @param cashFlows 분배금 등 구간 안 현금 흐름
     */
    public record InputsSpec(
            List<PortfolioPoint> portfolioSeries,
            List<Position> openingPositions,
            List<DailyPositions> dailyPositions,
            List<InputTrade> trades,
            List<CashFlow> cashFlows) {}

    public record PortfolioPoint(LocalDate date, BigDecimal equity, BigDecimal cash) {}

    public record Position(String instrumentId, long quantity) {}

    /**
     * 하루치 보유 스냅샷.
     *
     * <p>{@code positions}는 <b>그날 보유 전체</b>이고 수량 0인 종목은 빠져 있다. 그래서 워커의 이월은 실린 종목만 고치는 부분 갱신이 아니라
     * 덮어쓰기다 — 체결로 더하되 스냅샷이 있는 날은 그 수량으로 맞춘다. 분할 같은 기업행위는 체결 밖에서 수량을 바꾸므로 체결만 누적하면 그날부터 수량이 틀린다.
     */
    public record DailyPositions(LocalDate date, List<Position> positions) {}

    /**
     * 개별 체결 한 건. 004 {@code fills[]}를 그대로 옮긴 것이라 진입·청산을 묶은 거래 단위가 아니다.
     *
     * @param price 실제 체결가(호가차·슬리피지 반영)
     * @param referencePrice 가격 충격 전 기준가. 비용의 기준 금액은 {@code quantity × referencePrice}다
     * @param cashCost 체결 대금과 별도로 현금에서 빠진 비용(수수료 + 세금). 체결가에 반영된 호가차·슬리피지는 들어 있지 않다
     * @param reason 표시용. 004가 남기지 않았으면 키가 없다
     */
    public record InputTrade(
            String fillId,
            Instant time,
            String instrumentId,
            FillSide side,
            long quantity,
            BigDecimal price,
            BigDecimal referencePrice,
            FillCosts costs,
            BigDecimal cashCost,
            TradeReason reason) {}

    /** 체결 한 건의 비용 네 갈래. 합계({@code total})는 두지 않는다 — 결과 쪽 {@code costs}에만 있다. */
    public record FillCosts(
            BigDecimal commission, BigDecimal spread, BigDecimal slippage, BigDecimal tax) {}

    /**
     * 구간 안 현금 흐름. 체결과 이것만으로 구간 현금이 되살아난다(DEP-004-011 대조에서 확인).
     *
     * @param type 004와 같은 자유 문자열이며 현재 쓰이는 값은 {@code DISTRIBUTION} 하나다. enum으로 좁히면 004가 갈래를 늘린 날 정상
     *     메시지가 DLQ로 간다
     */
    public record CashFlow(LocalDate date, String instrumentId, String type, BigDecimal amount) {}
}
