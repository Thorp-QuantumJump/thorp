package com.ssafy.thorp.news.domain.vo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 외부 노출 식별자를 사업 키에서 결정론적으로 만드는 공통 규칙(spec FR-025).
 *
 * <p>사업 키의 SHA-256을 RFC 4648 base32 소문자로 인코딩해 앞 32자를 쓴다. 같은 대상을 다시 수집하면 같은 값이 나오므로 재현 기록과 외부 참조가
 * 유지된다.
 *
 * <p>난수와 DB 시퀀스를 쓰지 않는다. 순번을 인코딩하면 내부 PK를 사실상 노출하게 된다.
 *
 * <p>서로 다른 사업 키가 같은 값을 만드는 충돌은 여기서 막을 수 없다. 저장 단계의 UNIQUE 제약이 감지하며, 그때 기존 기록을 덮어쓰지 않고 새 항목을 제외한다.
 */
final class StableNewsId {

    /** 인코딩 결과의 문자 집합과 길이. 외부 계약(openapi.yaml)의 pattern과 같다. */
    static final String REGEX = "^[a-z2-7]{32}$";

    static final Pattern PATTERN = Pattern.compile(REGEX);

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final int LENGTH = 32;

    private StableNewsId() {}

    /** 사업 키에서 안정 식별자를 만든다. */
    static String derive(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            throw new IllegalArgumentException("businessKey는 비어 있을 수 없다");
        }
        return encode(sha256(businessKey.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }

    /** RFC 4648 base32 소문자. 앞 32자만 쓰므로 padding은 만들지 않는다. */
    private static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder(LENGTH);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5 && out.length() < LENGTH) {
                out.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1F]);
                bitsLeft -= 5;
            }
            if (out.length() == LENGTH) {
                break;
            }
        }
        return out.toString();
    }
}
