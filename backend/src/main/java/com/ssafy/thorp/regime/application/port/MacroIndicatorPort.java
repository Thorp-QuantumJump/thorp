package com.ssafy.thorp.regime.application.port;

import java.util.List;
import java.util.Optional;

/**
 * 010이 008(거시지표 등록표)에서 읽는 유일한 통로 — <b>표시용 등록표뿐이다</b>.
 *
 * <p>계약은 {@code specs/010-economic-regime-search/contracts/macro-indicator-port.md} §2다. 004가 001
 * 없이 {@code MarketMetadataPort}로, 007이 003 없이 {@code ParameterRegistryPort}로 시작한 것과 같은 모양이며, 008이
 * 완성되면 구현체만 바뀐다.
 *
 * <p><b>시계열 메서드를 두지 않는다</b>(계약 §0.1). 010의 계산은 전부 {@code data/} 배치에 있고 Backend는 적재된 행을 읽어 내보낼 뿐이라,
 * 여기에 {@code monthlySeries()}를 두면 <b>호출자가 없는 메서드를 픽스처와 008 구현체 양쪽이 구현</b>하게 된다. 루트 {@code
 * AGENTS.md}의 "Backend는 계산하지 않는다"가 그대로 적용되는 자리다.
 *
 * <p><b>기준월(T_g·T_all)도 여기서 읽지 않는다</b>(계약 §0.2). 008은 새 발표분이 들어오는 즉시 T_g를 올리지만 사전 계산 배치는 그 뒤에 돈다 —
 * 그 사이 화면이 008의 최신 T_g로 검색하면 아직 snapshot이 없는 달이라 404가 난다. 화면이 고를 수 있는 달은 <b>결과가 실제로 있는 달</b>이어야 하므로
 * 배치가 {@code regime_baseline_month}에 남긴 값을 읽는다.
 */
public interface MacroIndicatorPort {

    /**
     * 현재 등록표 버전 (008 FR-003).
     *
     * <p>임시 대역의 값은 {@code FIXTURE-}로 시작한다. 응답의 {@code versions.registry}에 그대로 실려 값이 어느 경로로 흘러가도
     * 가짜임이 드러난다.
     */
    String registryVersion();

    /** 분야 목록 — id·이름·표시 순서 (008 FR-002·FR-003). 화면이 분야를 고르는 자리에 쓴다. */
    List<FieldRegistration> fields();

    /**
     * 지표 하나의 이름·단위 (008 FR-002). 등록되지 않았으면 {@link Optional#empty()}다.
     *
     * <p><b>빈 값을 이름 없는 대체물로 바꾸지 않는다.</b> "(알 수 없음)" 같은 문구를 여기서 지어내면 등록표에서 빠진 지표와 오타 난 id가 화면에서 같아
     * 보이고, 그 차이를 되짚을 곳이 사라진다.
     */
    Optional<IndicatorRegistration> indicator(String indicatorId);
}
