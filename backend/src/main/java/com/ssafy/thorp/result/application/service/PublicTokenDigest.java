package com.ssafy.thorp.result.application.service;

import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 공개 토큰 → 묘비 키(SHA-256 소문자 16진 64자). 정리 작업이 남기는 쪽과 조회가 찾는 쪽이 같은 함수를 써야 하므로 한 곳에 둔다.
 *
 * <p>솔트를 두지 않는다. 입력이 128비트 난수라 사전·무차별 대입으로 원문을 찾을 수 없고, 솔트를 두면 조회가 해시로 찾지 못한다.
 */
final class PublicTokenDigest {

    private PublicTokenDigest() {}

    static String of(PublicResultToken token) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.value().getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exc) {
            // 자바 표준 구현은 SHA-256 을 반드시 제공한다. 여기 오면 런타임이 망가진 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", exc);
        }
    }
}
