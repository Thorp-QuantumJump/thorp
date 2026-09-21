package com.ssafy.thorp.myrecord.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.paramsearch.application.result.CandidatePageResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 내보내기 파일의 내용(spec 006 contracts "GET /my-records/{recordId}/export", research R10).
 *
 * <p><b>원천 일별 시세의 원값과 기사 본문은 자리가 없다.</b> 값을 비우는 것이 아니라 필드 자체가 없어 실수로 채울 수 없다(원칙 X, 012 {@code
 * NewsArticleExportResponse}와 같은 방식). 빠진 것은 {@link #exclusions}에 이유와 함께 적는다(FR-016) — 빠진 것이 없어도 키는
 * 있다.
 *
 * <p>종류·상태에 따라 없는 구획은 {@code null}이고 파일에는 키가 나오지 않는다({@link #sections()}). 특히 {@code fills}는 {@code
 * backtest_result.fills_recorded}가 참인 결과에만 있다 — 빈 배열로 담으면 "기록하지 않음"이 "체결 0건"으로 읽힌다(원칙 VI).
 *
 * @param record 항목 자체(식별자·이름·종류·저장일·상태·사유)
 * @param config 저장된 설정값 원문. 분석은 005 {@code config}와 같은 모양이라 전략 파라미터({@code parameters})와
 *     시나리오({@code scenario}·{@code scenarioParams})가 별도 키다(FR-013)
 * @param referenceVersions 상세와 같은 값. {@code worker}·{@code dependencies}는 담지 않는다
 * @param failure 실패·취소·철회 사유. 정상 완료면 null
 * @param summary 핵심 결과. 분석은 005 {@code summary}, 탐색 묶음은 후보 수·선정 결과. 종류마다 모양이 달라 {@code Object}다
 * @param timeseries 계산된 평가 시계열(분석)
 * @param trades 가상 거래 내역(분석)
 * @param fills 개별 체결. 기록한 결과에만 있다
 * @param exceptions 예외 처리 기록(분석)
 * @param searchSpace 탐색 공간·선정 규칙(탐색 묶음)
 * @param candidates 후보별 결과·상태·제외 사유(탐색 묶음)
 * @param regime 010 스냅샷 내용(경제환경). 011 연결 표가 아직 없어 {@code link}는 없다
 * @param exclusions 빠진 항목과 이유. 반드시 있다
 */
public record MyRecordExport(
        Map<String, Object> record,
        Map<String, Object> config,
        Map<String, Object> referenceVersions,
        Failure failure,
        Object summary,
        List<BacktestTimeseriesResult.Point> timeseries,
        List<EngineOutput.TradeResult> trades,
        List<EngineOutput.FillResult> fills,
        List<EngineOutput.ExceptionRecord> exceptions,
        Map<String, Object> searchSpace,
        List<CandidatePageResult.Item> candidates,
        Object regime,
        List<Exclusion> exclusions) {

    public MyRecordExport {
        Objects.requireNonNull(record, "record must not be null");
        Objects.requireNonNull(exclusions, "exclusions must not be null");
        exclusions = List.copyOf(exclusions);
    }

    /** 실패·취소·철회의 사유. 코드 집합은 원본(엔진·007·010)이 소유한다. */
    public record Failure(String code, String message) {}

    /** 파일에 담지 않은 항목 하나(FR-016). */
    public record Exclusion(String item, String reason, String message) {}

    /**
     * 파일에 나가는 구획을 계약 순서대로. {@code null}인 구획은 키를 만들지 않는다.
     *
     * <p>JSON과 CSV가 이 하나를 읽어 두 형식의 구획 순서와 유무가 어긋나지 않는다.
     */
    public Map<String, Object> sections() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("record", record);
        put(out, "config", config);
        put(out, "referenceVersions", referenceVersions);
        put(out, "failure", failure);
        put(out, "summary", summary);
        put(out, "timeseries", timeseries);
        put(out, "trades", trades);
        put(out, "fills", fills);
        put(out, "exceptions", exceptions);
        put(out, "searchSpace", searchSpace);
        put(out, "candidates", candidates);
        put(out, "regime", regime);
        out.put("exclusions", exclusions);
        return out;
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
