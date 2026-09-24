package com.ssafy.thorp.myrecord.application.service;

import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.service.ExecutionPolicy;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery;
import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.application.result.MyRecordDetail;
import com.ssafy.thorp.myrecord.application.result.MyRecordPage;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.application.result.SourceVersions;
import com.ssafy.thorp.myrecord.application.result.UsedStrategy;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.regime.domain.vo.SnapshotKey;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 003이 다루는 시나리오. 다른 시나리오의 기록은 '설정 바꿔 비교'를 열지 않는다(research R4).
     *
     * <p>문자열을 적지 않고 {@link Scenario}에서 가져온다 — 적어 두면 그 상수가 이름을 바꾸거나 사라져도 여기는 조용히 지나가고, 모든 기록의 버튼이
     * 사라진 뒤에야 드러난다. 이름이 바뀌면 빌드가 깨지는 쪽이 낫다.
     */
    private static final String REOPENABLE_SCENARIO = Scenario.FULL_PERIOD.name();

    private final MyRecordRepository records;
    private final MyRecordMappingDriftChecker driftChecker;
    private final MyRecordRegistryDriftChecker registryDriftChecker;
    private final BacktestRunRepository runs;
    private final Clock clock;

    public MyRecordQueryService(
            MyRecordRepository records,
            MyRecordMappingDriftChecker driftChecker,
            MyRecordRegistryDriftChecker registryDriftChecker,
            BacktestRunRepository runs,
            Clock clock) {
        this.records = records;
        this.driftChecker = driftChecker;
        this.registryDriftChecker = registryDriftChecker;
        this.runs = runs;
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
        return detailOf(view.viewedAt(now));
    }

    /**
     * 갱신한 항목을 그대로 돌려줄 때(이름 변경 응답). <b>열람 표시를 남기지 않는다.</b>
     *
     * <p>{@code last_viewed_at}은 "'다시 보기'로 열었다"는 뜻이고 보관 기간 판단(PD-13)에 쓰려고 남겨 둔 값이다. 이름을 고친 기록이 열람한
     * 것으로 세어지면 그 판단이 조용히 어긋난다 — {@link #item}과 같은 규칙이다.
     */
    @Transactional(readOnly = true)
    public MyRecordDetail current(long ownerId, String recordId) {
        return detailOf(requireView(ownerId, recordId));
    }

    /** 펼쳐 보는 층을 조립한다. 열람 표시 여부는 부른 쪽이 정한다. */
    private MyRecordDetail detailOf(MyRecordView view) {
        Map<String, Object> referenceVersions = referenceVersions(view);
        Map<String, Object> reopenPayload = reopenPayload(view);
        return new MyRecordDetail(
                view,
                referenceVersions,
                driftChecker
                        .check(
                                (String) referenceVersions.get("sectorEtfMapVersion"),
                                view.sectorCodes())
                        .orElse(null),
                registryDriftChecker.check(reopenPayload).orElse(null),
                new MyRecordDetail.ReopenSettings(reopenable(view, reopenPayload)),
                reopenPayload);
    }

    /**
     * 저장 당시 실행 조건. <b>005의 조립기를 그대로 부른다</b>(research R4).
     *
     * <p>006이 요청 사본을 직접 꺼내 모양을 맞추면 005의 {@code config}와 두 벌이 되고, 그때부터 결과 화면과 기록 화면이 다른 조건을 보인다. 화면은
     * 이 값을 {@code prefillStrategySettings}에 손대지 않고 넘긴다.
     *
     * <p>{@code ANALYSIS}가 아니면 null이다 — 탐색 묶음과 경제환경 기록에는 열어 볼 단일 실행이 없다. 실행 행이 사라졌을 때도 null이고, 그러면
     * 응답에서 키가 빠진다(원칙 VI).
     */
    private Map<String, Object> reopenPayload(MyRecordView view) {
        if (view.kind() != RecordKind.ANALYSIS || view.analysisRunId() == null) {
            return null;
        }
        return runs.findById(view.analysisRunId())
                .map(run -> BacktestDetailResult.config(run, ExecutionPolicy.CURRENCY))
                .orElse(null);
    }

    /**
     * '설정 바꿔 비교'를 열 수 있는가.
     *
     * <p>005의 {@code prefillStrategySettings}가 {@code FULL_PERIOD}가 아닌 시나리오를 거절한다 — 003 화면이 목표 위험
     * 맞춤 같은 시나리오를 다루지 않기 때문이다(research R4). 열 수 없는 것을 열어 두면 눌러 본 뒤에 실패한다.
     *
     * <p>시나리오가 <b>적혀 있지 않은</b> 사본은 주 분석으로 본다 — 접수가 생략을 {@code FULL_PERIOD}로 읽는 것과 같은 규칙이다.
     */
    private static boolean reopenable(MyRecordView view, Map<String, Object> config) {
        if (view.kind() != RecordKind.ANALYSIS || config == null) {
            return false;
        }
        Object scenario = config.get("scenario");
        return scenario == null || REOPENABLE_SCENARIO.equals(String.valueOf(scenario));
    }

    /**
     * 참조 버전을 계약 이름으로 옮긴다(contracts "referenceVersions의 출처"). <b>이름만 바꾸고 값을 만들지 않는다.</b> 원본에 없는 키는
     * 만들지 않고 {@code worker}·{@code dependencies}는 담지 않는다(운영 정보, 005 {@code
     * PublicResultService.withoutWorkerReport}와 같은 판단).
     *
     * <p>{@code rulesVersion}·{@code costProfileVersion}은 {@code {version, provisional}} 꼴 그대로다 —
     * 잠정 여부가 함께 보여야 사용자가 확정 규칙 결과와 임시 규칙 결과를 구분한다.
     *
     * <p>내보내기({@code MyRecordExportService})가 같은 값을 파일에 담으므로 패키지 안에서 부를 수 있다. 상세({@link #detail})를
     * 거쳐 받지 않는 이유: 상세는 열람 표시를 남기고 실행 조회({@code reopenPayload})와 등록표 판정({@code registryDrift})까지 딸려
     * 온다 — 내보내기는 '다시 보기'가 아니고 그 둘이 필요하지도 않다.
     */
    Map<String, Object> referenceVersions(MyRecordView view) {
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

    /** 요청 이력을 조합 단위로 접을 때 한 번에 읽는 요청 수. 같은 조합을 되풀이한 사용자라도 이 안에서 스무 조합은 나온다. */
    private static final int USED_STRATEGY_SCAN = 200;

    /** 후보 상한. 비교 목록이 이보다 길면 고르는 화면이 아니라 검색 화면이 된다(원칙 II). */
    private static final int USED_STRATEGY_LIMIT = 20;

    /**
     * 이 사용자가 단일 분석에서 써 본 전략·설정값 조합, 최근 순·중복 없이({@code GET /my-records/used-strategies}, 005 비교 후보).
     *
     * <p>조합의 같음은 {@code strategyId}와 {@code parameters}의 구조적 같음이다 — 접수가 저장한 JSON 이라 키 순서는 같음에 영향이
     * 없다. 규칙 버전은 같음에 넣지 않는다. 같은 설정을 규칙 버전이 바뀐 뒤 다시 돌렸어도 사용자에게는 한 조합이다.
     *
     * <p>이름은 그 실행을 가리키는 <b>살아 있는</b> 내 기록 항목에서 읽는다. 지운 항목은 이름을 주지 않으므로 화면은 전략 이름으로 대신한다 (FR-017).
     * 여기가 006 안에 있는 이유다 — 004 는 기록 이름을 모른다.
     */
    @Transactional(readOnly = true)
    public List<UsedStrategy> usedStrategies(long ownerId) {
        Map<List<Object>, UsedStrategy> byCombination = new LinkedHashMap<>();
        for (BacktestRun run :
                runs.findRecentRequestedBy(ownerId, ExecutionType.SINGLE, USED_STRATEGY_SCAN)) {
            List<Object> key = List.of(run.getStrategyId(), run.getParameters());
            if (byCombination.containsKey(key)) {
                continue;
            }
            String recordName =
                    records.findActiveByOwnerAndRun(ownerId, run.getId())
                            .map(record -> record.name().value())
                            .orElse(null);
            byCombination.put(
                    key,
                    new UsedStrategy(
                            run.getStrategyId(),
                            recordName,
                            run.getPlan().rulesVersion(),
                            run.getParameters()));
            if (byCombination.size() >= USED_STRATEGY_LIMIT) {
                break;
            }
        }
        return List.copyOf(byCombination.values());
    }
}
