package com.ssafy.thorp.regime.infrastructure.macro;

import com.ssafy.thorp.regime.application.exception.MacroRegistryUnavailableException;
import com.ssafy.thorp.regime.application.port.FieldRegistration;
import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.application.port.MacroIndicatorPort;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 008 구현체가 들어올 자리. <b>008이 완성되면 이 클래스를 그 구현으로 바꾼다.</b>
 *
 * <p>아직 008이 없으므로 <b>부르면 실패한다</b>. 자리를 비워 두지 않는 이유는 둘이다.
 *
 * <ul>
 *   <li><b>기동은 되어야 한다.</b> 빈이 아예 없으면 {@code MacroIndicatorPort}를 받는 서비스 때문에 애플리케이션 전체가 뜨지 않고, 010과
 *       무관한 도메인까지 함께 멈춘다.
 *   <li><b>부를 때는 시끄러워야 한다.</b> 빈 목록을 돌려주면 "등록표에 분야가 없다"가 되어 008 미구현이 <b>정상 응답</b>으로 보인다 — 화면은 분야
 *       목록이 비어 있는 이유를 알 길이 없다.
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "thorp.regime.macro", name = "source", havingValue = "macro")
public class PendingMacroIndicatorAdapter implements MacroIndicatorPort {

    @Override
    public String registryVersion() {
        throw unavailable();
    }

    @Override
    public List<FieldRegistration> fields() {
        throw unavailable();
    }

    @Override
    public Optional<IndicatorRegistration> indicator(String indicatorId) {
        throw unavailable();
    }

    private static MacroRegistryUnavailableException unavailable() {
        return new MacroRegistryUnavailableException(
                "지표 등록표(spec 008)가 아직 구현되지 않았습니다. "
                        + "008 완성 전에는 thorp.regime.macro.source=fixture 로 임시 대역을 씁니다"
                        + "(contracts/macro-indicator-port.md §4).");
    }
}
