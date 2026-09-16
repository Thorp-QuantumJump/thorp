package com.ssafy.thorp.strategy.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.strategy.application.exception.StrategySettingsRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 003 접수 거절이 계약에 적힌 HTTP 상태와 코드로 나가는지 본다(contracts/strategy-registry-api.md §2).
 *
 * <p>상태가 틀리면 화면이 잘못 반응한다 — 409는 "등록표를 다시 읽어라", 422는 "고른 조합을 바꿔라", 400은 "보낸 값이 틀렸다"다.
 *
 * <p><b>규칙 위반(422)은 접수 통합 테스트에서 낼 수 없어 여기서 고정한다.</b> 지금 등록표에서 유효성 규칙을 가진 전략은 {@code
 * SECTOR_MOMENTUM} 하나인데 004 규칙 등록표에서 {@code PENDING_DECISION}이라, 접수는 등록표 판정에 닿기 전에 422 {@code
 * RULES_NOT_REGISTERED}로 먼저 거절한다. 004가 그 전략의 규칙을 확정하면 {@code BacktestIntakeRegistryIT}에서 실제 경로로 한 번
 * 더 확인한다({@code ParamSearchErrorResponseTest}가 접수 엔드포인트 이전에 쓰던 방식과 같다).
 */
@WebMvcTest(controllers = StrategySettingsErrorResponseTest.ThrowingController.class)
@Import({SecurityConfig.class, StrategySettingsErrorResponseTest.ThrowingController.class})
class StrategySettingsErrorResponseTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("등록표 규칙 위반은 422 VALIDATION_RULE_VIOLATED이고 등록표 문장을 그대로 싣는다")
    void ruleViolationIsUnprocessable() throws Exception {
        mockMvc.perform(get("/test/strategy/rule-violated"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_RULE_VIOLATED"))
                // 화면이 이 문장을 그대로 보인다. 서버가 자기 말로 바꿔 쓰면 같은 위반이 두 문장으로 보인다.
                .andExpect(jsonPath("$.error.message").value("선정 섹터 수가 후보 섹터 집합의 크기보다 큽니다."))
                .andExpect(jsonPath("$.error.details.code").value("TOP_N_EXCEEDS_UNIVERSE"));
    }

    @Test
    @DisplayName("등록표 버전이 바뀌면 409이고 요청 버전과 현재 버전을 함께 알려 준다")
    void versionChangedIsConflict() throws Exception {
        mockMvc.perform(get("/test/strategy/version-changed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REGISTRY_VERSION_CHANGED"))
                .andExpect(jsonPath("$.error.details.requestedRegistryVersion").value("1.0.0"))
                .andExpect(jsonPath("$.error.details.currentRegistryVersion").value("1.1.0"));
    }

    @Test
    @DisplayName("허용 범위 밖 값은 400이고 허용 범위를 함께 알려 준다")
    void valueOutOfRangeIsBadRequest() throws Exception {
        mockMvc.perform(get("/test/strategy/out-of-range"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNREGISTERED_PARAMETER_VALUE"))
                .andExpect(jsonPath("$.error.details.axis").value("maxWeight"))
                .andExpect(jsonPath("$.error.details.allowedRange").exists());
    }

    /** 접수 경로를 거치지 않고 응답 매핑만 고정하는 더미 컨트롤러. */
    @RestController
    @RequestMapping("/test/strategy")
    static class ThrowingController {

        @GetMapping("/rule-violated")
        void ruleViolated() {
            throw StrategySettingsRejectedException.ruleViolated(
                    "SECTOR_MOMENTUM", "TOP_N_EXCEEDS_UNIVERSE", "선정 섹터 수가 후보 섹터 집합의 크기보다 큽니다.");
        }

        @GetMapping("/version-changed")
        void versionChanged() {
            throw StrategySettingsRejectedException.versionChanged("1.0.0", "1.1.0");
        }

        @GetMapping("/out-of-range")
        void outOfRange() {
            throw StrategySettingsRejectedException.valueOutOfRange(
                    "INVERSE_VOLATILITY", "maxWeight", 7, "10~100, 5 간격");
        }
    }
}
