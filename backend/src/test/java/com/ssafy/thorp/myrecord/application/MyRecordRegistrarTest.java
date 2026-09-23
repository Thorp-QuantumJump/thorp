package com.ssafy.thorp.myrecord.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.event.BacktestRequestAccepted;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.myrecord.application.port.RegimeSearchLogWriter;
import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.application.service.MyRecordRegistrar;
import com.ssafy.thorp.myrecord.application.service.RecordNamer;
import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordRef;
import com.ssafy.thorp.myrecord.domain.vo.RecordName;
import com.ssafy.thorp.paramsearch.application.event.ParamSearchAccepted;
import com.ssafy.thorp.regime.application.port.MacroIndicatorPort;
import com.ssafy.thorp.regime.application.repository.RegimeSnapshotRepository;
import com.ssafy.thorp.sector.application.repository.SectorRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 접수 이벤트를 어떤 조건에서 기록으로 옮기는지 고정한다(spec 006 FR-009·FR-020, data-model §4).
 *
 * <p>여기서 보는 것은 <b>만들지 않는 경우</b>다. 만드는 경우는 스키마 제약까지 함께 지켜야 하므로 {@code MyRecordRepositoryIT}가 본다.
 */
class MyRecordRegistrarTest {

    private static final Instant NOW = Instant.parse("2026-09-20T02:00:00Z");
    private static final long OWNER = 7L;
    private static final long RUN_ID = 42L;

    private MyRecordRepository records;
    private RecordNamer namer;
    private MyRecordRegistrar registrar;

    @BeforeEach
    void setUp() {
        records = mock(MyRecordRepository.class);
        namer = mock(RecordNamer.class);
        SectorRepository sectors = mock(SectorRepository.class);
        when(sectors.findOpenSectorMap()).thenReturn(Optional.empty());
        when(namer.forAnalysis(any(), any(), any())).thenReturn(RecordName.of("이름"));
        when(namer.forSearchBundle(any(), any(), any())).thenReturn(RecordName.of("이름"));
        registrar =
                new MyRecordRegistrar(
                        records,
                        namer,
                        sectors,
                        mock(RegimeSearchLogWriter.class),
                        mock(RegimeSnapshotRepository.class),
                        mock(MacroIndicatorPort.class),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("비로그인 요청은 기록을 만들지 않는다 — 기록은 식별된 사용자 단위로만 존재한다(FR-020)")
    void 비로그인_요청은_항목을_만들지_않는다() {
        registrar.on(accepted(null, ExecutionType.SINGLE));

        verify(records, never()).save(any());
    }

    @Test
    @DisplayName("탐색 후보가 만든 실행은 분석 항목이 되지 않는다 — 묶음 항목이 대표한다")
    void 탐색_후보_실행은_분석_항목을_만들지_않는다() {
        registrar.on(accepted(OWNER, ExecutionType.SEARCH_BUNDLE));

        verify(records, never()).save(any());
    }

    @Test
    @DisplayName("같은 실행에 살아 있는 항목이 있으면 새로 만들지 않는다 — 동일 결과 재사용(FR-009)")
    void 같은_실행의_두_번째_요청은_항목을_만들지_않는다() {
        when(records.findActiveByOwnerAndRun(OWNER, RUN_ID))
                .thenReturn(Optional.of(existingRecord()));

        registrar.on(accepted(OWNER, ExecutionType.SINGLE));

        // 기록은 요청이 아니라 실행을 가리키므로 새 요청이 생겨도 고칠 것이 없다.
        verify(records, never()).save(any());
    }

    @Test
    @DisplayName("지운 항목만 남아 있으면 같은 실행이라도 새 항목을 만든다")
    void 지운_항목_뒤의_같은_실행은_새_항목을_만든다() {
        // 저장소의 find* 는 살아 있는 항목만 본다. 지운 항목은 빈 값으로 돌아온다.
        when(records.findActiveByOwnerAndRun(OWNER, RUN_ID)).thenReturn(Optional.empty());

        registrar.on(accepted(OWNER, ExecutionType.SINGLE));

        ArgumentCaptor<MyRecord> saved = ArgumentCaptor.forClass(MyRecord.class);
        verify(records).save(saved.capture());
        Assertions.assertThat(saved.getValue().ref()).isEqualTo(new RecordRef.Analysis(RUN_ID));
    }

    @Test
    @DisplayName("비중이 큰 섹터가 이름의 앞에 온다(FR-003)")
    void 이름은_비중이_큰_섹터를_앞에_둔다() {
        registrar.on(
                new BacktestRequestAccepted(
                        11L,
                        RUN_ID,
                        OWNER,
                        ExecutionType.SINGLE,
                        List.of(
                                new BacktestRequestAccepted.AcceptedSector(
                                        "BANK", new BigDecimal("0.3")),
                                new BacktestRequestAccepted.AcceptedSector(
                                        "SEMICONDUCTOR", new BigDecimal("0.7"))),
                        "FIXED_WEIGHT",
                        LocalDate.of(2019, 1, 2),
                        LocalDate.of(2026, 8, 29),
                        NOW));

        ArgumentCaptor<List<String>> naming = ArgumentCaptor.forClass(List.class);
        verify(namer).forAnalysis(naming.capture(), any(), any());
        Assertions.assertThat(naming.getValue()).containsExactly("SEMICONDUCTOR", "BANK");
    }

    @Test
    @DisplayName("탐색 묶음도 같은 묶음을 가리키는 항목이 있으면 새로 만들지 않는다")
    void 같은_묶음의_두_번째_이벤트는_항목을_만들지_않는다() {
        when(records.findActiveByOwnerAndParamSearch(anyLong(), anyLong()))
                .thenReturn(Optional.of(existingRecord()));

        registrar.on(
                new ParamSearchAccepted(
                        99L,
                        OWNER,
                        List.of("SECTOR_MOMENTUM"),
                        List.of("091160.XKRX"),
                        LocalDate.of(2019, 1, 2),
                        LocalDate.of(2026, 8, 29),
                        NOW));

        verify(records, never()).save(any());
    }

    private static BacktestRequestAccepted accepted(Long requesterId, ExecutionType type) {
        return new BacktestRequestAccepted(
                11L,
                RUN_ID,
                requesterId,
                type,
                List.of(new BacktestRequestAccepted.AcceptedSector("BANK", null)),
                "EQUAL_WEIGHT",
                LocalDate.of(2019, 1, 2),
                LocalDate.of(2026, 8, 29),
                NOW);
    }

    private static MyRecord existingRecord() {
        return MyRecord.create(
                OWNER,
                new RecordRef.Analysis(RUN_ID),
                RecordName.of("기존"),
                List.of("BANK"),
                List.of("EQUAL_WEIGHT"),
                null,
                NOW);
    }
}
