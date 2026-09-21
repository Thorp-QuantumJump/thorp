package com.ssafy.thorp.myrecord.application.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 내보내기 CSV(spec 006 research R10). 한 기록이 여러 표로 나뉘므로 {@code # 구획} 이름 줄 뒤에 표의 머리글과 행을 이어 붙인 단일 파일이다.
 *
 * <ul>
 *   <li><b>UTF-8 BOM</b>으로 시작한다 — 한국어 Windows 표 계산 도구가 BOM 없는 UTF-8을 로컬 인코딩으로 읽어 한글이 깨진다
 *   <li>줄 끝은 CRLF
 *   <li>값에 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싸고 안의 따옴표는 두 번 쓴다(RFC 4180). 탈출은 이 세 경우뿐이라 새 라이브러리를 넣지 않는다
 * </ul>
 *
 * <p>구획이 객체면 한 줄짜리 표(머리글 = 키, 한 행 = 값), 배열이면 첫 등장 순서로 키를 모아 머리글을 만든다. 중첩된 객체·배열은 그 칸에 JSON 문자열로 담는다
 * — 표를 더 쪼개면 "어느 행의 것인가"를 잃는다.
 */
@Component
public class CsvWriter {

    private static final String BOM = "﻿";
    private static final String CRLF = "\r\n";

    private final ObjectMapper mapper;

    /**
     * 소수의 자릿수를 보존하는 사본을 쓴다. 기본 트리 변환은 {@code 10000000.00}을 {@code 1E+7}로 줄여 저장된 값과 다른 글자가 파일에 남는다 —
     * 결과 저장이 decimal 을 문자열로 두는 이유와 같다(scale 은 값의 일부다).
     */
    public CsvWriter(ObjectMapper mapper) {
        this.mapper =
                mapper.copy()
                        .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
                        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public byte[] write(Map<String, Object> sections) {
        StringBuilder out = new StringBuilder(BOM);
        sections.forEach(
                (name, value) -> {
                    out.append("# ").append(name).append(CRLF);
                    JsonNode node = mapper.valueToTree(value);
                    List<JsonNode> rows =
                            node.isArray()
                                    ? toList(node)
                                    : node.isNull() ? List.of() : List.of(node);
                    table(out, rows);
                });
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void table(StringBuilder out, List<JsonNode> rows) {
        Set<String> columns = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            if (row.isObject()) {
                row.fieldNames().forEachRemaining(columns::add);
            } else {
                columns.add("value");
            }
        }
        line(out, columns.stream().map(CsvWriter::escape).toList());
        for (JsonNode row : rows) {
            List<String> cells = new ArrayList<>(columns.size());
            for (String column : columns) {
                JsonNode cell = row.isObject() ? row.get(column) : row;
                cells.add(escape(text(cell)));
            }
            line(out, cells);
        }
        out.append(CRLF);
    }

    private static String text(JsonNode cell) {
        if (cell == null || cell.isNull() || cell.isMissingNode()) {
            return "";
        }
        if (cell.isNumber()) {
            return cell.decimalValue().toPlainString();
        }
        return cell.isValueNode() ? cell.asText() : cell.toString();
    }

    private static void line(StringBuilder out, List<String> cells) {
        out.append(String.join(",", cells)).append(CRLF);
    }

    /** 쉼표·따옴표·CR·LF 가 있으면 따옴표로 감싸고 안의 따옴표는 두 번 쓴다. 그 외에는 그대로다. */
    public static String escape(String value) {
        boolean needsQuote =
                value.indexOf(',') >= 0
                        || value.indexOf('"') >= 0
                        || value.indexOf('\r') >= 0
                        || value.indexOf('\n') >= 0;
        if (!needsQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> rows = new ArrayList<>(array.size());
        for (Iterator<JsonNode> it = array.elements(); it.hasNext(); ) {
            rows.add(it.next());
        }
        return rows;
    }
}
