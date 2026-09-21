package com.ssafy.thorp.myrecord.application.result;

import java.util.Map;

/**
 * 원본이 남긴 참조 버전의 <b>원문</b>. 이름을 바꾸는 것은 {@code MyRecordQueryService}가 한다.
 *
 * <ul>
 *   <li>{@code ANALYSIS}: {@code versions} = {@code backtest_run.versions}(접수가 확정한 값이라 실패한 실행에도
 *       있다), {@code spec} = {@code backtest_run.request_spec}
 *   <li>{@code SEARCH_BUNDLE}: {@code versions} = {@code param_search.reference_versions}, {@code
 *       spec} = {@code param_search.execution_plan}(대응표 버전이 여기 있다)
 * </ul>
 *
 * <p>경제환경 항목은 여기 없다 — 스냅샷 키 자체가 자료·등록표·모델 버전을 품는다({@code SnapshotKey}).
 */
public record SourceVersions(Map<String, Object> versions, Map<String, Object> spec) {

    public SourceVersions {
        versions = versions == null ? Map.of() : Map.copyOf(versions);
        spec = spec == null ? Map.of() : Map.copyOf(spec);
    }
}
