package com.ssafy.thorp.paramsearch.domain.vo;

import java.util.Objects;

/**
 * 후보의 외부 식별자. 형식은 {@code {searchId}-C{3자리 이상 순번}}이다(contracts/param-search-api.md §4).
 *
 * <p>묶음 식별자를 접두로 두는 이유: 후보는 묶음 밖에서 뜻이 없고, 화면·로그에서 어느 묶음의 후보인지 바로 읽혀야 한다. DB PK는 노출하지 않는다({@link
 * ParamSearchId}와 같은 규약, 원칙 VIII).
 */
public record CandidateId(String value) {

    public CandidateId {
        Objects.requireNonNull(value, "candidateId must not be null");
    }

    /** 묶음 식별자와 묶음 내 순번으로 만든다. 순번이 999를 넘으면 자릿수가 자연히 늘어난다. */
    public static CandidateId of(ParamSearchId searchId, int candidateNo) {
        Objects.requireNonNull(searchId, "searchId must not be null");
        if (candidateNo < 1) {
            throw new IllegalArgumentException("candidateNo must be positive: " + candidateNo);
        }
        return new CandidateId(searchId.value() + "-C" + String.format("%03d", candidateNo));
    }
}
