package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.port.NewsTopicCatalog;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 짧은 사실 요약을 만든다(spec FR-003, plan "요약·정렬 규칙 v1").
 *
 * <p><b>제목과 메타데이터만 쓴다.</b> 기사 본문과 RSS description은 읽지 않는다. 새로운 사실·인과·전망·판정·투자 시사점을 덧붙이지 않는다. 문장은 대표
 * 제목을 그대로 옮겨 담는 틀이라 모델이나 규칙이 바뀌어도 없는 내용이 생기지 않는다.
 *
 * <p><b>근거가 부족하면 만들지 않는다.</b> 제목·매체명이 있고 등록된 주제가 하나 이상일 때만 문장을 만든다. 조건을 못 맞추면 빈 값을 돌려주고 호출부가 그 기사를
 * 표시 대상에서 제외한다. 묶음 수를 맞추려고 다른 기사나 생성한 내용으로 대체하지 않는다(spec FR-001).
 *
 * <p>같은 입력이면 같은 문장이 나온다. 공개 응답은 허가된 매체의 기사만 넣어 이 규칙을 다시 실행한다(spec FR-016).
 */
@Component
public class NewsSummaryPolicy {

    private final NewsTopicCatalog topics;

    public NewsSummaryPolicy(NewsTopicCatalog topics) {
        this.topics = topics;
    }

    /**
     * 요약에 쓰는 입력. 여기 없는 값은 요약에 들어가지 않는다.
     *
     * @param representativeTitle 대표 기사 제목.
     * @param publisherName 대표 기사의 매체 이름.
     * @param topicKeys 묶음에 연결된 주제 키.
     * @param distinctPublisherCount 서로 다른 매체 수.
     */
    public record Input(
            String representativeTitle,
            String publisherName,
            Set<String> topicKeys,
            int distinctPublisherCount) {

        public Input {
            topicKeys = Set.copyOf(topicKeys);
        }
    }

    /** 근거가 충분하면 한 문장을 만든다. 아니면 빈 값이다. */
    public Optional<String> summarize(Input input) {
        if (isBlank(input.representativeTitle()) || isBlank(input.publisherName())) {
            return Optional.empty();
        }
        if (input.topicKeys().isEmpty()) {
            return Optional.empty();
        }

        String subject =
                input.distinctPublisherCount() > 1
                        ? input.publisherName() + " 등 " + input.distinctPublisherCount() + "개 매체"
                        : input.publisherName();
        return Optional.of(subject + "가 '" + input.representativeTitle().trim() + "'라고 보도했습니다.");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
