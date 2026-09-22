package com.ssafy.thorp.macro.support;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 008 등록표와 발행된 자료 한 벌을 시험용으로 넣는다.
 *
 * <p><b>왜 공용인가.</b> 009 시장 흐름은 008 표를 읽어 화면을 그린다. 그 경로를 시험하려면 008 표가 차 있어야 하는데, 시험마다 따로 채우면 같은 스키마를
 * 두 벌 알게 되고 한쪽만 고쳐진다. 2026-09-21 운영이 멈춘 것도 008과 009 사이가 실제로 이어진 적이 없어서였다.
 *
 * <p>값은 모양만 맞춘 것이다 — 분야 7개와 지표 17개, 세 달치 관측. 값의 정확성은 008 변환 시험이 본다.
 */
public final class MacroRegistrySeed {

    public static final String REGISTRY_VERSION = "REG-1";
    public static final String DISPLAY_VERSION = "DSP-1";
    public static final String DATASET_VERSION = "DS-1";

    /**
     * 분야별 지표 — <b>008 이 실제로 쓰는 식별자</b>다 (FR-001 표).
     *
     * <p>예전에는 {@code production_1} 처럼 지어낸 id 를 썼다. 그러면 009·010 의 검사가 실제 자료에서는 없는 이름으로 돌아, 식별자 표기가
     * 어긋나 있어도 통과한다 — 2026-09 에 010 이 대문자 표기를 쓰고 있던 것이 그렇게 가려져 있었다.
     */
    public static final Map<String, List<String>> FIELD_INDICATORS =
            Map.of(
                    "production",
                            List.of(
                                    "all_industry_production",
                                    "mining_manufacturing_production",
                                    "service_production"),
                    "consumption_investment",
                            List.of(
                                    "retail_sales_index",
                                    "facility_investment_index",
                                    "construction_completed_constant"),
                    "external_trade", List.of("exports", "imports", "trade_balance"),
                    "labor", List.of("employed_persons", "unemployment_rate"),
                    "prices_commodities", List.of("consumer_price_index", "dubai_crude"),
                    "finance_housing",
                            List.of("treasury_bond_3y", "usd_krw_rate", "housing_sale_price"),
                    "overseas", List.of("g20_cli"));

    /** 분야 표시 이름 — 008 화면 분류의 값이다. */
    public static final Map<String, String> FIELD_NAMES =
            Map.of(
                    "production", "생산",
                    "consumption_investment", "소비·투자",
                    "external_trade", "대외거래",
                    "labor", "노동",
                    "prices_commodities", "물가·원자재",
                    "finance_housing", "금융·주택",
                    "overseas", "해외 경기");

    /** 지표 표시 이름 — 008 화면 분류의 값이다. */
    public static final Map<String, String> INDICATOR_NAMES =
            Map.ofEntries(
                    Map.entry("all_industry_production", "전산업생산"),
                    Map.entry("mining_manufacturing_production", "광공업생산"),
                    Map.entry("service_production", "서비스업생산"),
                    Map.entry("retail_sales_index", "소매판매액지수"),
                    Map.entry("facility_investment_index", "설비투자지수"),
                    Map.entry("construction_completed_constant", "건설기성액(불변)"),
                    Map.entry("exports", "수출"),
                    Map.entry("imports", "수입"),
                    Map.entry("trade_balance", "무역수지"),
                    Map.entry("employed_persons", "취업자 수"),
                    Map.entry("unemployment_rate", "실업률"),
                    Map.entry("consumer_price_index", "소비자물가"),
                    Map.entry("dubai_crude", "두바이유"),
                    Map.entry("treasury_bond_3y", "국고채 3년"),
                    Map.entry("usd_krw_rate", "원/달러 환율"),
                    Map.entry("housing_sale_price", "주택 매매가격"),
                    Map.entry("g20_cli", "G20 경기선행지수"));

    public static final List<String> FIELD_ORDER =
            List.of(
                    "production",
                    "consumption_investment",
                    "external_trade",
                    "labor",
                    "prices_commodities",
                    "finance_housing",
                    "overseas");

