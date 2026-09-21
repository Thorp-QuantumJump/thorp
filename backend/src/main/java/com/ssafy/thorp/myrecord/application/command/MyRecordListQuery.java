package com.ssafy.thorp.myrecord.application.command;

import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 목록 질의(spec 006 contracts "GET /my-records").
 *
 * <p><b>성과 정렬은 없다</b>(research R7, 원칙 III). 정렬 축은 저장일과 이름뿐이다.
 *
 * <p>상태를 비우면 <b>기본 보기</b>다 — 단일 분석·묶음의 완료와 경제환경의 문턱 적용(정상 완료에 해당). 명시하면 기본 보기를 대체한다(FR-004). 허용하지
 * 않는 값은 400이고 조용히 기본값으로 바꾸지 않는다(원칙 VI) — enum 변환은 컨트롤러 인자 바인딩이 하므로 여기 오는 값은 이미 유효하다.
 *
 * @param cursor 이전 쪽의 마지막 자리. 첫 쪽이면 null
 * @param size 쪽 크기 1~50. 전체 개수는 돌려주지 않는다(research R14)
 */
public record MyRecordListQuery(
        Set<RecordKind> kinds,
        Set<RecordStatus> statuses,
        List<String> sectorCodes,
        List<String> strategyIds,
        LocalDate periodFrom,
        LocalDate periodTo,
        RecordSort sort,
        Cursor cursor,
        int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    /** 기본 보기. 상태를 명시하지 않은 요청이 보는 것. */
    public static final Set<RecordStatus> DEFAULT_STATUSES =
            Set.of(RecordStatus.COMPLETED, RecordStatus.THRESHOLD_APPLIED);

    public MyRecordListQuery {
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        statuses = statuses == null || statuses.isEmpty() ? DEFAULT_STATUSES : Set.copyOf(statuses);
        sectorCodes = sectorCodes == null ? List.of() : List.copyOf(sectorCodes);
        strategyIds = strategyIds == null ? List.of() : List.copyOf(strategyIds);
        sort = sort == null ? RecordSort.SAVED_AT_DESC : sort;
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size 는 1 이상 " + MAX_SIZE + " 이하여야 합니다: " + size);
        }
        if (periodFrom != null && periodTo != null && periodFrom.isAfter(periodTo)) {
            throw new IllegalArgumentException(
                    "periodFrom 이 periodTo 보다 늦습니다: " + periodFrom + " > " + periodTo);
        }
    }

    /** 정렬. 수익률·하락 폭·비용은 선택지에 없다(research R7). */
    public enum RecordSort {
        SAVED_AT_DESC,
        SAVED_AT_ASC,
        NAME_ASC
    }

    /**
     * 쪽 자리. 바깥에는 불투명한 문자열로 나간다 — 안의 값을 계약으로 삼으면 정렬 기준을 바꿀 때 이미 나간 커서를 해석할 수 없다.
     *
     * <p>저장일과 이름을 함께 담아 어느 정렬에서 받은 커서든 그 정렬의 키로 자리를 잡는다. 식별자를 함께 두는 이유: 같은 저장일·같은 이름이 여럿이면 어디까지
     * 보냈는지 구별되지 않아 그 행들이 되풀이된다.
     */
    public record Cursor(Instant savedAt, String name, long id) {

        private static final String DELIMITER = "|";

        public String encode() {
            String raw = savedAt.toString() + DELIMITER + id + DELIMITER + name;
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        /** 모양이 다르면 400이다. 이름은 마지막 조각이라 구분자를 품어도 된다. */
        public static Cursor parse(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            try {
                String raw =
                        new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\" + DELIMITER, 3);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("cursor 를 읽을 수 없습니다.");
                }
                return new Cursor(Instant.parse(parts[0]), parts[2], Long.parseLong(parts[1]));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw new IllegalArgumentException("cursor 를 읽을 수 없습니다.", e);
            }
        }
    }
}
