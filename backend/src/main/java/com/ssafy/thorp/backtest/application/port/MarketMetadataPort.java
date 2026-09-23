package com.ssafy.thorp.backtest.application.port;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 접수 검증과 작업 조립에 필요한 시장 자료 메타데이터(spec 004 analyze M1).
 *
 * <p>원본은 spec 001이 소유한다 — 섹터-ETF 대응표, 자료 버전, 상장일, 거래일 달력. 001이 표를 만들기 전까지 구현은 픽스처다({@code
 * infrastructure.marketdata.FixtureMarketMetadataAdapter}). 포트를 지금 두는 이유는 접수 경로가 이 네 가지를 실제로 필요로
 * 하고, 001이 들어오면 구현만 바꾸면 되게 하기 위해서다.
 *
 * <p><b>가격은 이 포트로 읽지 않는다.</b> 가격을 읽는 것은 워커이고 백엔드는 계산하지 않는다(원칙 1). 여기 있는 것은 전부 "실행 전에 요청을 거절할 수
 * 있는가"를 판단하는 값이다.
 */
public interface MarketMetadataPort {

    /** 현재 자료 버전({@code dataset_version.dataset_id}). 동일 버전 키와 결과 {@code versions.data}에 들어간다. */
    String currentDataVersion();

    /**
     * 대응표 버전에서 섹터가 그 종목으로 연결되는가.
     *
     * <p>요청이 들고 온 (sectorId, instrumentId) 쌍을 그대로 믿지 않는다 — 대응표가 바뀐 뒤 옛 화면이 보낸 요청이 조용히 다른 종목으로 실행되면
     * 비교 결과가 어긋난다.
     */
    boolean isMappedTo(String mappingVersion, String sectorId, String instrumentId);

    /**
     * 현재 대응표 버전. 요청이 버전을 들고 오지 않는 경로(spec 007 자동 탐색)가 쓴다.
     *
     * <p>호출자가 기본값을 정하지 않게 하려고 포트가 돌려준다 — 007이 대응표 버전 상수를 갖는 순간 001이 표를 갱신해도 그 값이 따라가지 않는다.
     */
    String currentMappingVersion();

    /**
     * 대응표 버전에서 이 종목이 속한 섹터. 모르는 종목이면 비어 있다.
     *
     * <p>{@link #isMappedTo}의 역방향이다. 섹터를 입력으로 받지 않는 화면(007은 종목만 고른다)이 004 접수에 필요한 섹터를 채우려면 이 조회가
     * 있어야 한다. 007이 종목-섹터 대응을 자체적으로 갖지 않게 하는 것이 목적이다.
     */
    Optional<String> sectorOf(String mappingVersion, String instrumentId);

    /** 종목 상장일. 모르는 종목이면 비어 있다(400 INVALID_INPUT의 근거). */
    Optional<LocalDate> listedOn(String instrumentId);

    /**
     * 001 {@code contracts/evaluation-window.md}의 {@code firstEvaluable} — <b>상장일</b>{@code
     * (notBefore)} 이후 워밍업 거래일을 소진하고 나서 그 종목을 평가할 수 있는 첫 거래일. 후보 집합에서 이 값의 최댓값이 계약의 {@code
     * commonStart}다.
     *
     * <p><b>사용자가 고른 시작일을 이 함수에 넣지 않는다.</b> 요청 앞에 워밍업 자료가 있으면 워커가 요청 구간보다 앞에서 읽으므로 요청 구간을 깎을 이유가
     * 없다(001 {@code contracts/market-dataset.md} 「읽기 규칙」). 요청 시작일을 거래일에 맞추는 일은 {@link
     * #firstTradingDayOnOrAfter}가 맡는다 — 워밍업을 소진하지 않는다는 점에서 하는 일이 다르다.
     *
     * <p>달력이 모자라면 비어 있고, 호출자는 422 {@code INSUFFICIENT_HISTORY}로 거절한다(R-15).
     */
    Optional<LocalDate> evaluationStart(LocalDate notBefore, int warmupTradingDays);

    /**
     * {@code date} 당일 또는 그 뒤 첫 거래일. <b>워밍업을 소진하지 않는다.</b>
     *
     * <p>사용자가 고른 시작일을 그대로 쓰되 휴장일이면 다음 거래일로 맞추는 용도다 — 주말을 평가 첫날로 삼으면 워커가 고르는 첫 거래일과 값이 갈린다. 달력 끝까지
     * 거래일이 하나도 없으면 비어 있다.
     */
    Optional<LocalDate> firstTradingDayOnOrAfter(LocalDate date);

    /**
     * 지금 자료 버전이 담은 <b>마지막 거래일</b>. 달력이 비어 있으면 비어 있다.
     *
     * <p>끝일을 받지 않는 경로(spec 007 자동 탐색)가 기본값으로 쓴다. <b>오늘을 쓰면 안 된다</b> — 수집은 늘 과거 어느 날까지만 완료돼 있고, 오늘이
     * 그보다 뒤인 날(주말·공휴일이거나 수집이 하루라도 밀린 날)에는 요청 구간이 자료 밖으로 나가 워커가 후보를 전부 {@code DATA_INSUFFICIENT}로
     * 거절한다. 004 단일 실행은 001 {@code evaluation-window}의 {@code commonEnd}를 실어 보내 같은 자리를 이미 피하고 있다.
     *
     * <p>기본 구현이 비어 있는 것은 시험용 대역을 위해서다. 실제 조회는 {@code DatabaseMarketMetadataAdapter}가 한다.
     */
    default Optional<LocalDate> lastTradingDay() {
        return Optional.empty();
    }
}
