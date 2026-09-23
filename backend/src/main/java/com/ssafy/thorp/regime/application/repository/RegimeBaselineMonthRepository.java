package com.ssafy.thorp.regime.application.repository;

import com.ssafy.thorp.regime.domain.model.RegimeBaselineMonth;
import java.util.List;
import java.util.Optional;

/**
 * 배치가 기록한 기준월을 읽는다 (data-model §3).
 *
 * <p><b>008에 직접 묻지 않는다</b>(계약 §0.2). 008이 올린 최신 T_g는 배치가 아직 계산하지 않았을 수 있고, 그 달로 검색하면 404가 난다 — 화면이
 * 고를 수 있는 달은 결과가 실제로 있는 달뿐이다.
 */
public interface RegimeBaselineMonthRepository {

    List<RegimeBaselineMonth> findAll();

    Optional<RegimeBaselineMonth> findByScope(String scopeId);
}
