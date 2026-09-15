package com.ssafy.thorp.news.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.domain.vo.NewsAllowedField;
import com.ssafy.thorp.news.domain.vo.PermissionEventType;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 매체 허가의 append-only 기록과 현재 상태 전이(spec FR-015, T040).
 *
 * <p>여기서 보는 것은 <b>지나간 사건이 나중에 바뀌지 않는가</b> 하나다. 상태 하나만 들고 있으면 "언제부터 허가였는지", "그때 무엇을 허용했는지"를 말할 수 없고,
 * 매체가 범위를 다투면 근거를 댈 수 없다.
 *
 * <p>사건 종류와 상태의 조합, 허용 필드 유무 같은 기본 불변식은 {@code NewsDomainModelTest}가 본다. 이 파일은 그 위에서 <b>여러 사건이 이어질
 * 때</b> 앞의 것이 온전히 남는지를 본다.
 *
 * <p>여러 인스턴스가 동시에 사건을 넣을 때의 원자성과, 늦게 입력한 과거 회신이 현재 상태를 덮지 않는 규칙은 저장소의 몫이라 {@code
 * NewsPersistenceIT}가 실제 DB에서 본다. 도메인만으로는 그것을 말할 수 없다.
 */
class NewsPublisherPermissionTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant DENIED_AT = Instant.parse("2026-09-05T00:00:00Z");
    private static final Instant ALLOWED_AT = Instant.parse("2026-09-10T00:00:00Z");

    private static NewsPublisher publisher() {
        return NewsPublisher.register(
                "example-press",
                "예시경제",
                "ko",
                "example.co.kr",
                "https://rss.example.co.kr/economy.xml",
                true);
    }

    @Nested
    @DisplayName("이력은 쌓이기만 한다")
    class AppendOnly {

        @Test
        @DisplayName("주고받은 사건이 순서대로 남고 앞의 것이 바뀌지 않는다")
        void 사건이_쌓인다() {
            List<NewsPublisherPermissionHistory> history = new ArrayList<>();
            history.add(NewsPublisherPermissionHistory.requested(1L, REQUESTED_AT, "메일 발송"));
            history.add(NewsPublisherPermissionHistory.denied(1L, DENIED_AT, "1차 회신"));
            history.add(
                    NewsPublisherPermissionHistory.allowed(
                            1L, Set.of(NewsAllowedField.TITLE), ALLOWED_AT, "2차 회신"));

            assertThat(history)
                    .extracting(NewsPublisherPermissionHistory::permissionStatus)
                    .as("불허 뒤 허가가 와도 불허 사건은 지워지지 않는다")
                    .containsExactly(
                            PermissionStatus.PENDING,
                            PermissionStatus.DENIED,
                            PermissionStatus.ALLOWED);
            assertThat(history.get(1).occurredAt())
                    .as("뒤 사건이 앞 사건의 시각을 건드리지 않는다")
                    .isEqualTo(DENIED_AT);
        }

        @Test
        @DisplayName("기록한 사건을 고치거나 지우는 길이 없다")
        void 고치는_길이_없다() {
            assertThat(NewsPublisherPermissionHistory.class.getMethods())
                    .extracting(java.lang.reflect.Method::getName)
                    .as("setter나 삭제 메서드가 생기면 근거가 사라지는 경로가 열린다")
                    .noneMatch(name -> name.startsWith("set") || name.startsWith("delete"));
        }

        @Test
        @DisplayName("회신을 만든 뒤 넘긴 집합을 고쳐도 기록은 그대로다")
        void 허용_필드는_방어_복사된다() {
            Set<NewsAllowedField> fields = EnumSet.of(NewsAllowedField.TITLE);
            NewsPublisherPermissionHistory response =
                    NewsPublisherPermissionHistory.allowed(1L, fields, ALLOWED_AT, "회신");

            fields.add(NewsAllowedField.ORIGINAL_URL);

            assertThat(response.allows(NewsAllowedField.ORIGINAL_URL))
                    .as("부르는 쪽이 들고 있던 집합으로 허가 범위가 넓어지면 회신에 없던 필드가 나간다")
                    .isFalse();
        }

        @Test
        @DisplayName("기록된 허용 필드는 밖에서 바꿀 수 없다")
        void 허용_필드는_읽기_전용이다() {
            NewsPublisherPermissionHistory response =
                    NewsPublisherPermissionHistory.allowed(
                            1L, Set.of(NewsAllowedField.TITLE), ALLOWED_AT, "회신");

            assertThatThrownBy(() -> response.allowedFields().add(NewsAllowedField.PUBLISHER))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("회신마다 허용 범위가 독립이다")
        void 회신끼리_섞이지_않는다() {
            NewsPublisherPermissionHistory first =
                    NewsPublisherPermissionHistory.allowed(
                            1L,
                            Set.of(NewsAllowedField.TITLE, NewsAllowedField.ORIGINAL_URL),
                            DENIED_AT,
                            "1차 회신");
            NewsPublisherPermissionHistory second =
                    NewsPublisherPermissionHistory.allowed(
                            1L, Set.of(NewsAllowedField.TITLE), ALLOWED_AT, "2차 회신");

            assertThat(first.allows(NewsAllowedField.ORIGINAL_URL)).isTrue();
            assertThat(second.allows(NewsAllowedField.ORIGINAL_URL))
                    .as("범위를 좁힌 회신이 앞 회신의 넓은 범위를 물려받으면 좁힌 뜻이 사라진다")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("현재 상태 전이")
    class CurrentState {

        @Test
        @DisplayName("불허 뒤 허가로 다시 열 수 있다")
        void 재허가() {
            NewsPublisher denied = publisher().withPermission(PermissionStatus.DENIED, DENIED_AT);
            NewsPublisher reopened = denied.withPermission(PermissionStatus.ALLOWED, ALLOWED_AT);

            assertThat(denied.isPubliclyVisible()).isFalse();
            assertThat(reopened.isPubliclyVisible()).isTrue();
            assertThat(reopened.getPermissionUpdatedAt()).isEqualTo(ALLOWED_AT);
        }

        @Test
        @DisplayName("상태를 바꿔도 원래 객체는 그대로다")
        void 원본은_바뀌지_않는다() {
            NewsPublisher pending = publisher();

            NewsPublisher allowed = pending.withPermission(PermissionStatus.ALLOWED, ALLOWED_AT);

            assertThat(pending.getPermissionStatus())
                    .as("제자리에서 고치면 잠금 없이 상태를 바꾼 코드가 조용히 섞인다")
                    .isEqualTo(PermissionStatus.PENDING);
            assertThat(allowed).isNotSameAs(pending);
        }

        @Test
        @DisplayName("재확인 요청은 공개를 다시 닫는다")
        void 재요청은_닫는다() {
            NewsPublisher allowed =
                    publisher().withPermission(PermissionStatus.ALLOWED, ALLOWED_AT);

            NewsPublisher rechecking =
                    allowed.withPermission(PermissionStatus.PENDING, ALLOWED_AT.plusSeconds(60));

            assertThat(rechecking.isPubliclyVisible())
                    .as("회신을 다시 기다리는 매체가 공개에 남아 있으면 확인되지 않은 범위로 계속 나간다")
                    .isFalse();
        }

        @Test
        @DisplayName("요청 사건은 허가 회신으로 세지 않는다")
        void 요청은_회신이_아니다() {
            assertThat(NewsPublisherPermissionHistory.requested(1L, REQUESTED_AT, "메일").eventType())
                    .isEqualTo(PermissionEventType.REQUESTED);
            assertThat(
                            NewsPublisherPermissionHistory.allowed(
                                            1L, Set.of(NewsAllowedField.TITLE), ALLOWED_AT, "회신")
                                    .eventType())
                    .isEqualTo(PermissionEventType.RESPONDED);
        }
    }
}
