package com.ssafy.thorp.paramsearch.application.command;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import java.util.List;
import java.util.Locale;

/**
 * 후보 목록 조회 조건(contracts/param-search-api.md §4).
 *
 * @param status 이 상태의 후보만. null이면 전체
 * @param selected 표시 후보만/아닌 것만. null이면 전체
 * @param sort 정렬 기준. 허용 목록 밖이면 400이다
 * @param descending 내림차순인가. 성과 지표는 큰 값이 위로 오는 것이 기본이다
 */
public record FindCandidatesQuery(
        int page,
        int size,
        CandidateStatus status,
        Boolean selected,
        Sort sort,
        boolean descending) {

    /** 계약이 정한 최대 쪽 크기. 넘겨서 한 번에 다 받으려는 요청을 막는다. */
    public static final int MAX_SIZE = 200;

    public static final int DEFAULT_SIZE = 50;

    public FindCandidatesQuery {
        if (page < 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page는 0 이상이어야 합니다: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "size는 1에서 %d 사이여야 합니다: %d".formatted(MAX_SIZE, size));
        }
    }

    /**
     * 허용된 정렬 기준(FR-017).
     *
     * <p><b>목록을 열어 두지 않는 이유</b>: 임의의 필드명을 받으면 저장 구조가 그대로 API 계약이 되어 컬럼을 바꿀 수 없게 된다. 허용 목록은 화면이 실제로
     * 쓰는 축이며, 여기 없는 값은 400이다.
     */
    public enum Sort {
        CANDIDATE_NO("candidateNo"),
        NET_RETURN("netReturn"),
        MAX_DRAWDOWN("maxDrawdown"),
        TRADING_COST("tradingCost"),
        STABILITY("stability");

        private final String parameter;

        Sort(String parameter) {
            this.parameter = parameter;
        }

        public String parameter() {
            return parameter;
        }

        /** 기본값은 순번 오름차순이다 — 접수 순서이며 지표에 기대지 않는 유일한 재현 가능한 나열이다. */
        public static Sort from(String raw) {
            if (raw == null || raw.isBlank()) {
                return CANDIDATE_NO;
            }
            for (Sort sort : values()) {
                if (sort.parameter.equalsIgnoreCase(raw)) {
                    return sort;
                }
            }
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "정렬 기준은 %s 중 하나여야 합니다: %s"
                            .formatted(
                                    List.of(values()).stream().map(Sort::parameter).toList(), raw));
        }
    }

    /** {@code sort=netReturn,desc} 형식을 나눈다. 방향을 생략하면 지표는 내림차순, 순번은 오름차순이다. */
    public static FindCandidatesQuery of(
            Integer page, Integer size, String status, Boolean selected, String sort) {
        String[] parts = sort == null ? new String[0] : sort.split(",", 2);
        Sort parsed = Sort.from(parts.length > 0 ? parts[0] : null);
        boolean descending =
                parts.length > 1
                        ? "desc".equalsIgnoreCase(parts[1].trim())
                        : parsed != Sort.CANDIDATE_NO;
        return new FindCandidatesQuery(
                page == null ? 0 : page,
                size == null ? DEFAULT_SIZE : size,
                parseStatus(status),
                selected,
                parsed,
                descending);
    }

    private static CandidateStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CandidateStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exc) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 후보 상태입니다: " + raw);
        }
    }
}
