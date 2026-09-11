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
     * {@code notBefore} 이후 워밍업 거래일을 소진하고 나서 평가를 시작할 수 있는 첫 거래일.
     *
     * <p>{@code warmupTradingDays == 0}이면 {@code notBefore} 당일 또는 그 뒤 첫 거래일이다. 달력이 모자라면 비어 있고, 호출자는
     * 422 {@code INSUFFICIENT_HISTORY}로 거절한다(R-15).
     */
    Optional<LocalDate> evaluationStart(LocalDate notBefore, int warmupTradingDays);
}
