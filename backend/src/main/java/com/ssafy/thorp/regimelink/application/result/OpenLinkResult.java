package com.ssafy.thorp.regimelink.application.result;

import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.time.LocalDate;
import java.util.List;

/**
 * 사례 열기의 결과 (contracts §2).
 *
 * <p>두 갈래다 — 접수({@code ACCEPTED}, 202)이거나 확인 요구({@code *_CONFIRM_REQUIRED}, 200)다. <b>확인 요구에서는 링크
 * 행도 메시지도 만들지 않는다</b>(Clarifications 09-18 Q1).
 */
public record OpenLinkResult(
        Outcome outcome,
        LinkSearchId searchId,
        List<AcceptedLink> links,
        Period requiredRange,
        List<Affected> affected) {

    public OpenLinkResult {
        links = links == null ? List.of() : List.copyOf(links);
        affected = affected == null ? List.of() : List.copyOf(affected);
    }

    /** 확인이 필요한 이유를 화면이 가릴 수 있게 갈라 둔다 — 문구가 다르다(FR-010, T092). */
    public enum Outcome {
        ACCEPTED,

        /** 어느 실행의 분석 기간이 사례 사후 구간을 덮지 않는다. */
        OUT_OF_PERIOD_CONFIRM_REQUIRED,

        /**
         * 어느 실행이 개별 체결을 남기지 않아 구간 비용을 세울 수 없다 (T092).
         *
         * <p>값을 추정해 채우지 않는다 — 같은 조건으로 다시 계산해야 사후 구간을 볼 수 있다(research R3 DEP-004-011).
         *
         * <p>이름을 {@code OUT_OF_PERIOD_CONFIRM_REQUIRED}와 갈라 두는 것은 <b>화면 문구가 다르기</b> 때문이다 — 앞은 "기간이
         * 모자라다", 뒤는 "이 결과는 사후 구간을 보려면 다시 계산합니다"다.
         */
        FILLS_MISSING_CONFIRM_REQUIRED
    }

    public static OpenLinkResult accepted(LinkSearchId searchId, List<AcceptedLink> links) {
        return new OpenLinkResult(Outcome.ACCEPTED, searchId, links, null, null);
    }

    public static OpenLinkResult confirmRequired(
            Outcome outcome, Period requiredRange, List<Affected> affected) {
        return new OpenLinkResult(outcome, null, List.of(), requiredRange, affected);
    }

    public record AcceptedLink(
            LinkId linkId, String publicToken, Scenario scenario, LinkStatus status) {}

    public record Period(LocalDate start, LocalDate end) {}

    /** 확인이 필요한 실행과 그 분석 기간. 화면이 "무엇이 모자란지"를 보인다. */
    public record Affected(String publicToken, Period period) {}
}
