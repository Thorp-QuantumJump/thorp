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

    private static final String DEFAULT = "PROVISIONAL-DEMO-1";

    @Mock private CostProfileRepository repository;

    private CostProfileService service() {
        return new CostProfileService(repository, DEFAULT);
    }

    private static CostProfile demo() {
        return new CostProfile(
                DEFAULT,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("5"),
                BigDecimal.ZERO,
                null,
                true,
                "ADR-0004 데모 값 재사용");
    }

    @Test
    @DisplayName("버전을 비우면 서버 기본값을 쓰고, 그 값은 임시로 표시돼 있다")
    void blankVersionFallsBackToProvisionalDefault() {
        when(repository.find(DEFAULT)).thenReturn(Optional.of(demo()));

        assertThat(service().resolve(null).provisional()).isTrue();
        assertThat(service().resolve("  ").version()).isEqualTo(DEFAULT);
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
