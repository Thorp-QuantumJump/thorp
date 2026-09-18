package com.ssafy.thorp.regime.infrastructure.macro.fixture;

import com.ssafy.thorp.regime.application.port.FieldRegistration;
import com.ssafy.thorp.regime.application.port.IndicatorRegistration;
import com.ssafy.thorp.regime.application.port.MacroIndicatorPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ⚠ <b>[임시] 008 대역</b> — 008(거시지표 등록표)이 완성되면 <b>이 디렉터리를 통째로 지운다</b>(계약 §4.3).
 *
 * <p>지울 것이 한 곳에 모이도록 운영 코드는 이 클래스를 이름으로 참조하지 않는다. 연결은 {@code @ConditionalOnProperty} 하나뿐이고, 그 설정을
 * {@code macro}로 바꾸면 이 빈은 아예 만들어지지 않는다.
 *
 * <p><b>담는 것은 이름·단위·표시 순서뿐이다.</b> 시계열도 기준월도 없다 — 그래서 {@code data/} 쪽 픽스처({@code
 * data/src/data_pipeline/regime/macro/fixture/})와 <b>겹치는 값이 없고 어긋날 자리도 없다</b>. 겹칠 뻔했던 T_g·T_all은
 * 배치가 {@code regime_baseline_month}에 기록한다(계약 §0.2).
 *
 * <p>{@link #registryVersion()}이 {@code FIXTURE-}로 시작하므로 이 값이 응답의 {@code versions.registry}와 식별 키에
 * 실려 화면·DB 어디에서도 가짜임이 드러난다(계약 §4.2).
 */
@Component
@ConditionalOnProperty(prefix = "thorp.regime.macro", name = "source", havingValue = "fixture")
public class FixtureMacroIndicatorAdapter implements MacroIndicatorPort {

    /** {@code FIXTURE-} 접두사가 폐기 조건이다 — {@code registry_version LIKE 'FIXTURE-%'}로 한 줄에 걸린다. */
    private static final String REGISTRY_VERSION = "FIXTURE-REG-1";

    private static final List<FieldRegistration> FIELDS =
            List.of(
                    new FieldRegistration("PRODUCTION", "생산", 1),
                    new FieldRegistration("CONSUMPTION_INVESTMENT", "소비·투자", 2),
                    new FieldRegistration("EXTERNAL_TRADE", "대외 거래", 3),
                    new FieldRegistration("LABOR", "노동", 4),
                    new FieldRegistration("PRICES_COMMODITIES", "물가·원자재", 5),
                    new FieldRegistration("FINANCE_HOUSING", "금융·주택", 6),
                    new FieldRegistration("GLOBAL_ECONOMY", "세계 경제", 7));

    private static final Map<String, IndicatorRegistration> INDICATORS = indicators();

    private static Map<String, IndicatorRegistration> indicators() {
        Map<String, IndicatorRegistration> registrations = new LinkedHashMap<>();
        register(registrations, "ALL_INDUSTRY_PRODUCTION", "전산업생산지수 증가율", "%");
        register(registrations, "MINING_MANUFACTURING_PRODUCTION", "광공업생산지수 증가율", "%");
        register(registrations, "SERVICE_PRODUCTION", "서비스업생산지수 증가율", "%");
        register(registrations, "RETAIL_SALES_INDEX", "소매판매액지수 증가율", "%");
        register(registrations, "FACILITY_INVESTMENT_INDEX", "설비투자지수 증가율", "%");
        register(registrations, "CONSTRUCTION_COMPLETED", "건설기성액 증가율", "%");
        register(registrations, "EXPORTS", "수출액 증가율", "%");
        register(registrations, "IMPORTS", "수입액 증가율", "%");
        register(registrations, "TRADE_BALANCE", "무역수지", "억달러");
        register(registrations, "EMPLOYED_PERSONS", "취업자 수 증가율", "%");
        register(registrations, "UNEMPLOYMENT_RATE", "실업률", "%");
        register(registrations, "CONSUMER_PRICES", "소비자물가 상승률", "%");
        register(registrations, "DUBAI_CRUDE", "두바이유", "달러/배럴");
        register(registrations, "TREASURY_BOND_3Y", "국고채 3년물 금리", "%");
        register(registrations, "USD_KRW", "원/달러 환율", "원");
        register(registrations, "HOUSING_PRICES", "주택매매가격 상승률", "%");
        register(registrations, "G20_LEADING_INDEX", "G20 경기선행지수", "p");
        return Map.copyOf(registrations);
    }

    private static void register(
            Map<String, IndicatorRegistration> target, String id, String name, String unit) {
        target.put(id, new IndicatorRegistration(id, name, unit));
    }

    @Override
    public String registryVersion() {
        return REGISTRY_VERSION;
    }

    @Override
    public List<FieldRegistration> fields() {
        return FIELDS.stream().sorted(FieldRegistration.DISPLAY_ORDER).toList();
    }

    @Override
    public Optional<IndicatorRegistration> indicator(String indicatorId) {
        // 모르는 지표에 이름을 지어내지 않는다. 빈 값이 "등록표에 없다"는 사실 그대로다.
        return Optional.ofNullable(INDICATORS.get(indicatorId));
    }
}
