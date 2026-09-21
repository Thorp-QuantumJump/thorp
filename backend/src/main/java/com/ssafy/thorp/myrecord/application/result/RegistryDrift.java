package com.ssafy.thorp.myrecord.application.result;

import java.util.List;
import java.util.Map;

/**
 * 저장된 설정값이 <b>현재</b> 003 등록표 허용 범위 밖인가(spec 006 FR-008, contracts).
 *
 * <p><b>판정만 하고 고치지 않는다.</b> 범위 밖 값을 자르거나 기본값으로 바꾸면 사용자가 저장해 둔 조건이 조용히 달라진다. 비우고 복원을 제안하는 것은 003 화면의
 * 몫이고, 006은 "무엇이 범위 밖인가"만 알린다.
 *
 * <p>판정은 <b>서버가 한다</b> — 화면이 버전 문자열을 견주지 않는다(원칙 II: 복잡한 계산은 시스템이 맡는다).
 *
 * @param outOfRange 하나라도 범위 밖인가
 * @param fields 범위 밖인 항목들. 없으면 빈 목록이다
 */
public record RegistryDrift(boolean outOfRange, List<Field> fields) {

    public RegistryDrift {
        fields = List.copyOf(fields);
    }

    /**
     * 범위 밖인 설정 항목 하나.
     *
     * @param savedValue 저장된 값 <b>원문 그대로</b>. 자르거나 반올림하지 않는다
     * @param currentRange 지금 등록표가 허용하는 것. 입력형이면 {@code {min, max, step}}, 목록형이면 {@code {choices:
     *     […]}} 이며 등록표에 있는 모양을 그대로 옮긴다
     */
    public record Field(String axis, Object savedValue, Map<String, Object> currentRange) {

        public Field {
            currentRange = Map.copyOf(currentRange);
        }
    }
}
