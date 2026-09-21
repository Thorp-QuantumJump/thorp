package com.ssafy.thorp.myrecord.application.result;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 *
 * <p>값이 null 인 키를 허용한다. 요청 사본({@code RequestSpecCodec})은 {@code mappingVersion} 같은 선택 항목을 null 로 넣을
 * 수 있고, 저장 규약(null 키 생략)에 기대어 {@code Map.copyOf}를 쓰면 그 규약이 바뀐 날 상세가 500 이 된다.
 */
public record SourceVersions(Map<String, Object> versions, Map<String, Object> spec) {

    public SourceVersions {
        versions = nullTolerantCopy(versions);
        spec = nullTolerantCopy(spec);
    }

    private static Map<String, Object> nullTolerantCopy(Map<String, Object> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
