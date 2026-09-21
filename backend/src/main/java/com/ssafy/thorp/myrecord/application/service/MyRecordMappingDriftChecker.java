package com.ssafy.thorp.myrecord.application.service;

import com.ssafy.thorp.myrecord.application.port.SectorMapEntries;
import com.ssafy.thorp.myrecord.application.result.MappingDrift;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * UI-01 판정(spec 006 research R8). 저장 당시 대응표 버전의 항목과 지금 열린 버전의 항목을 <b>섹터마다</b> 비교한다.
 *
 * <p>판정은 여기 한 곳이고 화면은 버전 문자열을 비교하지 않는다(원칙 II). 버전 문자열이 달라도 이 기록이 쓴 섹터의 대표 ETF({@code etf_id})가 모두
 * 같으면 바뀐 것이 아니다.
 */
@Service
public class MyRecordMappingDriftChecker {

    private final SectorMapEntries entries;

    public MyRecordMappingDriftChecker(SectorMapEntries entries) {
        this.entries = entries;
    }

    /**
     * @param savedMapVersion 기록이 참조한 대응표 버전. 없으면 판정할 수 없다
     * @param sectorCodes 기록이 쓴 섹터. 저장 당시 버전에 없던 섹터(탐색 묶음이 종목으로 접수해 현재 대응표로 되짚은 코드)는 비교하지 않는다
     * @return 판정. 근거가 없으면(저장 버전 없음·그 버전 행 없음·열린 버전 없음·섹터 없음) 빈 값 — "안 바뀜"으로 답하지 않는다(원칙 VI)
     */
    public Optional<MappingDrift> check(String savedMapVersion, List<String> sectorCodes) {
        if (savedMapVersion == null || sectorCodes.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<SectorMapEntries.Entry>> saved = entries.entriesOf(savedMapVersion);
        Optional<List<SectorMapEntries.Entry>> current = entries.currentEntries();
        if (saved.isEmpty() || current.isEmpty()) {
            return Optional.empty();
        }
        Map<String, SectorMapEntries.Entry> before = byCode(saved.get());
        Map<String, SectorMapEntries.Entry> now = byCode(current.get());

        List<SectorMapEntries.Entry> removed = new ArrayList<>();
        List<SectorMapEntries.Entry> replaced = new ArrayList<>();
        for (String code : sectorCodes) {
            SectorMapEntries.Entry was = before.get(code);
            if (was == null) {
                continue;
            }
            SectorMapEntries.Entry is = now.get(code);
            if (is == null) {
                removed.add(was);
            } else if (is.etfId() != was.etfId()) {
                replaced.add(was);
            }
        }
        // 섹터가 빠진 것이 우선이다 — 사용자가 대체 섹터를 골라야 다음 분석이 성립한다(FR-008). 둘이 함께
        // 일어나면 빠진 섹터만 알린다. 대체 섹터를 고르는 화면(002)이 현재 대응표를 보이므로 교체된 ETF 는 거기서 드러난다.
        if (!removed.isEmpty()) {
            return Optional.of(
                    new MappingDrift(
                            true,
                            MappingDrift.Kind.SECTOR_REMOVED,
                            codes(removed),
                            names(removed) + " 섹터가 현재 대응표에 없습니다. 설정 바꿔 비교에서 대체 섹터를 골라 주세요."));
        }
        if (!replaced.isEmpty()) {
            return Optional.of(
                    new MappingDrift(
                            true,
                            MappingDrift.Kind.INSTRUMENT_REPLACED,
                            codes(replaced),
                            names(replaced) + " 섹터의 대표 ETF가 저장 당시와 다릅니다."));
        }
        return Optional.of(MappingDrift.none());
    }

    private static Map<String, SectorMapEntries.Entry> byCode(List<SectorMapEntries.Entry> list) {
        return list.stream()
                .collect(
                        Collectors.toMap(
                                SectorMapEntries.Entry::sectorCode,
                                Function.identity(),
                                (first, second) -> first));
    }

    private static List<String> codes(List<SectorMapEntries.Entry> list) {
        return list.stream().map(SectorMapEntries.Entry::sectorCode).toList();
    }

    private static String names(List<SectorMapEntries.Entry> list) {
        return list.stream()
                .map(SectorMapEntries.Entry::sectorName)
                .collect(Collectors.joining("·"));
    }
}
