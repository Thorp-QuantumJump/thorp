package com.ssafy.thorp.news.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.application.exception.NewsQueryException;
import com.ssafy.thorp.news.support.StubSectorCatalog;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 섹터 연결과 섹터 조회가 spec 001의 등록표를 따르는지(spec FR-005·FR-010, T024).
 *
 * <p>핵심은 하나다. <b>012는 섹터 식별자를 만들지 않는다.</b> 뉴스의 주제 등록표에 적힌 섹터도 001에 실재해야 연결된다.
 */
class NewsSectorLinkPolicyTest {

    private static final String REGISTERED = "SEMICONDUCTOR";
    private static final String STALE_ENTRY = "OLD_SECTOR";

    @Nested
    @DisplayName("기사 연결")
    class Linking {

        @Test
        @DisplayName("001에 없는 섹터는 연결하지 않는다")
        void 미등록_제외() {
            NewsSectorLinkPolicy policy =
                    new NewsSectorLinkPolicy(new StubSectorCatalog(Set.of(REGISTERED)));

            Set<String> proposed = new LinkedHashSet<>(List.of(REGISTERED, STALE_ENTRY));

            assertThat(policy.resolve(proposed))
                    .as("없는 섹터로 연결하면 어떤 조회에도 걸리지 않는 연결이 남는다")
                    .containsExactly(REGISTERED);
        }

        @Test
        @DisplayName("대응표가 아직 없으면 섹터를 하나도 연결하지 않는다")
        void 대응표_미발행() {
            NewsSectorLinkPolicy policy = new NewsSectorLinkPolicy(new StubSectorCatalog());

            assertThat(policy.resolve(Set.of(REGISTERED)))
                    .as("주제 연결만 남고 섹터는 대응표가 나온 뒤 다시 수집하면 붙는다")
                    .isEmpty();
        }

        @Test
        @DisplayName("제안이 비어 있으면 빈 결과다")
        void 제안_없음() {
            NewsSectorLinkPolicy policy =
                    new NewsSectorLinkPolicy(new StubSectorCatalog(Set.of(REGISTERED)));

            assertThat(policy.resolve(Set.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("조회 검증")
    class Querying {

        @Test
        @DisplayName("등록된 섹터는 통과한다")
        void 등록된_섹터() {
            NewsSectorQueryPolicy policy =
                    new NewsSectorQueryPolicy(new StubSectorCatalog(Set.of(REGISTERED)));

            assertThat(policy.canQuery(REGISTERED)).isTrue();
        }

        @Test
        @DisplayName("대응표가 있는데 없는 섹터를 물으면 거부한다")
        void 미등록_섹터() {
            NewsSectorQueryPolicy policy =
                    new NewsSectorQueryPolicy(new StubSectorCatalog(Set.of(REGISTERED)));

            assertThatThrownBy(() -> policy.canQuery(STALE_ENTRY))
                    .isInstanceOf(NewsQueryException.class)
                    .as("요청 값을 되비추면 가림 규칙이 응답에서 무너진다")
                    .hasMessageNotContaining(STALE_ENTRY);
        }

        @Test
        @DisplayName("대응표가 아직 없으면 거부가 아니라 빈 화면이다")
        void 대응표_미발행이면_빈_화면() {
            NewsSectorQueryPolicy policy = new NewsSectorQueryPolicy(new StubSectorCatalog());

            assertThat(policy.canQuery(REGISTERED)).as("001 적재 일정이 뉴스 화면을 오류로 만들면 안 된다").isFalse();
        }

        @Test
        @DisplayName("섹터를 지정하지 않으면 늘 통과한다")
        void 전체_조회() {
            NewsSectorQueryPolicy policy = new NewsSectorQueryPolicy(new StubSectorCatalog());

            assertThat(policy.canQuery(null)).isTrue();
        }
    }
}
