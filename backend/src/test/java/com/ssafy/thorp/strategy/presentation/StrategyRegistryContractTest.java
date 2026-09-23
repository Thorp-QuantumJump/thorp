package com.ssafy.thorp.strategy.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.strategy.application.exception.RegistryUnavailableException;
import com.ssafy.thorp.strategy.application.port.AnalysisConditionRegistry;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.strategy.application.port.ValidationRule;
import com.ssafy.thorp.strategy.application.port.ValidationVerdict;
import com.ssafy.thorp.strategy.application.service.StrategyRegistryQueryService;
import com.ssafy.thorp.strategy.infrastructure.registry.SyntheticRegistry;
import com.ssafy.thorp.strategy.presentation.controller.StrategyRegistryController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 계약 문서의 응답 예시와 실제 응답이 같은지 본다(T026, plan.md "Testing" 2).
 *
 * <p><b>계약과 코드가 어긋나면 여기서 빌드가 깨진다.</b> 화면은 이 응답 하나로 전략 카드와 상세 설정을 구성하므로, 필드 이름이 하나 바뀌어도 화면이 아무것도 그리지
 * 못한다. 문서를 먼저 커밋했으니 이 테스트가 그것을 지키는 장치다.
 *
 * <p>합성 등록표로 돌린다. 운영 등록표의 값에 기대면 등록표가 바뀔 때마다 계약 테스트가 따라 바뀌고, 그러면 무엇을 지키는 테스트인지 알 수 없게 된다. 운영 등록표로
 * 실제 경로를 확인하는 것은 {@code StrategyRegistryApiIT}다.
 */
@WebMvcTest(controllers = StrategyRegistryController.class)
@Import({
    SecurityConfig.class,
    StrategyRegistryQueryService.class,
    StrategyRegistryContractTest.RegistryConfig.class
})
class StrategyRegistryContractTest {

    /** gradle test의 작업 디렉터리는 backend/다({@code BacktestResultApiIT}와 같은 방식). */
    private static final Path EXAMPLES =
            Path.of("..", "docs", "specs", "strategy-registry-api", "examples");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SwitchableRegistry registry;

    @AfterEach
    void restoreRegistry() {
        registry.available();
    }

    @Test
    @DisplayName("universeSize=3 응답의 data가 계약 예시 파일과 같다")
    void responseMatchesContractExample() throws Exception {
        String body =
                mockMvc.perform(get("/strategies/registry").param("universeSize", "3"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        JsonNode actual = objectMapper.readTree(body).get("data");
        JsonNode expected =
                objectMapper.readTree(
                        Files.readString(
                                EXAMPLES.resolve("registry-response.json"),
                                StandardCharsets.UTF_8));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("로그인하지 않아도 200이다 — 전략 진단은 비로그인 이용을 허용한다")
    void anonymousRequestIsAllowed() throws Exception {
        mockMvc.perform(get("/strategies/registry")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("universeSize가 0 이하면 400이다 — 없는 근거로 판정하지 않는다")
    void nonPositiveUniverseSizeIsRejected() throws Exception {
        mockMvc.perform(get("/strategies/registry").param("universeSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("등록표를 읽지 못하면 503 REGISTRY_UNAVAILABLE이다 — 임시 값으로 대신 응답하지 않는다")
    void registryFailureIsServiceUnavailable() throws Exception {
        registry.unavailable();

        mockMvc.perform(get("/strategies/registry").param("universeSize", "3"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("REGISTRY_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @TestConfiguration
    static class RegistryConfig {

        @Bean
        SwitchableRegistry parameterRegistryPort() {
            return new SwitchableRegistry();
        }
    }

    /**
     * 조회 실패를 켜고 끌 수 있는 등록표. 계약 픽스처와 같은 내용의 실제 로더에 위임한다.
     *
     * <p>모의 객체 대신 위임 구현을 두는 이유는, 정상 경로의 단언이 계약 픽스처와의 <b>완전 일치</b>라서 응답의 모든 필드가 실제 로더에서 와야 하기 때문이다.
     * 필드를 하나씩 흉내 내면 흉내 낸 만큼만 검증된다.
     */
    static class SwitchableRegistry implements ParameterRegistryPort {

        private final ParameterRegistryPort loaded = SyntheticRegistry.contract();
        private boolean failing;

        void unavailable() {
            failing = true;
        }

        void available() {
            failing = false;
        }

        private ParameterRegistryPort delegate() {
            if (failing) {
                throw new RegistryUnavailableException("합성 등록표를 읽지 못했다", null);
            }
            return loaded;
        }

        @Override
        public String version() {
            return delegate().version();
        }

        @Override
        public boolean provisional() {
            return delegate().provisional();
        }

        @Override
        public List<StrategyRegistration> activeStrategies() {
            return delegate().activeStrategies();
        }

        @Override
        public Optional<StrategyRegistration> find(String strategyId) {
            return delegate().find(strategyId);
        }

        @Override
        public List<ValidationRule> rules(String strategyId) {
            return delegate().rules(strategyId);
        }

        @Override
        public AnalysisConditionRegistry analysisConditions() {
            return delegate().analysisConditions();
        }

        @Override
        public ValidationVerdict validate(
                String strategyId, Map<String, Object> parameters, SearchContext context) {
            return delegate().validate(strategyId, parameters, context);
        }

        @Override
        public Optional<String> baselineStrategyId() {
            return delegate().baselineStrategyId();
        }
    }
}
