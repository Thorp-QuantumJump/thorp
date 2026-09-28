package com.ssafy.thorp.marketflow.domain.model;

import com.ssafy.thorp.marketflow.domain.model.NarrativeSubjectSelector.Direction;
import com.ssafy.thorp.marketflow.domain.model.NarrativeSubjectSelector.SelectedSubject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 등록된 문장 틀만 사용해 실제 값의 방향과 지속 기간을 설명한다. */
public class NarrativeFormatter {

    public static final String RULE_VERSION = "market-flow-narrative-v1";

    private static final List<String> FORBIDDEN_TERMS =
            List.of("좋다", "나쁘다", "유망", "전망", "기대", "추천", "비중 확대", "매수", "매도");

    public String format(List<SelectedSubject> subjects, boolean hasCompletePath) {
        if (subjects.isEmpty()) {
            return hasCompletePath ? "최근 3개월 동안 뚜렷한 방향 변화가 없었습니다." : "최근 3개월 경로를 확인할 수 없습니다.";
        }
        String narrative =
                subjects.stream()
                        .map(this::formatSubject)
                        .collect(java.util.stream.Collectors.joining(" "));
        List<String> forbidden = findForbiddenTerms(narrative);
        if (!forbidden.isEmpty()) {
            throw new IllegalStateException("변화 요약에 금지 어휘가 포함되었습니다: " + forbidden);
        }
        return narrative;
    }

    public static List<String> findForbiddenTerms(String narrative) {
        return FORBIDDEN_TERMS.stream().filter(narrative::contains).toList();
    }

    private String formatSubject(SelectedSubject subject) {
        IndicatorDisplay indicator = subject.indicator();
        String movement = subject.direction() == Direction.UP ? "높아졌습니다." : "낮아졌습니다.";
        String value = formatValue(indicator.value()) + indicator.unit();
        String subjectMarker = subjectMarker(indicator.name());
        if (subject.persistence() > 1) {
            return "%s%s %s로 %d개월 연속 %s"
                    .formatted(
                            indicator.name(),
                            subjectMarker,
                            value,
                            subject.persistence(),
                            movement);
        }
        return "%s%s %s로 %d년 %d월에 %s"
                .formatted(
                        indicator.name(),
                        subjectMarker,
                        value,
                        subject.movementMonth().getYear(),
                        subject.movementMonth().getMonthValue(),
                        movement);
    }

    /** 문장 값은 소수 첫째 자리까지 쓴다(S15P21E205-447). 저장·응답의 값은 그대로다. */
    static final int SENTENCE_DECIMALS = 1;

    static String formatValue(BigDecimal value) {
        if (value == null) {
            return "값 없음 ";
        }
        return value.setScale(SENTENCE_DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String subjectMarker(String word) {
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "이";
        }
        return (last - 0xAC00) % 28 == 0 ? "가" : "이";
    }
}
