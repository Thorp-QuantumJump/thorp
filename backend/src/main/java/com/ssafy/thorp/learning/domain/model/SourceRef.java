package com.ssafy.thorp.learning.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 출처 이름과 주소(FR-007). 글자 조각의 출처 표시와 제도 안내의 출처가 쓴다(data-model §5-2·§5-3).
 *
 * <p>이름은 저장할 때 비어 있어도 된다. 비었는지는 게시 전 검사({@code ELEMENT_FORMAT})가 가른다. 주소는 {@code http}·{@code
 * https}만 받는다. 화면이 연결로 그리므로 다른 체계(예: {@code javascript:})를 저장하지 않는다.
 */
public record SourceRef(String label, String url) {

    public static final int LABEL_MAX_LENGTH = 100;
    public static final int URL_MAX_LENGTH = 2000;
    public static final String URL_REGEX = "^https?://\\S+$";

    private static final Pattern URL_PATTERN = Pattern.compile(URL_REGEX);

    public SourceRef {
        Objects.requireNonNull(label, "source label must not be null");
        Objects.requireNonNull(url, "source url must not be null");
        if (label.length() > LABEL_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "source label must be at most " + LABEL_MAX_LENGTH + " characters");
        }
        if (url.length() > URL_MAX_LENGTH || !URL_PATTERN.matcher(url).matches()) {
            throw new IllegalArgumentException("source url must be an http(s) address");
        }
    }
}
