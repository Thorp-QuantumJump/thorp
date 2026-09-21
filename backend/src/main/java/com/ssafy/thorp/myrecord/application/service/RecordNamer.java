package com.ssafy.thorp.myrecord.application.service;

import com.ssafy.thorp.myrecord.domain.vo.RecordName;
import com.ssafy.thorp.sector.application.repository.SectorRepository;
import com.ssafy.thorp.sector.application.result.SectorListResult;
import com.ssafy.thorp.sector.domain.model.Sector;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 기록 항목의 이름을 짓는다(spec 006 FR-003).
 *
 * <p><b>사용자에게 이름 입력을 요구하지 않는다.</b> 저장 시 섹터·전략·저장일로 만들고 사용자가 나중에 바꾼다.
 *
 * <p><b>유일성을 강제하지 않는다</b>(research R12). 같은 이름이 여럿이어도 목록은 저장일과 상태로 구분되고, 식별은 {@code recordId}가 한다 —
 * 서버가 {@code (2)} 같은 꼬리를 붙이면 그것은 사용자가 붙인 뜻이 없는 식별자다(원칙 II).
 *
 * <p><b>표시 이름을 읽지 못해도 이름은 나온다.</b> 대응표·등록표 조회가 실패하면 코드를 그대로 쓴다 — 이름은 찾기 위한 표시라 그것 때문에 접수를 막을 수 없다.
 * 없는 이름을 지어내지는 않는다(원칙 VI).
 */
@Service
public class RecordNamer {

    private static final Logger log = LoggerFactory.getLogger(RecordNamer.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SectorRepository sectorRepository;
    private final ParameterRegistryPort registry;

    public RecordNamer(SectorRepository sectorRepository, ParameterRegistryPort registry) {
        this.sectorRepository = sectorRepository;
        this.registry = registry;
    }

    /**
     * 단일 분석: {@code 반도체 외 2 섹터 모멘텀 2026-09-18}.
     *
     * @param sectorCodes 투자 대상 섹터. <b>첫 값이 대표다</b> — 비중이 가장 큰 섹터를 부른 쪽이 앞에 둔다
     */
    public RecordName forAnalysis(List<String> sectorCodes, String strategyId, LocalDate savedOn) {
        return RecordName.of(
                join(sectorLabel(sectorCodes), strategyName(strategyId), DATE.format(savedOn)));
    }

    /**
     * 탐색 묶음: 전략이 하나면 분석과 같은 모양이고, 여럿이면 {@code 반도체 외 2 전략 3개 탐색 2026-09-18}.
     *
     * <p>전략 이름을 나열하지 않는 이유: 이름이 길어 목록에서 섹터·기간이 밀린다. 전략 목록은 펼쳐 보는 층에 있다.
     */
    public RecordName forSearchBundle(
            List<String> sectorCodes, List<String> strategyIds, LocalDate savedOn) {
        String strategyPart =
                strategyIds.size() == 1
                        ? strategyName(strategyIds.get(0)) + " 탐색"
                        : "전략 " + strategyIds.size() + "개 탐색";
        return RecordName.of(join(sectorLabel(sectorCodes), strategyPart, DATE.format(savedOn)));
    }

    /**
     * 경제환경 탐색: {@code 고용환경 2026-08}. 분야 이름은 부른 쪽이 준다 — 010이 소유한 값이라 006이 다시 읽지 않는다.
     *
     * @param fieldLabel 분야 이름 또는 종합 검색의 표시 문구
     * @param baselineMonth 실제 질의 기준월
     */
    public RecordName forRegimeSearch(String fieldLabel, LocalDate baselineMonth) {
        return RecordName.of(
                join(fieldLabel, DateTimeFormatter.ofPattern("yyyy-MM").format(baselineMonth)));
    }

    /** {@code 반도체}, {@code 반도체 외 2}. 대응표를 읽지 못하면 코드를 그대로 쓴다. */
    private String sectorLabel(List<String> sectorCodes) {
        if (sectorCodes.isEmpty()) {
            return "";
        }
        Map<String, String> names = sectorNames();
        String head = names.getOrDefault(sectorCodes.get(0), sectorCodes.get(0));
        return sectorCodes.size() == 1 ? head : head + " 외 " + (sectorCodes.size() - 1);
    }

    private Map<String, String> sectorNames() {
        try {
            return sectorRepository
                    .findOpenSectorMap()
                    .map(SectorListResult::sectors)
                    .orElse(List.of())
                    .stream()
                    .collect(
                            Collectors.toMap(
                                    sector -> sector.code().value(),
                                    Sector::name,
                                    (first, second) -> first));
        } catch (RuntimeException e) {
            log.warn("섹터 이름을 읽지 못해 코드로 이름을 짓는다", e);
            return Map.of();
        }
    }

    /** 등록표의 전략 이름. 비활성 전략도 이름이 나온다. 읽지 못하면 전략 식별자를 그대로 쓴다. */
    private String strategyName(String strategyId) {
        try {
            return registry.find(strategyId)
                    .map(StrategyRegistration::name)
                    .filter(name -> !name.isBlank())
                    .orElse(strategyId);
        } catch (RuntimeException e) {
            log.warn("전략 이름을 읽지 못해 식별자로 이름을 짓는다: strategyId={}", strategyId, e);
            return strategyId;
        }
    }

    /** 빈 조각이 섞여도 공백이 겹치지 않게 잇는다. */
    private static String join(String... parts) {
        return Stream.of(parts).filter(part -> !part.isBlank()).collect(Collectors.joining(" "));
    }
}
