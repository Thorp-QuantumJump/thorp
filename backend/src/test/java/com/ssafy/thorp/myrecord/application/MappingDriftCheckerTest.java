package com.ssafy.thorp.myrecord.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.myrecord.application.port.SectorMapEntries;
import com.ssafy.thorp.myrecord.application.result.MappingDrift;
import com.ssafy.thorp.myrecord.application.service.MyRecordMappingDriftChecker;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UI-01 판정의 세 경우(quickstart S5)와 판정 불가를 고정한다(spec 006 research R8).
 *
 * <p>첫 검사가 가장 중요하다 — 버전 번호만 올랐을 때 알림이 뜨면 UI-01 결정과 원칙 II를 함께 어긴다.
 */
class MappingDriftCheckerTest {

    private static final String SAVED = "SEM-1";
    private static final List<SectorMapEntries.Entry> SAVED_ENTRIES =
            List.of(
                    new SectorMapEntries.Entry("SEMICONDUCTOR", "반도체", 1L),
                    new SectorMapEntries.Entry("BATTERY", "2차전지", 2L),
                    new SectorMapEntries.Entry("BIO", "바이오", 3L));

    @Test
    @DisplayName("다른 섹터만 바뀐 새 버전이면 바뀌지 않은 것이다 — 버전 문자열이 달라도 알리지 않는다")
    void 다른_섹터만_바뀌면_변경이_아니다() {
        // 현재 버전은 BIO 의 ETF 만 다르다. 이 기록은 반도체·2차전지만 쓴다.
        List<SectorMapEntries.Entry> current =
                List.of(
                        new SectorMapEntries.Entry("SEMICONDUCTOR", "반도체", 1L),
                        new SectorMapEntries.Entry("BATTERY", "2차전지", 2L),
                        new SectorMapEntries.Entry("BIO", "바이오", 99L));

        Optional<MappingDrift> drift =
                checker(current).check(SAVED, List.of("SEMICONDUCTOR", "BATTERY"));

        assertThat(drift).hasValue(MappingDrift.none());
    }

    @Test
    @DisplayName("이 기록이 쓴 섹터의 대표 ETF가 교체됐으면 INSTRUMENT_REPLACED 와 한 문장")
    void 대표_ETF가_바뀌면_교체다() {
        List<SectorMapEntries.Entry> current =
                List.of(
                        new SectorMapEntries.Entry("SEMICONDUCTOR", "반도체", 1L),
                        new SectorMapEntries.Entry("BATTERY", "2차전지", 22L),
                        new SectorMapEntries.Entry("BIO", "바이오", 3L));

        MappingDrift drift =
                checker(current).check(SAVED, List.of("SEMICONDUCTOR", "BATTERY")).orElseThrow();

        assertThat(drift.changed()).isTrue();
        assertThat(drift.kind()).isEqualTo(MappingDrift.Kind.INSTRUMENT_REPLACED);
        assertThat(drift.sectorCodes()).containsExactly("BATTERY");
        assertThat(drift.message()).isEqualTo("2차전지 섹터의 대표 ETF가 저장 당시와 다릅니다.");
    }

    @Test
    @DisplayName("이 기록이 쓴 섹터가 목록에서 빠졌으면 SECTOR_REMOVED — 대체 섹터는 사용자가 고른다")
    void 섹터가_빠지면_제거다() {
        List<SectorMapEntries.Entry> current =
                List.of(
                        new SectorMapEntries.Entry("SEMICONDUCTOR", "반도체", 1L),
                        new SectorMapEntries.Entry("BIO", "바이오", 3L));

        MappingDrift drift =
                checker(current).check(SAVED, List.of("SEMICONDUCTOR", "BATTERY")).orElseThrow();

        assertThat(drift.kind()).isEqualTo(MappingDrift.Kind.SECTOR_REMOVED);
        assertThat(drift.sectorCodes()).containsExactly("BATTERY");
        assertThat(drift.message()).startsWith("2차전지 섹터가 현재 대응표에 없습니다.");
    }

    @Test
    @DisplayName("저장 당시 버전을 모르거나 그 버전 행이 없으면 판정하지 않는다 — '안 바뀜'으로 답하지 않는다")
    void 근거가_없으면_판정하지_않는다() {
        MyRecordMappingDriftChecker checker = checker(SAVED_ENTRIES);

        assertThat(checker.check(null, List.of("BATTERY"))).isEmpty();
        assertThat(checker.check("SEM-없음", List.of("BATTERY"))).isEmpty();
        assertThat(checker.check(SAVED, List.of())).isEmpty();
    }

    private static MyRecordMappingDriftChecker checker(List<SectorMapEntries.Entry> current) {
        Map<String, List<SectorMapEntries.Entry>> versions = Map.of(SAVED, SAVED_ENTRIES);
        return new MyRecordMappingDriftChecker(
                new SectorMapEntries() {
                    @Override
                    public Optional<List<Entry>> entriesOf(String mapVersion) {
                        return Optional.ofNullable(versions.get(mapVersion));
                    }

                    @Override
                    public Optional<List<Entry>> currentEntries() {
                        return Optional.of(current);
                    }
                });
    }
}
