package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 같은 원문을 가리키는 항목을 하나로 합치는 중복 판정(plan 규칙 3·4).
 *
 * <p>판정 순서가 있다. 주소가 있으면 정규화한 canonical URL, 없으면 매체와 항목 식별자, 그것도 없으면 제목·매체·시각 hash다. 셋 다 만들 수 없으면
 * 수집에서 제외한다. 식별할 수 없는 항목을 넣으면 같은 기사가 회차마다 새로 쌓인다.
 *
 * <p>정규화 규칙 자체는 도메인의 {@link CanonicalNewsUrl}이 갖는다. 여기서는 규칙을 다시 쓰지 않고 부른다.
 */
@Component
public class NewsUrlNormalizer {

    /** 중복 판정에 쓴 규칙 버전. 저장된 기사와 함께 남겨 재현에 쓴다. */
    public static final String RULE_VERSION = CanonicalNewsUrl.RULE_VERSION;

    /**
     * 절대 주소를 정규화한다. 정규화할 수 없으면 빈 값이며 호출부가 그 항목을 제외한다.
     *
     * <p>예외를 던지지 않는 이유는 한 항목의 실패가 회차 전체를 멈추면 안 되기 때문이다.
     */
    public Optional<CanonicalNewsUrl> canonicalize(String absoluteUrl) {
        if (absoluteUrl == null || absoluteUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(CanonicalNewsUrl.normalize(absoluteUrl));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 중복 판정 열쇠를 만든다.
     *
     * @param item 수집한 원시 항목.
     * @param publisherId 매체 식별자. 같은 guid라도 매체가 다르면 별개 기사다.
     */
    public Optional<String> duplicateKey(CollectedNewsItem item, Long publisherId) {
        Optional<CanonicalNewsUrl> canonical = canonicalize(item.url());
        if (canonical.isPresent()) {
            return canonical.map(CanonicalNewsUrl::value);
        }
        if (item.sourceItemId() != null && !item.sourceItemId().isBlank()) {
            return Optional.of(publisherId + "|" + item.sourceItemId().trim());
        }
        if (item.parsedPublishedAt() != null) {
            String businessKey =
                    normalizeTitle(item.title())
                            + "|"
                            + publisherId
                            + "|"
                            + item.parsedPublishedAt();
            return Optional.of(sha256Hex(businessKey));
        }
        // 주소도 항목 식별자도 유효 시각도 없다. 같은 기사를 다시 알아볼 방법이 없으므로 받지 않는다.
        return Optional.empty();
    }

    /** 제목을 비교 가능한 형태로 낮춘다. 원제목은 그대로 보존한다. */
    static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC);
        return normalized
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 64);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }
}
