package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.repository.CostProfileRepository;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CostProfileServiceTest {

    /**
     * 서버 기본 비용 조건. PD-07 확정값이다(application.yml {@code thorp.backtest.default-cost-profile}, T021).
     */
    private static final String DEFAULT = "KR-ETF-2026-09";

    @Mock private CostProfileRepository repository;

    private CostProfileService service() {
        return new CostProfileService(repository, DEFAULT);
    }

    /** V20260919193708이 넣은 확정 비용 행 그대로다. */
    private static CostProfile confirmed() {
        return new CostProfile(
                DEFAULT,
                new BigDecimal("1.5"),
                new BigDecimal("3"),
                new BigDecimal("5"),
                BigDecimal.ZERO,
                null,
                false,
                "PD-07 확정 2026-09-17");
    }

    @Test
    @DisplayName("버전을 비우면 서버 기본값을 쓴다")
    void blankVersionFallsBackToServerDefault() {
        CostProfile confirmed = confirmed();
        when(repository.find(DEFAULT)).thenReturn(Optional.of(confirmed));

        assertThat(service().resolve(null)).isSameAs(confirmed);
        assertThat(service().resolve("  ")).isSameAs(confirmed);
    }

    @Test
    @DisplayName("모르는 버전을 지정하면 입력 오류다")
    void unknownVersionIsInvalidInput() {
        when(repository.find("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().resolve("NOPE"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("NOPE")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("서버 기본값이 등록표에 없으면 사용자 잘못이 아니므로 500이다")
    void missingDefaultIsInternalError() {
        when(repository.find(DEFAULT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().resolve(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
