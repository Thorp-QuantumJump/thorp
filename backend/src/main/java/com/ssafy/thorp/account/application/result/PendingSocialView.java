package com.ssafy.thorp.account.application.result;

import java.time.Instant;
import java.util.List;

/**
 * 대기 상태 중 화면에 보여도 되는 값(contracts {@code PendingSocialView}).
 *
 * <p>{@code email}은 대기 상태의 주인인 사용자 자신의 제공자 이메일이라 가리지 않는다. 연결 대상 계정의 이메일은 가린다.
 */
public record PendingSocialView(
        String provider,
        String stage,
        String email,
        String suggestedNickname,
        String linkReason,
        String linkTargetMaskedEmail,
        List<String> linkMethods,
        Instant expiresAt) {}
