-- 묶음 규칙 v2 (spec FR-011·SC-002).
-- v1(어절 Jaccard 0.60)은 실제 수집분에서 같은 사건을 거의 묶지 못했다. 한국어는 조사·어미가
-- 낱말에 붙어 같은 낱말이 어절로는 다른 토큰이 되기 때문이다. 문자 2-gram으로 바꾸고 문턱을
-- 낮추되, 정형 제목의 오병합을 막는 고유어 조건을 함께 둔다.
--
-- v1 행을 고치지 않고 새 버전을 더한다(원칙 VIII). 이미 저장된 묶음은 v1 판정 결과이며 그 근거가
-- 남아 있어야 재현할 수 있다.
INSERT INTO news_rule_version
    (rule_version, rule_type, definition, effective_from, created_at, updated_at)
VALUES
    ('news-grouping-v2', 'GROUPING',
     '{"maxTimeGapHours": 48, "minSharedTopics": 1, "titleBigramJaccardThreshold": 0.30,
       "linkage": "complete", "mergeOnUncertain": false, "transitiveBridge": false,
       "rareTermGate": {"maxDocumentFrequency": 3, "minTermLength": 2,
                        "scope": "candidateSet", "rejectsWhenNoSharedRareTerm": true},
       "titleNormalization": ["NFKC", "lowercase", "stripPunctuation", "collapseWhitespace",
                              "stripSpaces"]}'::JSONB,
     TIMESTAMP WITH TIME ZONE '2026-09-16T00:00:00Z', NOW(), NOW());

-- v1은 더 쓰지 않는다. 행은 남겨 두고 유효 구간만 닫는다.
UPDATE news_rule_version
SET retired_at = TIMESTAMP WITH TIME ZONE '2026-09-16T00:00:00Z',
    updated_at = NOW()
WHERE rule_version = 'news-grouping-v1';
