package com.ssafy.thorp.regime.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 경제환경 검색 조회 API 통합 시험 (계약 {@code contracts/regime-search-api.md}, tasks.md T101~T103·T105·T107).
 *
 * <p>행을 넣는 것은 {@code data/} 배치이므로 여기서는 배치가 쓰는 것과 <b>같은 모양의 JSONB</b>를 직접 심는다. 모양이 어긋나면 이 시험이 먼저
 * 깨지고, 그것이 배치와 조회가 갈렸다는 신호다.
 */
@SpringBootTest(
        properties = {
            // 다른 IT 다수가 쓰는 값과 같게 둔다. 컨텍스트 캐시 키는 프로퍼티 조합이라 값이
            // 다르면 Spring 이 컨텍스트를 따로 하나 더 만들어 캐시에 얹는다.
            //
            // thorp.regime.macro.source 는 적지 않는다 — application.yml 의 기본값이 이미
            // fixture 라 적을 필요가 없고, 적으면 이 클래스만 다른 버킷으로 빠진다.
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
@DisplayName("경제환경 검색 조회 API")
class RegimeSearchApiIT extends PostgresTestSupport {

    private static final String LABOR_KEY =
            "FIELD:LABOR:2026-07-01:FIXTURE-20260915:FIXTURE-REG-1:REGIME-V1";
    private static final String COMPOSITE_KEY =
            "COMPOSITE:ALL:2026-05-01:FIXTURE-20260915:FIXTURE-REG-1:REGIME-V1";

    /** spec FR-022 ①이 문자열로 못 박은 열세 가지. 산출물과 고정 문구 어디에도 나오지 않아야 한다. */
    private static final List<String> FORBIDDEN =
            List.of(
                    "대표 유사국면",
                    "충분히 유사",
                    "충분한 사례가 없다",
                    "충분한 사례 없음",
                    "지금도 잘될 전략",
                    "모든 지표의 중요성이 같다",
                    "실제 투자 여건이 같다",
                    "유지율",
                    "retention",
                    "당시 재현",
                    "당시 발표값 기준",
                    "수집 시각",
                    "실시간");

    /** 4번 — 숫자 + {@code % 유사}. 문자열 하나로 적을 수 없어 따로 본다. */
    private static final Pattern PERCENT_SIMILAR = Pattern.compile("\\d+\\s*%\\s*유사");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        clean();
        baseline("LABOR", "2026-07-01", "1999-04-01");
        baseline("ALL", "2026-05-01", "2005-04-01");
        baseline("PRODUCTION", "2026-07-01", "1999-04-01");
        baseline("EXTERNAL_TRADE", "2026-07-01", "1999-04-01");
        baseline("PRICES_COMMODITIES", "2026-07-01", "1999-04-01");
        baseline("GLOBAL_ECONOMY", "2026-07-01", "2010-04-01");

        long labor =
                snapshot(
                        LABOR_KEY,
                        "FIELD",
                        "LABOR",
                        "2026-07-01",
                        "NEAREST_ONLY",
                        "THRESHOLD_NOT_CALIBRATED",
                        "a".repeat(64));
        laborCase(labor, 1, "2012-03-01", "0.412938571034");
        laborCase(labor, 2, "2005-09-01", "0.512938571034");

        long composite =
                snapshot(
                        COMPOSITE_KEY,
                        "COMPOSITE",
                        null,
                        "2026-05-01",
                        "NEAREST_ONLY",
                        "THRESHOLD_NOT_CALIBRATED",
                        "b".repeat(64));
        compositeCase(composite);

        // 계산 불가 네 사유 — 화면이 사용자에게 할 말이 사유마다 다르다.
        notComputable("PRODUCTION", "SCALE_ZERO", "c");
        notComputable("EXTERNAL_TRADE", "SCALE_WINDOW_TOO_SHORT", "d");
        notComputable("PRICES_COMMODITIES", "QUERY_INPUT_MISSING", "e");
        notComputable("GLOBAL_ECONOMY", "NO_CANDIDATE", "f");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM regime_search_log");
        jdbc.update("DELETE FROM regime_search_case");
        jdbc.update("DELETE FROM regime_search_snapshot");
        jdbc.update("DELETE FROM regime_baseline_month");
    }

    // -- §1 검색 하나 ------------------------------------------------------------------

    @Test
    @DisplayName("분야 검색이 계약 모양으로 온다")
    void fieldSearchMatchesContract() throws Exception {
        mockMvc.perform(get("/regime-searches").param("mode", "field").param("field", "LABOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.searchId").value(LABOR_KEY))
                .andExpect(jsonPath("$.result.mode").value("FIELD"))
                .andExpect(jsonPath("$.result.field.id").value("LABOR"))
                .andExpect(jsonPath("$.result.field.name").value("노동"))
                .andExpect(jsonPath("$.result.baselineMonth").value("2026-07"))
                // baselineMonth 를 주지 않았으니 근거는 T_G 다 — 저장 값이 아니라 요청을 보고 정한다.
                .andExpect(jsonPath("$.result.baselineOrigin").value("T_G"))
                .andExpect(jsonPath("$.result.status").value("NEAREST_ONLY"))
                .andExpect(jsonPath("$.result.statusReason").value("THRESHOLD_NOT_CALIBRATED"))
                .andExpect(jsonPath("$.result.caseLabel").value("최근접 참고 사례"))
                .andExpect(jsonPath("$.result.basis.kind").value("HISTORICAL_REVISION"))
                .andExpect(jsonPath("$.result.versions.data").value("FIXTURE-20260915"))
                .andExpect(jsonPath("$.result.versions.sampleData").value(true))
                .andExpect(jsonPath("$.result.versions.revision").value(1))
                .andExpect(jsonPath("$.result.counts.eligible").value(3))
                .andExpect(jsonPath("$.result.counts.stored").value(2))
                .andExpect(jsonPath("$.result.counts.displayed").value(2))
                .andExpect(jsonPath("$.result.cases[0].endMonth").value("2012-03"))
                // 거리는 문자열이다 — 숫자로 내보내면 클라이언트 JSON 파서가 자릿수를 깎는다.
                .andExpect(jsonPath("$.result.cases[0].distance").value("0.412938571034"))
                .andExpect(jsonPath("$.result.cases[0].similar[0]").exists())
                .andExpect(jsonPath("$.result.cases[0].observationMonths.length()").value(3));
    }

    @Test
    @DisplayName("빠진 달이 있는 지표는 어느 달인지 표기된다")
    void omittedMonthsAreLabelled() throws Exception {
        mockMvc.perform(get("/regime-searches").param("mode", "field").param("field", "LABOR"))
                .andExpect(status().isOk())
                // 남은 시점으로 계산했다는 사실이 보이지 않으면 세 달로 비교한 결과와 구분되지 않는다.
                .andExpect(
                        jsonPath(
                                        "$.result.comparisonMonths[?(@.indicatorId=='EMPLOYED_PERSONS')].omitted[0]")
                                .value("2026-06"))
                .andExpect(
                        jsonPath(
                                        "$.result.comparisonMonths[?(@.indicatorId=='EMPLOYED_PERSONS')].note")
                                .isNotEmpty());
    }

    @Test
    @DisplayName("limit은 3 또는 10만 받는다")
    void limitIsThreeOrTen() throws Exception {
        mockMvc.perform(
                        get("/regime-searches")
                                .param("mode", "field")
                                .param("field", "LABOR")
                                .param("limit", "5"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        get("/regime-searches")
                                .param("mode", "field")
                                .param("field", "LABOR")
                                .param("limit", "10"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("등록표에 없는 분야는 404다")
    void unknownFieldIsNotFound() throws Exception {
        mockMvc.perform(get("/regime-searches").param("mode", "field").param("field", "NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("자료 범위 밖 기준월은 404다")
    void baselineMonthOutOfRangeIsNotFound() throws Exception {
        mockMvc.perform(
                        get("/regime-searches")
                                .param("mode", "field")
                                .param("field", "LABOR")
                                .param("baselineMonth", "1970-01"))
                .andExpect(status().isNotFound());
    }

    // -- T103 계산 불가는 200 -----------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "PRODUCTION,SCALE_ZERO",
        "EXTERNAL_TRADE,SCALE_WINDOW_TOO_SHORT",
        "PRICES_COMMODITIES,QUERY_INPUT_MISSING",
        "GLOBAL_ECONOMY,NO_CANDIDATE"
    })
    @DisplayName("계산 불가는 200이고 네 사유가 구분돼 나온다")
    void notComputableIsOkWithDistinctReasons(String field, String reason) throws Exception {
        mockMvc.perform(get("/regime-searches").param("mode", "field").param("field", field))
                // 503·500 을 쓰지 않는다 — 척도가 0인 것은 다시 와도 같다.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("NOT_COMPUTABLE"))
                .andExpect(jsonPath("$.result.statusReason").value(reason))
                .andExpect(jsonPath("$.result.statusNote").isNotEmpty())
                .andExpect(jsonPath("$.result.cases").isEmpty())
                .andExpect(jsonPath("$.result.caseLabel").doesNotExist())
                // SC-007·SC-009 가 "검색 결과 화면의 100%"를 요구하므로 여기서도 빠지지 않는다.
                .andExpect(jsonPath("$.result.dataAsOf").exists())
                .andExpect(jsonPath("$.result.basis.kind").value("HISTORICAL_REVISION"))
                .andExpect(jsonPath("$.result.versions.data").value("FIXTURE-20260915"));
    }

    @Test
    @DisplayName("네 사유의 안내 문구가 서로 다르다")
    void reasonNotesDiffer() throws Exception {
        List<String> notes =
                List.of("PRODUCTION", "EXTERNAL_TRADE", "PRICES_COMMODITIES", "GLOBAL_ECONOMY")
                        .stream()
                        .map(this::statusNoteOf)
                        .toList();
        // 한 문구로 뭉치면 사용자가 할 수 있는 일(다른 달을 고른다 / 기다린다)이 가려진다.
        assertThat(notes).doesNotHaveDuplicates();
    }

    // -- §2 고정된 항목 ----------------------------------------------------------------

    @Test
    @DisplayName("searchId로 지목하면 그 항목이 열린다")
    void searchByIdOpensFixedSnapshot() throws Exception {
        mockMvc.perform(get("/regime-searches/{searchId}", LABOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.searchId").value(LABOR_KEY))
                .andExpect(jsonPath("$.result.baselineOrigin").value("USER_SPECIFIED"));
    }

    @Test
    @DisplayName("정리되어 없는 항목과 철회되어 없는 항목을 구분한다")
    void prunedAndWithdrawnAreDistinguished() throws Exception {
        String gone = "FIELD:LABOR:2026-07-01:FIXTURE-OLD:FIXTURE-REG-1:REGIME-V1";
        mockMvc.perform(get("/regime-searches/{searchId}", gone))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("보관 정리")));

        // 기록이 가리키던 참조가 끊긴 것은 철회뿐이다 — 정리는 기록이 가리키는 항목을 남긴다.
        jdbc.update(
                """
                INSERT INTO regime_search_log (
                    owner_user_id, snapshot_id, snapshot_key, entry_path, baseline_origin, viewed_at)
                VALUES (?, NULL, ?, 'MARKET_FLOW', 'T_G', now())
                """,
                7L,
                gone);
        mockMvc.perform(get("/regime-searches/{searchId}", gone))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("철회")));
    }

    // -- §3 사례 상세 (T102) ----------------------------------------------------------

    @Test
    @DisplayName("사례 상세에 공통 척도와 비교 시점이 있어 손계산으로 대조할 수 있다")
    void caseDetailCarriesScaleForManualCheck() throws Exception {
        mockMvc.perform(
                        get("/regime-searches/cases/{month}", "2012-03")
                                .param("mode", "field")
                                .param("field", "LABOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.endMonth").value("2012-03"))
                .andExpect(jsonPath("$.result.distance").value("0.412938571034"))
                .andExpect(jsonPath("$.result.byField.LABOR").value("0.412938571034"))
                // 질의와 후보의 z 를 같은 척도 하나로 대조한다 — m·s·방법·구간이 모두 있어야
                // 독립 계산으로 z 와 E_i 를 다시 만들 수 있다(SC-003).
                .andExpect(
                        jsonPath("$.result.indicators[?(@.id=='UNEMPLOYMENT_RATE')].scale.median")
                                .value("3.450000000000"))
                .andExpect(
                        jsonPath("$.result.indicators[?(@.id=='UNEMPLOYMENT_RATE')].scale.spread")
                                .value("0.593040887402"))
                .andExpect(
                        jsonPath("$.result.indicators[?(@.id=='UNEMPLOYMENT_RATE')].scale.method")
                                .value("MAD"))
                .andExpect(
                        jsonPath("$.result.indicators[?(@.id=='UNEMPLOYMENT_RATE')].name")
                                .value("실업률"))
                // 비교 시점이 셋이면 z 도 셋이다. 하나로 줄이면 E_i 를 다시 만들 수 없다.
                // 지표를 인덱스가 아니라 id 로 지목한다 — 지표마다 비교 시점 수가 다르고(빠진 달),
                // 순서에 기대면 지표가 하나 늘 때 엉뚱한 지표를 재게 된다.
                .andExpect(
                        jsonPath("$.result.indicators[?(@.id=='UNEMPLOYMENT_RATE')].queryZ[2]")
                                .value("-0.590178531422"))
                .andExpect(
                        jsonPath("$.result.indicators[?(@.id=='UNEMPLOYMENT_RATE')].caseZ[2]")
                                .value("-0.421556093873"))
                .andExpect(
                        jsonPath(
                                        "$.result.indicators[?(@.id=='EMPLOYED_PERSONS')].comparisonMonths[1]")
                                .value("2026-07"))
                // 명목 비중과 실제 거리 기여를 나란히 낸다(spec FR-010).
                .andExpect(
                        jsonPath("$.result.indicators[?(@.id=='UNEMPLOYMENT_RATE')].nominalWeight")
                                .value("0.500000000000"))
                .andExpect(
                        jsonPath("$.result.indicators[?(@.id=='UNEMPLOYMENT_RATE')].distanceShare")
                                .value("0.204173755344"))
                .andExpect(jsonPath("$.result.postPeriod.available").value(false));
    }

    @Test
    @DisplayName("분야 검색에는 분야 기여도가 없고 종합에만 있다")
    void contributionOnlyInComposite() throws Exception {
        // 분야가 하나뿐이라 값이 늘 1 이고, "기여도 100%"로 읽히면 뜻 없는 숫자가 하나 는다.
        mockMvc.perform(
                        get("/regime-searches/cases/{month}", "2012-03")
                                .param("mode", "field")
                                .param("field", "LABOR"))
                .andExpect(jsonPath("$.result.contribution").doesNotExist());
        mockMvc.perform(get("/regime-searches/cases/{month}", "2009-02").param("mode", "composite"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contribution.LABOR").value("0.418300000000"));
    }

    @Test
    @DisplayName("시대별 위치는 문장으로만 나가고 z·분위수가 없다")
    void eraPositionIsSentenceOnly() throws Exception {
        String payload =
                body(
                        get("/regime-searches/cases/{month}", "2012-03")
                                .param("mode", "field")
                                .param("field", "LABOR"));
        java.util.Map<String, Object> era =
                com.jayway.jsonpath.JsonPath.read(
                        payload, "$.result.eraPosition.UNEMPLOYMENT_RATE");
        assertThat(era).containsKey("sentence");
        // 숫자로 놓이면 유사도처럼 읽힌다(spec FR-017·FR-022 ① 11번).
        assertThat(era.keySet())
                .containsExactlyInAnyOrder(
                        "sentence", "method", "windowStart", "windowEnd", "months");
    }

    @Test
    @DisplayName("보조 설명을 만들지 못한 사례도 그대로 조회된다")
    void caseWithoutEraPositionIsStillReadable() throws Exception {
        // 이력이 부족해 보조 설명이 없는 사례다. 0 으로 채우지 않고 키를 두지 않는다(SC-011).
        mockMvc.perform(
                        get("/regime-searches/cases/{month}", "2005-09")
                                .param("mode", "field")
                                .param("field", "LABOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.endMonth").value("2005-09"))
                .andExpect(jsonPath("$.result.distance").value("0.512938571034"))
                .andExpect(jsonPath("$.result.eraPosition").doesNotExist());
    }

    @Test
    @DisplayName("그 검색에 없는 사례는 404다")
    void unknownCaseIsNotFound() throws Exception {
        mockMvc.perform(
                        get("/regime-searches/cases/{month}", "1999-01")
                                .param("mode", "field")
                                .param("field", "LABOR"))
                .andExpect(status().isNotFound());
    }

    // -- §4 전체 후보 ------------------------------------------------------------------

    @Test
    @DisplayName("전체 후보 목록은 표시 후보 밖까지 담는다")
    void candidatesGoBeyondDisplayed() throws Exception {
        mockMvc.perform(
                        get("/regime-searches/candidates")
                                .param("mode", "field")
                                .param("field", "LABOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.searchId").value(LABOR_KEY))
                .andExpect(jsonPath("$.result.candidates.length()").value(3))
                .andExpect(jsonPath("$.result.candidates[0].rank").value(1))
                .andExpect(jsonPath("$.result.candidates[0].distance").value("0.412938571034"));
    }

    // -- §5 기준월 --------------------------------------------------------------------

    @Test
    @DisplayName("기준월 목록의 이름은 등록표에서, 달은 배치가 남긴 표에서 온다")
    void baselineMonthsJoinRegistryAndTable() throws Exception {
        mockMvc.perform(get("/regime-searches/baseline-months"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.byField[?(@.id=='LABOR')].name").value("노동"))
                .andExpect(
                        jsonPath("$.result.byField[?(@.id=='LABOR')].baselineMonth")
                                .value("2026-07"))
                .andExpect(jsonPath("$.result.composite").value("2026-05"))
                .andExpect(jsonPath("$.result.range.earliest").value("1999-04"))
                .andExpect(jsonPath("$.result.note").isNotEmpty());
    }

    // -- T107 같은 요청은 같은 결과 -----------------------------------------------------

    @Test
    @DisplayName("같은 요청을 두 번 보내면 응답이 완전히 같다")
    void sameRequestGivesIdenticalResponse() throws Exception {
        String first = body(get("/regime-searches").param("mode", "field").param("field", "LABOR"));
        String second =
                body(get("/regime-searches").param("mode", "field").param("field", "LABOR"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("T_g로 얻은 달과 같은 달을 지정한 요청이 같은 항목을 연다")
    void tgAndUserSpecifiedOpenTheSameSnapshot() throws Exception {
        // 식별 키에 기준월의 근거가 없으므로 searchId 가 같아야 한다. baselineOrigin 만 다르다.
        mockMvc.perform(
                        get("/regime-searches")
                                .param("mode", "field")
                                .param("field", "LABOR")
                                .param("baselineMonth", "2026-07"))
                .andExpect(jsonPath("$.result.searchId").value(LABOR_KEY))
                .andExpect(jsonPath("$.result.baselineOrigin").value("USER_SPECIFIED"));
    }

    // -- T105 금지 표현 ----------------------------------------------------------------

    @Test
    @DisplayName("응답에 금지 표현이 0건이다")
    void responseHasNoForbiddenExpressions() throws Exception {
        List<String> bodies =
                List.of(
                        body(
                                get("/regime-searches")
                                        .param("mode", "field")
                                        .param("field", "LABOR")),
                        body(get("/regime-searches").param("mode", "composite")),
                        body(
                                get("/regime-searches")
                                        .param("mode", "field")
                                        .param("field", "PRODUCTION")),
                        body(
                                get("/regime-searches/cases/{month}", "2012-03")
                                        .param("mode", "field")
                                        .param("field", "LABOR")),
                        body(
                                get("/regime-searches/candidates")
                                        .param("mode", "field")
                                        .param("field", "LABOR")),
                        body(get("/regime-searches/baseline-months")));
        for (String payload : bodies) {
            for (String banned : FORBIDDEN) {
                assertThat(payload).as("금지 표현 %s (spec FR-022 ①)", banned).doesNotContain(banned);
            }
            assertThat(PERCENT_SIMILAR.matcher(payload).find())
                    .as("'87%% 유사' 같은 확률 표현 (spec FR-022 ①)")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("전략·성과 파라미터를 받지 않는다")
    void strategyParametersAreIgnored() throws Exception {
        // 010 은 전략을 입력으로 받지 않는다. 받으면 "전략을 바꾸면 순위가 바뀐다"가 가능해진다(SC-001).
        String withStrategy =
                body(
                        get("/regime-searches")
                                .param("mode", "field")
                                .param("field", "LABOR")
                                .param("strategy", "MOMENTUM")
                                .param("returnRate", "0.3"));
        String without =
                body(get("/regime-searches").param("mode", "field").param("field", "LABOR"));
        assertThat(withStrategy).isEqualTo(without);
    }

    // -- 씨앗 -------------------------------------------------------------------------

    private String statusNoteOf(String field) {
        try {
            String payload =
                    body(get("/regime-searches").param("mode", "field").param("field", field));
            return com.jayway.jsonpath.JsonPath.read(payload, "$.result.statusNote");
        } catch (Exception e) {
            throw new IllegalStateException(field, e);
        }
    }

    private String body(org.springframework.test.web.servlet.RequestBuilder request)
            throws Exception {
        return mockMvc.perform(request)
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void baseline(String scopeId, String baselineMonth, String earliest) {
        jdbc.update(
                """
                INSERT INTO regime_baseline_month (
                    scope_id, baseline_month, earliest_month, registry_version, data_version,
                    updated_at)
                VALUES (?, ?::date, ?::date, 'FIXTURE-REG-1', 'FIXTURE-20260915', now())
                """,
                scopeId,
                baselineMonth,
                earliest);
    }

    private long snapshot(
            String key,
            String mode,
            String fieldId,
            String month,
            String status,
            String reason,
            String hash) {
        jdbc.update(
                """
                INSERT INTO regime_search_snapshot (
                    snapshot_key, mode, field_id, baseline_month, revision, content_hash,
                    data_version, registry_version, model_version, status, status_reason,
                    scale, comparison_months, ranked_candidates, candidate_counts, data_asof,
                    computed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?::date, 1, ?, 'FIXTURE-20260915', 'FIXTURE-REG-1', 'REGIME-V1',
                    ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, now(), now(), now())
                """,
                key,
                mode,
                fieldId,
                month,
                hash,
                status,
                reason,
                "NEAREST_ONLY".equals(status) ? SCALE_JSON : "{}",
                "NEAREST_ONLY".equals(status) ? COMPARISON_JSON : COMPARISON_JSON,
                "NEAREST_ONLY".equals(status) ? RANKED_JSON : "[]",
                COUNTS_JSON,
                DATA_ASOF_JSON);
        return jdbc.queryForObject(
                "SELECT id FROM regime_search_snapshot WHERE snapshot_key = ?", Long.class, key);
    }

    private void notComputable(String field, String reason, String hashSeed) {
        snapshot(
                "FIELD:" + field + ":2026-07-01:FIXTURE-20260915:FIXTURE-REG-1:REGIME-V1",
                "FIELD",
                field,
                "2026-07-01",
                "NOT_COMPUTABLE",
                reason,
                hashSeed.repeat(64));
    }

    private void laborCase(long snapshotId, int rank, String endMonth, String distance) {
        jdbc.update(
                """
                INSERT INTO regime_search_case (
                    snapshot_id, rank, end_month, distance, indicator_values, explanation,
                    era_position, created_at, updated_at)
                VALUES (?, ?, ?::date, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, now(), now())
                """,
                snapshotId,
                rank,
                endMonth,
                """
                {"value": "%s", "byField": {"LABOR": "%s"}}
                """
                        .formatted(distance, distance),
                INDICATOR_VALUES_JSON,
                EXPLANATION_JSON,
                rank == 1 ? ERA_JSON : null);
    }

    private void compositeCase(long snapshotId) {
        jdbc.update(
                """
                INSERT INTO regime_search_case (
                    snapshot_id, rank, end_month, distance, indicator_values, explanation,
                    era_position, created_at, updated_at)
                VALUES (?, 1, '2009-02-01'::date, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                    now(), now())
                """,
                snapshotId,
                """
                {"value": "0.612938571034",
                 "byField": {"LABOR": "0.512938571034", "PRODUCTION": "0.712938571034"},
                 "contribution": {"LABOR": "0.418300000000", "PRODUCTION": "0.581700000000"}}
                """,
                INDICATOR_VALUES_JSON,
                EXPLANATION_JSON,
                ERA_JSON);
    }

    private static final String SCALE_JSON =
            """
            {"UNEMPLOYMENT_RATE": {"m": "3.450000000000", "s": "0.593040887402",
                                   "method": "MAD", "windowStart": "2016-05",
                                   "windowEnd": "2026-04", "months": 120},
             "EMPLOYED_PERSONS": {"m": "1.200000000000", "s": "0.800000000000",
                                  "method": "IQR", "windowStart": "2016-05",
                                  "windowEnd": "2026-04", "months": 120}}
            """;

    private static final String COMPARISON_JSON =
            """
            {"UNEMPLOYMENT_RATE": {"months": ["2026-05", "2026-06", "2026-07"], "omitted": []},
             "EMPLOYED_PERSONS": {"months": ["2026-05", "2026-07"], "omitted": ["2026-06"]}}
            """;

    private static final String RANKED_JSON =
            """
            [{"endMonth": "2012-03", "distance": "0.412938571034", "rank": 1},
             {"endMonth": "2005-09", "distance": "0.512938571034", "rank": 2},
             {"endMonth": "2001-11", "distance": "0.812938571034", "rank": 3}]
            """;

    private static final String COUNTS_JSON =
            """
            {"evaluated": 412,
             "excluded": {"MISSING_INPUT": 5, "WITHIN_12_MONTHS": 12, "NEIGHBOR_SKIPPED": 3}}
            """;

    private static final String DATA_ASOF_JSON =
            """
            {"indicators": {
                "UNEMPLOYMENT_RATE": {"targetMonth": "2026-07", "publishedAt": "2026-09-12"},
                "EMPLOYED_PERSONS": {"targetMonth": "2026-07", "publishedAt": "2026-09-10",
                                     "revisedAt": "2026-09-10"}},
             "newerReleases": []}
            """;

    private static final String INDICATOR_VALUES_JSON =
            """
            {"UNEMPLOYMENT_RATE": {
                "unit": "%",
                "comparisonMonths": ["2026-05", "2026-06", "2026-07"],
                "caseMonths": ["2012-01", "2012-02", "2012-03"],
                "queryRaw": ["3.100000000000", "3.100000000000", "3.100000000000"],
                "caseRaw": ["3.200000000000", "3.200000000000", "3.200000000000"],
                "queryZ": ["-0.590178531422", "-0.590178531422", "-0.590178531422"],
                "caseZ": ["-0.421556093873", "-0.421556093873", "-0.421556093873"],
                "e": "0.168622437549",
                "nominalWeight": "0.500000000000",
                "distanceShare": "0.204173755344"},
             "EMPLOYED_PERSONS": {
                "unit": "%",
                "comparisonMonths": ["2026-05", "2026-07"],
                "caseMonths": ["2012-01", "2012-03"],
                "queryRaw": ["1.200000000000", "1.200000000000"],
                "caseRaw": ["2.400000000000", "2.400000000000"],
                "queryZ": ["0.000000000000", "0.000000000000"],
                "caseZ": ["1.500000000000", "1.500000000000"],
                "e": "1.500000000000",
                "nominalWeight": "0.500000000000",
                "distanceShare": "0.795826244656"}}
            """;

    private static final String EXPLANATION_JSON =
            """
            {"similar": ["실업률은 3.1%로 당시 3.2%와 비슷합니다."],
             "different": ["취업자 수 증가율은 지금이 1.2%로 당시 2.4%보다 낮습니다."]}
            """;

    private static final String ERA_JSON =
            """
            {"UNEMPLOYMENT_RATE": {"sentence": "실업률 3.2%는 당시로서는 높은 편이었습니다.",
                                   "method": "MAD", "windowStart": "2002-01",
                                   "windowEnd": "2011-12", "months": 120}}
            """;
}
