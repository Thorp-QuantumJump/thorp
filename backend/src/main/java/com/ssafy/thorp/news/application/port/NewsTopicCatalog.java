package com.ssafy.thorp.news.application.port;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 등록된 경제 주제를 읽는 경계(spec FR-010).
 *
 * <p>주제 등록표는 버전 관리되는 파일이고 그 파일을 읽는 일은 infrastructure의 몫이다. application 계층은 이 경계로만 주제를 본다. 규칙 코드가 파일
 * 형식을 직접 알면 계층 방향이 뒤집힌다.
 *
 * <p>섹터 식별자는 spec 001이 소유하는 외부 값이다. 여기서는 주제에 딸린 참조로만 다루고 012가 만들지 않는다(spec FR-005).
 */
public interface NewsTopicCatalog {

    /**
     * 등록된 경제 주제 하나.
     *
     * @param topicKey 주제 키.
     * @param displayName 한국어 표시명.
     * @param domesticLink 국내 섹터와 연결되는 주제인지 여부. 해외 보도 수용 판정에 쓴다.
     * @param sectorIds 연결되는 외부 섹터 식별자.
     */
    record Topic(
            String topicKey, String displayName, boolean domesticLink, List<String> sectorIds) {

        public Topic {
            sectorIds = List.copyOf(sectorIds);
        }
    }

    /** 등록표 버전. 저장된 연결과 함께 남겨 재현에 쓴다. */
    String version();

    /**
     * 뉴스원이 준 분류 값을 등록된 주제 키로 옮긴다.
     *
     * <p>매핑되지 않으면 빈 값이다. 호출부는 그 값을 연결 근거로 쓰지 않고 미등록으로만 센다.
     */
    Optional<String> resolveTopicKey(String sourceCategory);

    Optional<Topic> findTopic(String topicKey);

    /** 해외 보도를 받을 수 있는 국내 연결 주제 키. */
    Set<String> domesticLinkTopicKeys();
}
