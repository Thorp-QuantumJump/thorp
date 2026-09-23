package com.ssafy.thorp.paramsearch.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.paramsearch.application.exception.RegistryVersionChangedException;
import com.ssafy.thorp.paramsearch.application.exception.StrategyNotExecutableException;
import com.ssafy.thorp.paramsearch.application.exception.StrategyNotRegisteredException;
import com.ssafy.thorp.strategy.application.exception.RegistryUnavailableException;
import java.util.List;
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
 * 007 거부 사유가 계약에 적힌 HTTP 상태와 코드로 나가는지 본다 (contracts/param-search-api.md §6).
 *
 * <p>상태가 틀리면 화면이 잘못 반응한다 — 409는 "미리보기를 다시 보고 확인받아라", 422는 "선택을 바꿔라", 503은 "잠시 후 다시"다. 같은 거부를 400으로
 * 내리면 화면이 재시도해야 할 때 포기한다.
 *
 * <p>접수 엔드포인트는 M3(T049)에서 생긴다. 그때까지 예외를 직접 던지는 컨트롤러로 응답 매핑만 고정한다 — 엔드포인트가 붙은 뒤에는 그 경로의 통합 테스트가 같은
 * 것을 한 번 더 확인한다.
 */
@WebMvcTest(controllers = ParamSearchErrorResponseTest.ThrowingController.class)
@Import({SecurityConfig.class, ParamSearchErrorResponseTest.ThrowingController.class})
class ParamSearchErrorResponseTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("등록표 버전이 바뀌면 409 REGISTRY_VERSION_CHANGED로 응답한다")
    void registryVersionChangedIsConflict() throws Exception {
        mockMvc.perform(get("/test/paramsearch/version-changed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("REGISTRY_VERSION_CHANGED"))
                // 어떤 버전을 보고 있었는지 알려야 화면이 다시 조회할 근거가 된다.
                .andExpect(
                        jsonPath("$.error.message")
                                .value(
                                        org.hamcrest.Matchers.allOf(
                                                org.hamcrest.Matchers.containsString(
                                                        "PROVISIONAL-20260910"),
                                                org.hamcrest.Matchers.containsString(
                                                        "PROVISIONAL-20260911"))));
    }

    @Test
    @DisplayName("미등록 전략은 422 STRATEGY_NOT_REGISTERED로 응답한다")
    void unregisteredStrategyIsUnprocessable() throws Exception {
        mockMvc.perform(get("/test/paramsearch/not-registered"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("STRATEGY_NOT_REGISTERED"))
                .andExpect(
                        jsonPath("$.error.message")
                                .value(org.hamcrest.Matchers.containsString("STRATEGY_Z")));
    }

    @Test
    @DisplayName("실행 미지원 전략은 422 STRATEGY_NOT_EXECUTABLE로 응답한다 — 미등록과 코드가 다르다")
    void unsupportedStrategyIsUnprocessable() throws Exception {
        mockMvc.perform(get("/test/paramsearch/not-executable"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("STRATEGY_NOT_EXECUTABLE"));
    }

    @Test
    @DisplayName("등록 정보 조회 실패는 503 REGISTRY_UNAVAILABLE로 응답한다")
    void registryFailureIsServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/paramsearch/registry-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("REGISTRY_UNAVAILABLE"));
    }

    @RestController
    @RequestMapping("/test/paramsearch")
    static class ThrowingController {

        @GetMapping("/version-changed")
        void versionChanged() {
            throw new RegistryVersionChangedException(
                    "PROVISIONAL-20260910", "PROVISIONAL-20260911");
        }

        @GetMapping("/not-registered")
        void notRegistered() {
            throw new StrategyNotRegisteredException(List.of("STRATEGY_Z"));
        }

        @GetMapping("/not-executable")
        void notExecutable() {
            throw new StrategyNotExecutableException(List.of("STRATEGY_B"));
        }

        @GetMapping("/registry-down")
        void registryDown() {
            throw new RegistryUnavailableException("003 등록표", new IllegalStateException("down"));
        }
    }
}
