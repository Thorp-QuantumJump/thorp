package com.ssafy.thorp.news.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.application.exception.NewsQueryException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 섹터 여럿·{@code limit}을 받는 조회 요청의 규칙(spec FR-001 ③, 2026-10-02). */
class NewsDigestQueryTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-10T00:00:00Z");

    @Test
    @DisplayName("빈 값과 중복 섹터를 지우고 처음 나온 순서를 지킨다")
    void 섹터를_정리한다() {
        NewsDigestQuery query =
                new NewsDigestQuery(
                        Arrays.asList("NEWS_IT", " ", null, "SEMICONDUCTOR", "NEWS_IT"),
                        null,
                        null,
                        null);

        assertThat(query.sectorIds()).containsExactly("NEWS_IT", "SEMICONDUCTOR");
        assertThat(query.hasManySectors()).isTrue();
        assertThat(query.sectorId()).as("여럿이면 응답의 sectorId는 비운다").isNull();
    }

    @Test
    @DisplayName("섹터 하나짜리 생성자는 예전과 같은 뜻이다")
    void 섹터_하나() {
        assertThat(NewsDigestQuery.current("SEMICONDUCTOR").sectorId()).isEqualTo("SEMICONDUCTOR");
        assertThat(NewsDigestQuery.current(null).hasSector()).isFalse();
        assertThat(NewsDigestQuery.current("  ").hasSector()).as("빈 문자열은 전체다").isFalse();
    }

    @Test
    @DisplayName("한 번에 담을 수 있는 섹터 수를 넘으면 받지 않는다")
    void 섹터_수_상한() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= NewsDigestQuery.MAX_SECTORS; i++) {
            many.add("S" + i);
        }

        assertThatThrownBy(() -> new NewsDigestQuery(many, null, null, null))
                .isInstanceOf(NewsQueryException.class);
    }

    @Test
    @DisplayName("과거 구간은 섹터 하나까지만 받는다")
    void 과거_구간은_섹터_하나() {
        assertThatThrownBy(
                        () ->
                                new NewsDigestQuery(
                                        List.of("NEWS_IT", "SEMICONDUCTOR"), FROM, TO, null))
                .isInstanceOf(NewsQueryException.class);
        assertThat(new NewsDigestQuery(List.of("NEWS_IT"), FROM, TO, null).isHistorical()).isTrue();
    }

    @Test
    @DisplayName("limit은 1 이상이어야 하고, 상한보다 크면 상한까지만 준다")
    void limit() {
        assertThatThrownBy(() -> new NewsDigestQuery(List.of(), null, null, 0))
                .isInstanceOf(NewsQueryException.class);

        assertThat(new NewsDigestQuery(List.of(), null, null, 2).limitWithin(10)).isEqualTo(2);
        assertThat(new NewsDigestQuery(List.of(), null, null, 12).limitWithin(10)).isEqualTo(10);
    }

    @Test
    @DisplayName("limit을 주지 않으면 상한이 아니라 예전 개수(3)다")
    void limit이_없으면_예전_개수() {
        NewsDigestQuery query = new NewsDigestQuery(List.of(), null, null, null);

        assertThat(query.limitWithin(10))
                .as("limit 없이 부르는 사례 화면은 받은 묶음을 모두 그린다. 상한을 올려도 그 화면은 그대로여야 한다")
                .isEqualTo(NewsDigestQuery.DEFAULT_LIMIT)
                .isEqualTo(3);
        assertThat(query.limitWithin(2)).as("상한이 더 작으면 상한이다").isEqualTo(2);
    }
}
