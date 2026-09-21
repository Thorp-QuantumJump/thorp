package com.ssafy.thorp.regime.infrastructure.macro;

import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.CurrentPointer;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.DisplayIndicatorRow;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.IndicatorDefinitionRow;
import com.ssafy.thorp.regime.application.exception.MacroRegistryUnavailableException;
import com.ssafy.thorp.regime.application.port.FieldRegistration;
import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.application.port.MacroIndicatorPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 010이 008 등록표에서 이름과 단위를 읽는 구현 (T070, 010 contracts/macro-indicator-port.md §2).
 *
 * <p><b>관측값을 읽지 않는다.</b> 008의 스냅샷 조회는 17개 지표의 경로까지 함께 읽는데, 이 포트가 돌려주는 것은 이름·단위·표시 순서뿐이라 그 값들이 쓰이지
 * 않는다. 계약 §0.1이 시계열 메서드를 두지 않기로 한 것과 같은 이유다 — 쓰지 않는 것을 읽으면 그것이 언젠가 쓰이기 시작한다.
 *
 * <p><b>식별자를 바꾸지 않는다.</b> 008이 {@code all_industry_production} 으로 부르면 여기서도 그대로 내보낸다. 임시 대역이 쓰던 대문자
 * 표기로 옮기면 그 변환표가 새로운 진실이 되고, 008에 지표가 늘 때마다 여기를 고쳐야 한다 — 계약 §2가 "010은 이 포트가 돌려주는 값 외에 아무것도 알지 못한다"고
 * 한 것이 그 뜻이다.
 *
 * <p><b>빈 값을 지어내지 않는다.</b> 등록표가 아직 발행되지 않았으면 빈 목록이 아니라 오류다. 빈 목록은 "분야가 없는 등록표"로 읽혀 미발행이 정상 응답처럼
 * 보인다.
 */
@Component
public class MacroRegistryIndicatorAdapter implements MacroIndicatorPort {

    private final MacroSnapshotRepository repository;

    public MacroRegistryIndicatorAdapter(MacroSnapshotRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public String registryVersion() {
        return pointer().registryVersionKey();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FieldRegistration> fields() {
        CurrentPointer pointer = pointer();
        return repository.findDisplayFields(pointer.displayVersionId()).stream()
                .map(
                        field ->
                                new FieldRegistration(
                                        field.fieldId(), field.displayName(), field.displayOrder()))
                .sorted(FieldRegistration.DISPLAY_ORDER)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IndicatorRegistration> indicator(String indicatorId) {
        if (indicatorId == null || indicatorId.isBlank()) {
            return Optional.empty();
        }
        CurrentPointer pointer = pointer();
        Optional<IndicatorDefinitionRow> definition =
                repository.findDefinitions(pointer.registryVersionId()).stream()
                        .filter(row -> row.indicatorId().equals(indicatorId))
                        .findFirst();
        if (definition.isEmpty()) {
            // 등록표에 없는 id다. 이름을 지어내면 빠진 지표와 오타 난 id가 화면에서 같아 보인다.
            return Optional.empty();
        }
        Map<String, DisplayIndicatorRow> displayNames =
                repository.findDisplayIndicators(pointer.displayVersionId()).stream()
                        .collect(
                                Collectors.toMap(
                                        DisplayIndicatorRow::indicatorId,
                                        Function.identity(),
                                        (first, ignored) -> first));
        DisplayIndicatorRow display = displayNames.get(indicatorId);
        return Optional.of(
                new IndicatorRegistration(
                        indicatorId,
                        // 화면 분류에 이름이 없으면 등록표의 id 를 그대로 쓴다. 그래야 "이름이
                        // 아직 없다"가 화면에 드러나고, 지어낸 문구로 덮이지 않는다.
                        display == null ? indicatorId : display.displayName(),
                        // 단위는 변환 뒤 단위다. 010이 보여주는 값이 변환값이라 원단위를 쓰면
                        // 증가율에 "2020=100" 이 붙는다.
                        definition.get().transformedUnit()));
    }

    private CurrentPointer pointer() {
        return repository
                .findCurrentPointer()
                .orElseThrow(
                        () ->
                                new MacroRegistryUnavailableException(
                                        "지표 등록표(spec 008)가 아직 발행되지 않았습니다. "
                                                + "data/scripts/register_macro_registry.py 로 등록표를"
                                                + " 세운 뒤 다시 시도합니다"));
    }
}
