-- 연결 규칙 v2 (spec FR-005·FR-010, S15P21E205-437).
-- v1은 주제-섹터 연결이 모두 비어 있어 어떤 기사도 섹터에 연결되지 않았다. v2는 섹터가 분명한 기존
-- 주제(반도체)에 spec 001 섹터 식별자를 채우고, 001 대응표의 8개 섹터마다 기사 제목 키워드 주제를 둔다.
-- 국내 매체 대부분이 기사별 분류 값을 주지 않아, 제목 말고는 섹터에 연결할 근거가 없기 때문이다.
--
-- 스키마는 바꾸지 않는다. 제목 키워드 근거는 기존 news_article_topic.match_evidence_key에
-- 'title:키워드'로 남는다. v1 행은 고치지 않고 유효 구간만 닫는다(원칙 VIII).
INSERT INTO news_rule_version
    (rule_version, rule_type, definition, effective_from, created_at, updated_at)
VALUES
    ('news-link-v2', 'LINK',
     '{"registryFile": "news/news-link-v2.json", "trustSourceCategoryDirectly": false,
       "sectorIdOwner": "spec-001", "overseasRequiresDomesticTopic": true,
       "titleKeywords": {"match": "substring", "caseInsensitive": true,
                         "exclusionsRemovedFirst": true, "evidencePrefix": "title:",
                         "additiveToCategoryAndChannel": true, "domesticLink": false},
       "relinkStoredArticles": "addOnly"}'::JSONB,
     TIMESTAMP WITH TIME ZONE '2026-09-24T00:00:00Z', NOW(), NOW());

UPDATE news_rule_version
SET retired_at = TIMESTAMP WITH TIME ZONE '2026-09-24T00:00:00Z',
    updated_at = NOW()
WHERE rule_version = 'news-link-v1';
