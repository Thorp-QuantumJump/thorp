package com.ssafy.thorp.myrecord.application.service;

import com.ssafy.thorp.backtest.application.event.BacktestRequestAccepted;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordRef;
import com.ssafy.thorp.myrecord.domain.vo.AnalysisPeriod;
import com.ssafy.thorp.paramsearch.application.event.ParamSearchAccepted;
import com.ssafy.thorp.sector.application.repository.SectorRepository;
import com.ssafy.thorp.sector.application.result.SectorListResult;
import com.ssafy.thorp.sector.domain.model.Sector;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 접수 이벤트를 듣고 기록 항목을 만든다(spec 006 data-model §4, research R16).
 *
 * <p><b>004·007이 006을 부르지 않는다.</b> 접수 서비스를 직접 부르면 backtest → myrecord → backtest 순환 참조가 생긴다(목록 질의가
 * 실행·요청을 읽는다). 이벤트로 받으면 방향이 하나로 남는다.
 *
 * <p><b>{@code BEFORE_COMMIT}인 이유</b>: 접수와 항목이 같은 트랜잭션에 묶여야 둘 중 하나만 남지 않는다. 접수가 끝난 뒤 항목 저장이 실패하면
 * 사용자는 "분석은 돌고 있는데 목록에 없는" 상태를 보게 된다.
 */
@Service
public class MyRecordRegistrar {

    private static final Logger log = LoggerFactory.getLogger(MyRecordRegistrar.class);

    private final MyRecordRepository records;
    private final RecordNamer namer;
    private final SectorRepository sectorRepository;
    private final Clock clock;

    public MyRecordRegistrar(
            MyRecordRepository records,
            RecordNamer namer,
            SectorRepository sectorRepository,
            Clock clock) {
        this.records = records;
        this.namer = namer;
        this.sectorRepository = sectorRepository;
        this.clock = clock;
    }

    /**
     * 단일 분석 접수 → {@code ANALYSIS} 항목.
     *
     * <p>세 경우에 항목을 만들지 않는다.
     *
     * <ul>
     *   <li><b>비로그인 요청</b> — 기록은 식별된 사용자 단위로만 존재한다(FR-020). 진단 자체는 그대로 되고 저장이 필요한 지점에서 014의 로그인 안내를
     *       부른다
     *   <li><b>탐색 후보가 만든 실행</b> — 묶음 항목이 그것을 대표한다(data-model §4)
     *   <li><b>같은 실행을 가리키는 살아 있는 항목이 이미 있을 때</b> — 004의 동일 결과 재사용으로 요청은 새로 생겨도 실행은 같다(FR-009). 스키마의
     *       {@code uk_my_record_analysis}도 같은 것을 막지만, 여기서 먼저 보고 넘어가야 정상적인 재분석이 제약 위반으로 500이 되지 않는다
     * </ul>
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(BacktestRequestAccepted event) {
        if (event.requesterId() == null || event.executionType() != ExecutionType.SINGLE) {
            return;
        }
        long ownerId = event.requesterId();
        // 같은 실행을 가리키는 항목이 이미 있으면 그대로 둔다(FR-009). 기록은 요청이 아니라 실행을
        // 가리키므로 새 요청이 생겨도 고칠 것이 없다.
        if (records.findActiveByOwnerAndRun(ownerId, event.runId()).isPresent()) {
            return;
        }
        List<String> sectorCodes =
                event.sectors().stream()
                        .map(BacktestRequestAccepted.AcceptedSector::sectorCode)
                        .toList();
        records.save(
                MyRecord.create(
                        ownerId,
                        new RecordRef.Analysis(event.runId()),
                        namer.forAnalysis(
                                byWeightDesc(event.sectors()),
                                event.strategyId(),
                                LocalDate.now(clock)),
                        sectorCodes,
                        List.of(event.strategyId()),
                        AnalysisPeriod.ofNullable(event.periodStart(), event.periodEnd()),
                        event.acceptedAt()));
    }

    /**
     * 탐색 접수 → {@code SEARCH_BUNDLE} 항목.
     *
     * <p>거절된 접수는 이벤트 자체가 오지 않는다(007이 실행이 시작되는 갈래에서만 발행한다). 같은 묶음을 가리키는 항목이 이미 있으면 넘어간다 — 접수는 한 번뿐이라
     * 정상 경로에서는 일어나지 않지만, 이벤트가 두 번 전달돼도 항목이 둘이 되지 않아야 한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(ParamSearchAccepted event) {
        if (event.ownerUserId() == null) {
            return;
        }
        long ownerId = event.ownerUserId();
        if (records.findActiveByOwnerAndParamSearch(ownerId, event.paramSearchId()).isPresent()) {
            return;
        }
        List<String> sectorCodes = sectorCodesOf(event.instrumentIds());
        records.save(
                MyRecord.create(
                        ownerId,
                        new RecordRef.SearchBundle(event.paramSearchId()),
                        namer.forSearchBundle(
                                sectorCodes, event.strategyIds(), LocalDate.now(clock)),
                        sectorCodes,
                        event.strategyIds(),
                        AnalysisPeriod.ofNullable(event.periodStart(), event.periodEnd()),
                        event.acceptedAt()));
    }

    /** 비중이 큰 섹터가 앞이다. 비중이 없는 전략(규칙이 정하는 배분)은 요청 순서를 지킨다. */
    private static List<String> byWeightDesc(List<BacktestRequestAccepted.AcceptedSector> sectors) {
        return sectors.stream()
                .sorted(
                        Comparator.comparing(
                                BacktestRequestAccepted.AcceptedSector::targetWeight,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(BacktestRequestAccepted.AcceptedSector::sectorCode)
                .toList();
    }

    /**
     * 탐색 대상 종목을 섹터 코드로 옮긴다. 007은 섹터를 다루지 않고 종목으로 접수한다.
     *
     * <p><b>현재 대응표에 없는 종목은 건너뛴다.</b> 목록 필터에 쓰는 값이라 없는 섹터를 지어내지 않는다(원칙 VI). 대응표를 읽지 못하면 빈 목록이다 — 섹터
     * 필터에서 빠질 뿐 접수를 막지는 않는다.
     */
    private List<String> sectorCodesOf(List<String> instrumentIds) {
        Map<String, String> byInstrument;
        try {
            byInstrument =
                    sectorRepository
                            .findOpenSectorMap()
                            .map(SectorListResult::sectors)
                            .orElse(List.of())
                            .stream()
                            .collect(
                                    Collectors.toMap(
                                            sector -> sector.representativeEtf().instrumentId(),
                                            (Sector sector) -> sector.code().value(),
                                            (first, second) -> first));
        } catch (RuntimeException e) {
            log.warn("대응표를 읽지 못해 탐색 묶음의 섹터를 비운다", e);
            return List.of();
        }
        return instrumentIds.stream().map(byInstrument::get).filter(code -> code != null).toList();
    }
}
