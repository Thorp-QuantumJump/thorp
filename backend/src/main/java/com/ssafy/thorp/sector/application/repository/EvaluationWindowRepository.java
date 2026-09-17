package com.ssafy.thorp.sector.application.repository;

import com.ssafy.thorp.sector.application.result.EvaluationWindowInputs;
import java.util.List;
import java.util.Optional;

/**
 * 공통 평가 가능 기간 산식의 재료 조회. <b>읽기 전용이다.</b>
 *
 * <p>대응표·자료 버전·시계열의 원본은 {@code data/} 파이프라인이 소유하고 백엔드에는 쓰기 경로가 없다(research.md 결정 7). 이유는 {@link
 * SectorRepository}·{@link InstrumentProfileRepository}와 같다.
 */
public interface EvaluationWindowRepository {

    /**
     * 후보 집합의 산식 재료를 한 번에 읽는다.
     *
     * <p><b>어느 자료 버전을 고르는가</b>는 대표 상품 정보와 같은 규칙이다({@link
     * InstrumentProfileRepository#findCurrentProfile}). 지금 열린 대응표를 대상으로 수집됐고, 완전하며({@code complete
     * = true}), 그중 {@code data_deadline}이 가장 늦은 것이다. 세 조건을 대표 상품 정보와 다르게 두면 같은 화면의 두 응답이 다른 판을
     * 가리킨다.
     *
     * <p><b>거래일 달력은 후보의 가장 이른 상장일부터만 읽는다.</b> 산식이 쓰는 것은 상장일 이후 구간뿐이라 그 앞을 읽어도 결과가 달라지지 않고, 전체를 읽으면
     * 오래된 지수의 날짜 수천 건이 매 요청마다 딸려 온다.
     *
     * @param mapVersion 지금 열린 대응표 버전
     * @param instrumentIds 후보 종목. <b>돌려주는 목록은 이 순서를 보존한다</b> — 산식의 동점 규칙이 입력 순서를 쓴다
     * @return 쓸 수 있는 자료 버전이 없거나 그 버전에 거래일이 하나도 없으면 {@link Optional#empty()}. 호출자는 이것을 빈 값이 아니라 "아직
     *     산출할 수 없음"으로 다룬다
     */
    Optional<EvaluationWindowInputs> findInputs(String mapVersion, List<String> instrumentIds);
}
