package com.ssafy.thorp.learning.presentation.support;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * 동시에 들어온 두 요청이 같은 새 식별자를 쓰다 UNIQUE 제약에 걸렸는지 가른다.
 *
 * <p>서비스의 존재 검사는 커밋되지 않은 다른 트랜잭션의 행을 보지 못한다. 그래서 둘 다 검사를 통과하고, 늦은 쪽이 INSERT에서 제약에 걸린다. 그 예외는 트랜잭션
 * 경계 안에서 삼키면 롤백 표시가 남아 커밋이 500으로 끝나므로, 경계 밖인 컨트롤러에서 계약의 409로 바꾼다.
 *
 * <p>제약 이름은 마이그레이션({@code V20260917101000__learning_content.sql})이 정한 것이다. 이름이 바뀌면 여기서 가르지 못해 500이
 * 되고, {@code LearningAdminApiIT}의 동시 요청 테스트가 깨진다.
 */
public final class UniqueConstraintViolations {

    public static final String ARTICLE_KEY = "uq_learning_article_key";
    public static final String TERM_KEY = "uq_learning_term_key";
    public static final String PUBLISHED_TERM_KEY = "learning_published_term_pkey";

    private UniqueConstraintViolations() {}

    /** 이 예외가 이름이 {@code constraint}인 제약 위반인가. PostgreSQL 오류 문장에 제약 이름이 따옴표로 실린다. */
    public static boolean violates(DataIntegrityViolationException violation, String constraint) {
        String message = violation.getMostSpecificCause().getMessage();
        return message != null && message.contains("\"" + constraint + "\"");
    }
}
