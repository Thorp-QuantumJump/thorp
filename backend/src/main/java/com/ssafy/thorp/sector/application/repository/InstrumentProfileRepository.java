package com.ssafy.thorp.sector.application.repository;

import com.ssafy.thorp.sector.application.result.InstrumentProfileResult;
import java.util.Optional;

/**
 * 대표 상품 정보 조회. <b>읽기 전용이다.</b>
 *
 * <p>원본은 {@code data/} 파이프라인이 소유하고({@code data/src/data_pipeline/instrument_profile.py}) 백엔드에는 쓰기
 * 경로가 없다. 이유는 대응표와 같다 — 양방향을 허용하면 같은 값이 두 곳에서 갈라지고, 발행된 자료 버전을 고치지 않는다는 FR-017을 누가 책임지는지 흐려진다.
 */
public interface InstrumentProfileRepository {

    /**
     * 지금 보여 줄 대표 상품 정보.
     *
     * <p><b>어느 행을 고르는가</b>는 문서가 정해 두지 않아 여기서 정한다. 조건은 셋이다.
     *
     * <ol>
     *   <li>{@code mapVersion}(지금 열린 대응표 버전)을 대상으로 수집된 자료 버전일 것. 물러난 대응표를 대상으로 만든 값을 현재 목록 옆에 두면 두
     *       응답이 다른 판을 가리킨다
     *   <li>그 자료 버전이 완전할 것({@code complete = true}). 이용 한도에 걸려 부분만 갱신된 자료를 완전한 자료로 표시하지 않는다(FR-014)
     *   <li>그중 {@code data_deadline}이 가장 늦은 것
     * </ol>
     *
     * @return 조건에 맞는 행이 없으면 {@link Optional#empty()}. 호출자는 이것을 빈 값이 아니라 "아직 만들어지지 않음"으로 다룬다
     */
    Optional<InstrumentProfileResult> findCurrentProfile(String mapVersion, String instrumentId);
}
