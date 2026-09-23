package com.ssafy.thorp.regimelink.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 비교 묶음의 조건 대조 (T093).
 *
 * <p>주소로 직접 들어오는 두 토큰이 같은 조건인지 <b>서버가</b> 확인한다. 005 는 화면에서만 하므로 011 이 그것을 물려받으면 조건이 다른 두 결과가 나란히
 * 놓인다.
 */
@DisplayName("비교 묶음 조건 대조")
class BundleConsistencyCheckerTest {

    private final BundleConsistencyChecker checker = new BundleConsistencyChecker();

    @Test
    @DisplayName("기준만 있는 묶음은 견줄 상대가 없어 통과한다")
    void 단일_실행() {
        assertThatCode(() -> checker.verify(List.of(run(LinkRole.BASE, "SEMICONDUCTOR"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 조건이면 통과한다")
    void 같은_조건() {
        assertThatCode(
                        () ->
                                checker.verify(
                                        List.of(
                                                run(LinkRole.BASE, "SEMICONDUCTOR"),
                                                run(LinkRole.COMPARE, "SEMICONDUCTOR"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("섹터 후보 집합이 다르면 400 이고 어긋난 항목을 말한다")
    void 섹터_불일치() {
        assertThatThrownBy(
                        () ->
                                checker.verify(
                                        List.of(
                                                run(LinkRole.BASE, "SEMICONDUCTOR"),
                                                run(LinkRole.COMPARE, "BIO"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        thrown ->
                                assertThat(((BusinessException) thrown).getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_INPUT))
                .hasMessageContaining("sectors");
    }

    @Test
    @DisplayName("자료 버전이 다르면 400 이다")
    void 자료버전_불일치() {
        BundleRunView other =
                new BundleRunView(
                        "b41e7d0925c6438af10e5b2c96d47831",
                        LinkRole.COMPARE,
                        102L,
                        902L,
                        "INVERSE_VOLATILITY",
                        "흐름을 따라 배분하기",
                        "MAP-1",
                        new BigDecimal("10000000.00"),
                        "KR-ETF-2026-09",
                        "KRX-2026-08-31",
                        List.of(new BundleRunView.UniverseEntry("SEMICONDUCTOR", "091160.XKRX")),
                        LocalDate.of(2010, 1, 4),
                        LocalDate.of(2026, 6, 30),
                        LocalDate.of(2010, 4, 2),
                        LocalDate.of(2026, 6, 30),
                        63,
                        true);
        assertThatThrownBy(
                        () -> checker.verify(List.of(run(LinkRole.BASE, "SEMICONDUCTOR"), other)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dataVersion");
    }

    @Test
    @DisplayName("금액 표기만 다른 것은 어긋난 것이 아니다")
    void 표기_차이() {
        BundleRunView other =
                new BundleRunView(
                        "b41e7d0925c6438af10e5b2c96d47831",
                        LinkRole.COMPARE,
                        102L,
                        902L,
                        "INVERSE_VOLATILITY",
                        "흐름을 따라 배분하기",
                        "MAP-1",
                        new BigDecimal("10000000"),
                        "KR-ETF-2026-09",
                        "KRX-2026-09-09",
                        List.of(new BundleRunView.UniverseEntry("SEMICONDUCTOR", "091160.XKRX")),
                        LocalDate.of(2010, 1, 4),
                        LocalDate.of(2026, 6, 30),
                        LocalDate.of(2010, 4, 2),
                        LocalDate.of(2026, 6, 30),
                        63,
                        true);
        assertThatCode(() -> checker.verify(List.of(run(LinkRole.BASE, "SEMICONDUCTOR"), other)))
                .doesNotThrowAnyException();
    }

    private static BundleRunView run(LinkRole role, String sectorId) {
        return new BundleRunView(
                role == LinkRole.BASE
                        ? "9f2c1a7b4e0d83526af1c9d0b7e34512"
                        : "b41e7d0925c6438af10e5b2c96d47831",
                role,
                role == LinkRole.BASE ? 101L : 102L,
                role == LinkRole.BASE ? 901L : 902L,
                "FIXED_WEIGHT",
                "내가 정한 비중",
                "MAP-1",
                new BigDecimal("10000000.00"),
                "KR-ETF-2026-09",
                "KRX-2026-09-09",
                List.of(new BundleRunView.UniverseEntry(sectorId, "091160.XKRX")),
                LocalDate.of(2010, 1, 4),
                LocalDate.of(2026, 6, 30),
                LocalDate.of(2010, 4, 2),
                LocalDate.of(2026, 6, 30),
                63,
                true);
    }
}
