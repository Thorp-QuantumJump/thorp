package com.ssafy.thorp.myrecord.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.myrecord.application.service.CsvWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** spec 006 T042. 탈출 세 경우·BOM·CRLF·구획 줄. 새 라이브러리 없이 함수 하나로 끝난다(research R10). */
class CsvWriterTest {

    private final CsvWriter writer = new CsvWriter(new ObjectMapper());

    @Test
    @DisplayName("쉼표·따옴표·줄바꿈이 든 값만 따옴표로 감싸고 안의 따옴표는 두 번 쓴다")
    void escapesOnlyWhenNeeded() {
        assertThat(CsvWriter.escape("plain")).isEqualTo("plain");
        assertThat(CsvWriter.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvWriter.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(CsvWriter.escape("line1\r\nline2")).isEqualTo("\"line1\r\nline2\"");
    }

    @Test
    @DisplayName("파일은 BOM 으로 시작하고 CRLF 로 끝나며 구획마다 # 이름 줄이 붙는다")
    void startsWithBomAndUsesCrlf() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("recordId", "MR-1");
        record.put("name", "반도체, 2차전지");
        Map<String, Object> exclusion = new LinkedHashMap<>();
        exclusion.put("item", "dailyPriceRaw");
        exclusion.put("reason", "SOURCE_REDISTRIBUTION_FORBIDDEN");
        List<Map<String, Object>> exclusions = List.of(exclusion);
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("record", record);
        sections.put("exclusions", exclusions);

        String csv = new String(writer.write(sections), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("﻿# record\r\n");
        assertThat(csv)
                .contains("recordId,name\r\nMR-1,\"반도체, 2차전지\"\r\n")
                .contains(
                        "# exclusions\r\nitem,reason\r\ndailyPriceRaw,SOURCE_REDISTRIBUTION_FORBIDDEN\r\n");
        assertThat(csv.replace("\r\n", "")).doesNotContain("\n");
        assertThat(
                        new String(writer.write(sections), StandardCharsets.UTF_8)
                                .getBytes(StandardCharsets.UTF_8))
                .startsWith(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
    }

    @Test
    @DisplayName("배열 구획은 첫 등장 순서로 열을 모으고, 중첩 값은 JSON 문자열로, 없는 키는 빈 칸으로 둔다")
    void arraySectionsUnionColumns() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("date", "2024-01-02");
        first.put("equity", new BigDecimal("10000000.00"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("date", "2024-01-03");
        second.put("equity", new BigDecimal("10010000.50"));
        Map<String, Object> cashFlow = new LinkedHashMap<>();
        cashFlow.put("type", "DISTRIBUTION");
        cashFlow.put("amount", new BigDecimal("5.00"));
        second.put("cashFlows", List.of(cashFlow));
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("timeseries", List.of(first, second));

        String csv = new String(writer.write(sections), StandardCharsets.UTF_8);

        assertThat(csv)
                .contains("date,equity,cashFlows\r\n")
                .contains("2024-01-02,10000000.00,\r\n")
                .contains(
                        "2024-01-03,10010000.50,\"[{\"\"type\"\":\"\"DISTRIBUTION\"\",\"\"amount\"\":5.00}]\"\r\n");
    }
}
