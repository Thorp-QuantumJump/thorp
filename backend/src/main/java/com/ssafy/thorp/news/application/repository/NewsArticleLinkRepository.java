package com.ssafy.thorp.news.application.repository;

import java.util.Set;

/**
 * 기사와 섹터·주제의 연결 저장 경계(spec FR-010).
 *
 * <p>WB01의 스키마에는 연결 테이블이 있었지만 저장 경계가 없었다. 수집이 연결을 남기려면 이 경계가 필요하다.
 *
 * <p>주제는 등록표가 원본이다. 저장소는 등록표에 있는 주제를 행으로 확보할 뿐 새 주제를 만들지 않는다. 섹터 식별자는 spec 001이 소유하므로 여기서 만들지 않고 받은
 * 값을 그대로 연결한다(spec FR-005).
 */
public interface NewsArticleLinkRepository {

    /**
     * 등록표의 주제를 행으로 확보하고 식별자를 돌려준다. 이미 있으면 그대로 쓴다.
     *
     * @param topicKey 주제 키.
     * @param displayName 한국어 표시명.
     * @param domesticLink 국내 연결 주제 여부.
     * @param ruleVersion 연결 규칙 버전.
     */
    Long ensureTopic(String topicKey, String displayName, boolean domesticLink, String ruleVersion);

    /**
     * 한 기사의 연결을 규칙 버전 단위로 다시 쓴다.
     *
     * <p>같은 규칙 버전의 기존 연결만 지우고 새로 넣는다. 다른 버전으로 만든 연결은 남겨 과거 결과의 근거가 사라지지 않게 한다(원칙 VIII).
     */
    void replaceLinks(
            Long articleId,
            Set<String> sectorIds,
            java.util.Map<Long, String> topicEvidenceByTopicId,
            String ruleVersion);

    /**
     * 기사별로 연결된 주제 키를 읽는다.
     *
     * <p>묶음 후보에 이전 회차의 기사를 넣으려면 그 기사의 주제를 알아야 한다. 주제가 없으면 공통 주제 조건을 판정할 수 없어 묶이지 않는다.
     */
    java.util.Map<Long, java.util.Set<String>> findTopicKeysByArticleIds(
            java.util.Collection<Long> articleIds);

    /**
     * 기사별로 연결된 섹터 식별자를 읽는다.
     *
     * <p>응답이 "이 묶음이 어느 섹터와 이어지는지" 보이려면 필요하다. 값은 spec 001이 소유하며 012는 저장된 연결을 그대로 돌려줄 뿐 새로 만들지 않는다.
     */
    java.util.Map<Long, java.util.Set<String>> findSectorIdsByArticleIds(
            java.util.Collection<Long> articleIds);
}
