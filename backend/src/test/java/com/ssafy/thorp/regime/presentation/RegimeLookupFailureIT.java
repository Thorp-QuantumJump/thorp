package com.ssafy.thorp.regime.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.regime.application.exception.RegimeLookupFailedException;
import com.ssafy.thorp.regime.application.repository.RegimeBaselineMonthRepository;
import com.ssafy.thorp.regime.application.repository.RegimeSnapshotRepository;
import com.ssafy.thorp.regime.domain.model.RegimeBaselineMonth;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>조회 실패는 '계산 불가'와 다르게 나간다</b> (spec FR-034, SC-010, tasks.md T104).
 *
 * <p>둘을 구분하는 이유는 사용자에게 다른 말이기 때문이다 — 계산 불가는 <b>다시 와도 같은 것</b>(척도가 0이거나 자료가 없다)이고 조회 실패는 <b>다시 오면
 * 달라질 수 있는 것</b>이다. 같은 화면으로 말하면 기다려야 할 때 포기하고, 포기해야 할 때 계속 기다린다.
 *
 * <p>저장소 실패는 실제로 일으키기 어려워 저장소를 대역으로 바꾼다. 대역이 컨텍스트 전체를 바꾸므로 {@link RegimeSearchApiIT}와 파일을 나눴다 — 한
 * 클래스에 두면 나머지 시험까지 대역 위에서 돌아 무엇을 확인한 것인지 흐려진다.
 *
 * <p>저장소를 대역으로 바꿔도 {@link PostgresTestSupport}를 상속한다. JPA는 기동에 실제 연결을 요구하므로, 상속하지 않으면 개발자 기기에 마침 떠
 * 있던 DB에 붙어 통과하고 CI에서만 깨진다.
 */
@SpringBootTest(
        properties = {
            "spring.mail.username=regime-test",
            "spring.mail.password=regime-test",
            "thorp.regime.macro.source=fixture"
        })
@AutoConfigureMockMvc
@DisplayName("조회 실패 구분")
class RegimeLookupFailureIT extends PostgresTestSupport {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RegimeSnapshotRepository snapshots;
    @MockitoBean private RegimeBaselineMonthRepository baselineMonths;

    @BeforeEach
    void baselineIsAvailable() {
        given(baselineMonths.findByScope(anyString()))
                .willReturn(
                        Optional.of(
                                new RegimeBaselineMonth(
                                        "LABOR",
                                        YearMonth.of(2026, 7),
                                        YearMonth.of(1999, 4),
                                        "FIXTURE-REG-1",
                                        "FIXTURE-20260915")));
    }

    @Test
    @DisplayName("저장된 결과를 읽지 못하면 503으로 알린다")
    void lookupFailureIsServiceUnavailable() throws Exception {
        given(snapshots.findLatest(any(), anyString(), any()))
                .willThrow(new RegimeLookupFailedException("읽기 실패", new IllegalStateException()));

        mockMvc.perform(get("/regime-searches").param("mode", "field").param("field", "LABOR"))
                // 503 은 "일시적이니 다시 오라"는 뜻이고, 읽기 실패는 정말로 그렇다.
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.isSuccess").value(false));
    }

    @Test
    @DisplayName("조회 실패를 NOT_COMPUTABLE이나 빈 결과로 바꾸지 않는다")
    void lookupFailureIsNotDisguisedAsNotComputable() throws Exception {
        given(snapshots.findLatest(any(), anyString(), any()))
                .willThrow(new RegimeLookupFailedException("읽기 실패", new IllegalStateException()));

        String payload =
                mockMvc.perform(
                                get("/regime-searches")
                                        .param("mode", "field")
                                        .param("field", "LABOR"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 읽지 못한 것을 "결과가 없다"로 내보내면 실패가 정상 화면 안에 숨는다.
        org.assertj.core.api.Assertions.assertThat(payload).doesNotContain("NOT_COMPUTABLE");
        org.assertj.core.api.Assertions.assertThat(payload).doesNotContain("\"cases\"");
    }

    @Test
    @DisplayName("결과가 없는 것과 읽지 못한 것은 다른 상태 코드다")
    void missingAndUnreadableDiffer() throws Exception {
        given(snapshots.findLatest(any(), anyString(), any())).willReturn(Optional.empty());
        mockMvc.perform(get("/regime-searches").param("mode", "field").param("field", "LABOR"))
                .andExpect(status().isNotFound());
    }
}
