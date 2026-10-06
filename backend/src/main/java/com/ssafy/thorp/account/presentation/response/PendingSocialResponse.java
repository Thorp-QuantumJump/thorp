package com.ssafy.thorp.account.presentation.response;

import com.ssafy.thorp.account.application.result.PendingSocialView;
import java.time.Instant;
import java.util.List;

/** 계약 {@code PendingSocialView}. {@code additionalProperties: false}라서 필드를 더하면 계약 위반이다. */
public record PendingSocialResponse(
        String provider,
        String stage,
        String email,
        String suggestedNickname,
        String linkReason,
        String linkTargetMaskedEmail,
        List<String> linkMethods,
        Instant expiresAt) {

    public static PendingSocialResponse from(PendingSocialView view) {
        return new PendingSocialResponse(
                view.provider(),
                view.stage(),
                view.email(),
                view.suggestedNickname(),
                view.linkReason(),
                view.linkTargetMaskedEmail(),
                view.linkMethods(),
                view.expiresAt());
    }
}
