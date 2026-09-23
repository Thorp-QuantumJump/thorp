package com.ssafy.thorp.regimelink.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.regimelink.application.result.OpenLinkResult;
import java.time.LocalDate;
import java.util.List;

/**
 * 사례 열기 응답 (contracts §2).
 *
 * <p>두 모양이 한 타입에 있다 — 접수(202)면 {@code linkSearchId}·{@code links}가, 확인 요구(200)면 {@code
 * outcome}·{@code requiredRange}·{@code affected}가 찬다. 쓰이지 않는 키는 빠진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenLinkResponse(
        Basis basis,
        String outcome,
        String linkSearchId,
        List<Link> links,
        Period requiredRange,
        List<Affected> affected) {

    public record Basis(String kind) {}

    public record Link(String linkId, String publicToken, String scenario, String status) {}

    public record Period(LocalDate start, LocalDate end) {}

    public record Affected(String publicToken, Period period) {}

    public static OpenLinkResponse from(OpenLinkResult result) {
        Basis basis = new Basis("HISTORICAL_REVISION");
        if (result.outcome() == OpenLinkResult.Outcome.ACCEPTED) {
            return new OpenLinkResponse(
                    basis,
                    null,
                    result.searchId() == null ? null : result.searchId().value(),
                    result.links().stream().map(OpenLinkResponse::link).toList(),
                    null,
                    null);
        }
        return new OpenLinkResponse(
                basis,
                result.outcome().name(),
                null,
                null,
                period(result.requiredRange()),
                result.affected().stream()
                        .map(
                                affected ->
                                        new Affected(
                                                affected.publicToken(), period(affected.period())))
                        .toList());
    }

    private static Link link(OpenLinkResult.AcceptedLink accepted) {
        return new Link(
                accepted.linkId() == null ? null : accepted.linkId().value(),
                accepted.publicToken(),
                accepted.scenario().name(),
                accepted.status().name());
    }

    private static Period period(OpenLinkResult.Period period) {
        return period == null ? null : new Period(period.start(), period.end());
    }
}