    /** 관측이 든 세 달. 화면 경로가 t-2·t-1·t 세 점을 요구한다. */
    public static final List<String> MONTHS = List.of("2026-05-01", "2026-06-01", "2026-07-01");

    private MacroRegistrySeed() {}

    /**
     * 008 표를 비운다. <b>검사마다 다시 심을 수 있게</b> 시딩이 먼저 부른다.
     *
     * <p>지우는 차례는 참조 방향의 역순이다. 이 목록이 스키마보다 뒤처지면 새 표만 남아 다음 시딩이 유니크 제약에 걸리므로, 표가 늘면 여기도 함께 는다.
     */
    public static void clear(JdbcTemplate jdbc) {
        for (String table :
                List.of(
                        "macro_observation_lineage",
                        "macro_observation",
                        "macro_baseline",
                        "macro_current_publication",
                        "macro_dataset_version",
                        "macro_raw_revision",
                        "macro_collection_item_status",
                        "macro_collection_attempt",
                        "macro_display_indicator",
                        "macro_display_field",
                        "macro_display_version",
                        "macro_indicator_definition",
                        "macro_calculation_registry_version")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /** 계산 등록표·화면 분류·현재 포인터. 자료 버전은 아직 없다. */
    public static void seedRegistry(JdbcTemplate jdbc) {
        clear(jdbc);
        jdbc.update(
                """
                INSERT INTO macro_calculation_registry_version (
                    version_key, value_rule_fingerprint, change_reason, status, created_at)
                VALUES (?, repeat('a', 64), '최초 등록', 'PUBLISHED', now())
                """,
                REGISTRY_VERSION);
        jdbc.update(
                """
                INSERT INTO macro_display_version (version_key, change_reason, created_at)
                VALUES (?, '최초', now())
                """,
                DISPLAY_VERSION);
        long registryId = idOf(jdbc, "macro_calculation_registry_version", REGISTRY_VERSION);
        long displayId = idOf(jdbc, "macro_display_version", DISPLAY_VERSION);

        int order = 0;
        int fieldOrder = 0;
        for (String fieldId : FIELD_ORDER) {
            fieldOrder++;
            jdbc.update(
                    """
                    INSERT INTO macro_display_field (
                        display_version_id, field_id, display_name, display_order, description)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    displayId,
                    fieldId,
                    FIELD_NAMES.get(fieldId),
                    fieldOrder,
                    fieldId + " 설명");
            for (String indicatorId : FIELD_INDICATORS.get(fieldId)) {
                order++;
                // 두바이유만 작성기관이 다르다 — 제공기관과 작성기관은 다를 수 있다(FR-016).
                boolean foreignAgency = indicatorId.equals("dubai_crude");
                jdbc.update(
                        """
                        INSERT INTO macro_indicator_definition (
                            registry_version_id, indicator_id, field_id, primary_source,
                            raw_unit, transformed_unit, transform_rule, agency, attribution,
                            license_status, license_review_status, refresh_months_n,
                            nominal_weight, revision_history_available)
                        VALUES (?, ?, ?, 'kosis', '2020=100', '%', 'YOY_GROWTH', ?, ?,
                                'ALLOWED', 'VERIFIED', 3, '"0.0476"'::jsonb, true)
                        """,
                        registryId,
                        indicatorId,
                        fieldId,
                        foreignAgency ? "국제통화기금(IMF)" : "통계청",
                        foreignAgency ? "한국은행 경제통계시스템(ECOS)" : "국가통계포털(KOSIS)");
                jdbc.update(
                        """
                        INSERT INTO macro_display_indicator (
                            display_version_id, indicator_id, display_name, display_order)
                        VALUES (?, ?, ?, ?)
                        """,
                        displayId,
                        indicatorId,
                        INDICATOR_NAMES.get(indicatorId),
                        order);
            }
        }
        jdbc.update(
                """
                INSERT INTO macro_current_publication (
                    dataset_version_id, registry_version_id, display_version_id, switched_at)
                VALUES (NULL, ?, ?, now())
                """,
                registryId,
                displayId);
    }

    /** 세 달치 관측과 기준월이 든 자료 버전을 발행하고 포인터를 옮긴다. */
    public static void seedPublishedDataset(JdbcTemplate jdbc) {
        long registryId = idOf(jdbc, "macro_calculation_registry_version", REGISTRY_VERSION);
        jdbc.update(
                """
                INSERT INTO macro_dataset_version (
                    version_key, processing_registry_version_id, value_rule_fingerprint,
                    status, complete_indicator_count, published_at, created_at)
                VALUES (?, ?, repeat('a', 64), 'PUBLISHED', 17, now(), now())
                """,
                DATASET_VERSION,
                registryId);
        long datasetId = idOf(jdbc, "macro_dataset_version", DATASET_VERSION);

        for (String fieldId : FIELD_ORDER) {
            for (String indicatorId : FIELD_INDICATORS.get(fieldId)) {
                for (String month : MONTHS) {
                    jdbc.update(
                            """
                            INSERT INTO macro_observation (
                                dataset_version_id, indicator_id, target_month,
                                raw_value, transformed_value, released_at, received_at)
                            VALUES (?, ?, ?::date,
                                    jsonb_build_array(jsonb_build_array(?, '101.2')),
                                    '"1.4"'::jsonb, now(), now())
                            """,
                            datasetId,
                            indicatorId,
                            month,
                            month.substring(0, 7));
                }
            }
            jdbc.update(
                    """
                    INSERT INTO macro_baseline (
                        dataset_version_id, registry_version_id, scope_id,
                        reference_month, reference_month_held_back_by, computed_at)
                    VALUES (?, ?, ?, '2026-07-01'::date, '[]'::jsonb, now())
                    """,
                    datasetId,
                    registryId,
                    fieldId);
        }
        jdbc.update(
                """
                INSERT INTO macro_baseline (
                    dataset_version_id, registry_version_id, scope_id,
                    reference_month, reference_month_held_back_by, computed_at)
                VALUES (?, ?, 'ALL', '2026-07-01'::date, '[]'::jsonb, now())
                """,
                datasetId,
                registryId);
        jdbc.update("UPDATE macro_current_publication SET dataset_version_id = ?", datasetId);
    }

    /**
     * 분야 기준월보다 <b>뒤에</b> 발표된 값 하나를 더한다.
     *
     * <p>분야 기준월은 그 분야에서 가장 늦은 지표에 맞춰 당겨지므로, 먼저 나온 지표는 그보다 새 달의 값을 이미 갖고 있다. 그 값이 경로에 섞이지 않고 따로 실리는지
     * 보는 시험이 이것을 쓴다. 기본 시딩에 넣지 않는 이유는 관측 수를 세는 다른 시험이 있어서다.
     */
    public static void seedLaterRelease(JdbcTemplate jdbc, String indicatorId, String month) {
        // raw_value 는 008 이 쓰는 계보 배열 모양이어야 한다. 스칼라로 심으면 이 시험은
        // 통과하면서 운영만 400 으로 떨어진다 — 2026-09-22 에 실제로 그렇게 멈췄다.
        long datasetId = idOf(jdbc, "macro_dataset_version", DATASET_VERSION);
        jdbc.update(
                """
                INSERT INTO macro_observation (
                    dataset_version_id, indicator_id, target_month,
                    raw_value, transformed_value, released_at, received_at)
                VALUES (?, ?, ?::date,
                        jsonb_build_array(jsonb_build_array(?, '102.9')),
                        '"2.1"'::jsonb, now(), now())
                """,
                datasetId,
                indicatorId,
                month,
                month.substring(0, 7));
    }

    private static long idOf(JdbcTemplate jdbc, String table, String versionKey) {
        Long id =
                jdbc.queryForObject(
                        "SELECT id FROM " + table + " WHERE version_key = ?",
                        Long.class,
                        versionKey);
        if (id == null) {
            throw new IllegalStateException(table + " 에 " + versionKey + " 가 없다");
        }
        return id;
    }
}
