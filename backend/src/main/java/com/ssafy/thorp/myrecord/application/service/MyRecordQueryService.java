package com.ssafy.thorp.myrecord.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery;
import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.application.result.MyRecordDetail;
import com.ssafy.thorp.myrecord.application.result.MyRecordPage;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.application.result.SourceVersions;
import com.ssafy.thorp.regime.domain.vo.SnapshotKey;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 기록 목록·상세 읽기(spec 006 US1).
 *
 * <p><b>남의 기록은 403이 아니라 404다</b>(FR-020, contracts 공통). 403은 그 식별자가 존재한다는 사실을 알려 준다. 없는 것·지운 것·남의
 * 것을 한 가지로 답하므로 저장소도 셋을 구분하지 않고 빈 값을 돌려준다.
 */
@Service
public class MyRecordQueryService {

    private final MyRecordRepository records;
    private final MyRecordMappingDriftChecker driftChecker;
    private final Clock clock;

    public MyRecordQueryService(
            MyRecordRepository records, MyRecordMappingDriftChecker driftChecker, Clock clock) {
        this.records = records;
        this.driftChecker = driftChecker;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MyRecordPage list(long ownerId, MyRecordListQuery query) {
        return records.findPage(ownerId, query);
    }

    /**
     * 한 항목의 목록 줄. 기록 요청({@code POST /my-records/regime-searches})의 응답이 목록 항목과 같은 본문이라 그 경로가 쓴다.
     *
     * <p>상세와 달리 열람 표시를 남기지 않는다 — 방금 만든 항목을 돌려주는 것이지 사용자가 다시 본 것이 아니다.
     */
    @Transactional(readOnly = true)
    public MyRecordView item(long ownerId, String recordId) {
        return requireView(ownerId, recordId);
    }

    /**
     * 한 항목의 상세. 읽는 순간을 '다시 보기'로 남긴다 — 상세는 결과 화면이 기록에서 들어왔을 때({@code ?recordId=})와 펼쳐 보는 층이 부르는 경로라
     * 열람 표시를 따로 받지 않는다. 시각 하나만 남기고 자동 삭제에는 쓰지 않는다(2026-09-18 명료화).
     */
    @Transactional
    public MyRecordDetail detail(long ownerId, String recordId) {
        MyRecordView view = requireView(ownerId, recordId);
        Instant now = clock.instant();
        records.markViewed(view.id(), now);
        Map<String, Object> referenceVersions = referenceVersions(view);
        return new MyRecordDetail(
                view.viewedAt(now),
                referenceVersions,
                driftChecker
                        .check(
                                (String) referenceVersions.get("sectorEtfMapVersion"),
                                view.sectorCodes())
                        .orElse(null));
    }

    /**
     * 참조 버전을 계약 이름으로 옮긴다(contracts "referenceVersions의 출처"). <b>이름만 바꾸고 값을 만들지 않는다.</b> 원본에 없는 키는
     * 만들지 않고 {@code worker}·{@code dependencies}는 담지 않는다(운영 정보, 005 {@code
     * PublicResultService.withoutWorkerReport}와 같은 판단).
     *
     * <p>{@code rulesVersion}·{@code costProfileVersion}은 {@code {version, provisional}} 꼴 그대로다 —
     * 잠정 여부가 함께 보여야 사용자가 확정 규칙 결과와 임시 규칙 결과를 구분한다.
     */
    private Map<String, Object> referenceVersions(MyRecordView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (view.kind()) {
            case ANALYSIS -> {
                SourceVersions src = source(view);
                put(out, "datasetVersion", src.versions().get("data"));
                put(out, "sectorEtfMapVersion", src.versions().get("mapping"));
                put(out, "rulesVersion", src.versions().get("rules"));
                put(out, "costProfileVersion", src.versions().get("costProfile"));
                put(out, "engineVersion", src.versions().get("engine"));
                put(out, "registryVersion", src.spec().get("registryVersion"));
            }
            case SEARCH_BUNDLE -> {
                SourceVersions src = source(view);
                put(out, "datasetVersion", src.versions().get("marketData"));
                put(out, "sectorEtfMapVersion", src.spec().get("mappingVersion"));
                put(out, "registryVersion", src.versions().get("registry"));
                put(out, "costProfileVersion", src.versions().get("cost"));
                put(out, "engineVersion", src.versions().get("engine"));
            }
            case REGIME_SEARCH -> {
                SnapshotKey key = view.regime().key();
                out.put("datasetVersion", key.dataVersion());
                out.put("registryVersion", key.registryVersion());
                out.put("modelVersion", key.modelVersion());
            }
        }
        return out;
    }

    /** 없는 것·지운 것·남의 것을 한 가지 404 로 답한다. 두 읽기 경로가 같은 문장을 쓰도록 한 곳에 둔다. */
    private MyRecordView requireView(long ownerId, String recordId) {
        return records.findView(ownerId, recordId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND,
                                        "기록을 찾을 수 없습니다: " + recordId));
    }

    private SourceVersions source(MyRecordView view) {
        return records.findSourceVersions(view.id(), view.kind())
                .orElseGet(() -> new SourceVersions(Map.of(), Map.of()));
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
