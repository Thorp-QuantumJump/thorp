-- 012 시장 흐름 뉴스 묶음. 근거: specs/012-market-news-digest/data-model.md.
--
-- 원칙
--  - 기사 본문·HTML·이미지·GDELT Tone에 해당하는 컬럼을 두지 않는다 (spec FR-002).
--  - 외부 공개 허가·기간·섹터·중복 제거에 쓰는 값은 JSONB가 아니라 컬럼과 관계로 둔다.
--  - 모든 외부 시각은 TIMESTAMP WITH TIME ZONE이다.
--  - 섹터 식별자는 spec 001이 소유한다. 여기에 섹터 마스터 테이블을 만들지 않는다 (spec FR-005).
--  - 뉴스 도메인 테이블은 2026-11-07 일괄 삭제 대상이며 예외로 보존하는 테이블이 없다 (spec FR-019).

-- 시퀀스. 증분은 각 Entity의 allocationSize와 같아야 한다.
CREATE SEQUENCE news_publisher_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE news_publisher_permission_history_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE news_article_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE news_topic_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE news_digest_seq START WITH 1 INCREMENT BY 50;

-- 매체와 공식 RSS 채널, 현재 공개 허가 상태.
-- registered_domain은 공식 채널 판정 기준이다. rss_url의 host가 이 도메인 또는 하위 도메인이어야 한다 (spec FR-009).
-- rss_url은 서버 전용이며 응답·로그·지표에 노출하지 않는다 (spec FR-021).
CREATE TABLE news_publisher (
    id                    BIGINT                   NOT NULL PRIMARY KEY,
    publisher_key         VARCHAR(64)              NOT NULL,
    display_name          VARCHAR(200)             NOT NULL,
    language              VARCHAR(16)              NOT NULL,
    registered_domain     VARCHAR(253)             NOT NULL,
    rss_url               TEXT,
    permission_status     VARCHAR(16)              NOT NULL,
    permission_updated_at TIMESTAMP WITH TIME ZONE,
    enabled               BOOLEAN                  NOT NULL DEFAULT TRUE,
    etag                  VARCHAR(512),
    last_modified         VARCHAR(512),
    created_at            TIMESTAMP                NOT NULL,
    updated_at            TIMESTAMP                NOT NULL,
    CONSTRAINT uk_news_publisher_key UNIQUE (publisher_key),
    CONSTRAINT ck_news_publisher_permission_status
        CHECK (permission_status IN ('PENDING', 'ALLOWED', 'DENIED'))
);

-- 공개 조회는 ALLOWED 매체만 사용한다. 부분 인덱스로 그 경로만 좁힌다.
CREATE INDEX idx_news_publisher_allowed
    ON news_publisher (id) WHERE permission_status = 'ALLOWED';

-- 매체 허가 요청과 회신. append-only이며 갱신·삭제하지 않는다 (spec FR-015).
-- allowed_fields는 회신에서 허용된 필드 집합이다. 내보내기 투영이 이 값을 쓴다 (spec FR-017).
CREATE TABLE news_publisher_permission_history (
    id                BIGINT                   NOT NULL PRIMARY KEY,
    publisher_id      BIGINT                   NOT NULL
                          REFERENCES news_publisher (id) ON DELETE CASCADE,
    event_type        VARCHAR(16)              NOT NULL,
    permission_status VARCHAR(16)              NOT NULL,
    allowed_fields    TEXT[]                   NOT NULL DEFAULT '{}',
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    reference         TEXT,
    created_at        TIMESTAMP                NOT NULL,
    updated_at        TIMESTAMP                NOT NULL,
    CONSTRAINT ck_news_permission_history_event
        CHECK (event_type IN ('REQUESTED', 'RESPONDED')),
    CONSTRAINT ck_news_permission_history_status
        CHECK (permission_status IN ('PENDING', 'ALLOWED', 'DENIED')),
    -- 요청은 PENDING, 회신은 ALLOWED 또는 DENIED다.
    CONSTRAINT ck_news_permission_history_event_status CHECK (
        (event_type = 'REQUESTED' AND permission_status = 'PENDING')
        OR (event_type = 'RESPONDED' AND permission_status IN ('ALLOWED', 'DENIED'))
    ),
    CONSTRAINT ck_news_permission_history_allowed_fields CHECK (
        allowed_fields <@ ARRAY['TITLE', 'PUBLISHER', 'PUBLISHED_AT', 'ORIGINAL_URL']::TEXT[]
    )
);

CREATE INDEX idx_news_permission_history_publisher
    ON news_publisher_permission_history (publisher_id, occurred_at, id);

-- 기사 메타데이터. 본문 컬럼은 없다.
-- article_id는 canonical_url에서 결정론적으로 유도한 32자 안정 식별자다. 내부 PK를 노출하지 않는다 (spec FR-025).
-- published_at은 VERIFIED일 때만 값이 있고 UNKNOWN이면 NULL이다 (spec FR-012).
-- gdelt_recorded_at은 발행 시각 대체로 쓰지 않는 별도 값이다.
CREATE TABLE news_article (
    id                  BIGINT                   NOT NULL PRIMARY KEY,
    article_id          VARCHAR(40)              NOT NULL,
    source_type         VARCHAR(16)              NOT NULL,
    publisher_id        BIGINT                   NOT NULL
                            REFERENCES news_publisher (id) ON DELETE CASCADE,
    source_item_id      VARCHAR(512),
    original_url        TEXT                     NOT NULL,
    canonical_url       TEXT                     NOT NULL,
    title               VARCHAR(1000)            NOT NULL,
    language            VARCHAR(16)              NOT NULL,
    published_at        TIMESTAMP WITH TIME ZONE,
    published_at_status VARCHAR(16)              NOT NULL,
    collected_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    gdelt_recorded_at   TIMESTAMP WITH TIME ZONE,
    duplicate_key       VARCHAR(64)              NOT NULL,
    source_payload_hash VARCHAR(64)              NOT NULL,
    ingest_run_id       VARCHAR(40)              NOT NULL,
    display_eligible    BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP                NOT NULL,
    updated_at          TIMESTAMP                NOT NULL,
    CONSTRAINT uk_news_article_article_id UNIQUE (article_id),
    CONSTRAINT uk_news_article_canonical_url UNIQUE (canonical_url),
    CONSTRAINT ck_news_article_source_type
        CHECK (source_type IN ('RSS', 'GDELT_DOC', 'GDELT_FILE')),
    CONSTRAINT ck_news_article_published_at_status
        CHECK (published_at_status IN ('VERIFIED', 'UNKNOWN')),
    CONSTRAINT ck_news_article_title_not_blank CHECK (LENGTH(BTRIM(title)) > 0),
    -- VERIFIED면 발행 시각이 있어야 하고 UNKNOWN이면 없어야 한다. 조용한 시각 추정을 막는다.
    CONSTRAINT ck_news_article_published_at_consistency CHECK (
        (published_at_status = 'VERIFIED' AND published_at IS NOT NULL)
        OR (published_at_status = 'UNKNOWN' AND published_at IS NULL)
    )
);

-- 같은 GUID라도 매체가 다르면 별개 기사다 (spec FR-011).
CREATE UNIQUE INDEX uk_news_article_publisher_source_item
    ON news_article (publisher_id, source_item_id) WHERE source_item_id IS NOT NULL;

CREATE INDEX idx_news_article_published_at ON news_article (published_at, id);
CREATE INDEX idx_news_article_collected_at ON news_article (collected_at, id);
CREATE INDEX idx_news_article_duplicate_key ON news_article (duplicate_key);

-- 등록된 경제 주제. 뉴스원의 원문 분류 값은 저장하지 않고 등록표 매핑을 통과한 주제 키만 남긴다 (spec FR-010).
CREATE TABLE news_topic (
    id            BIGINT       NOT NULL PRIMARY KEY,
    topic_key     VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(200) NOT NULL,
    domestic_link BOOLEAN      NOT NULL DEFAULT FALSE,
    rule_version  VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uk_news_topic_key UNIQUE (topic_key)
);

CREATE TABLE news_article_topic (
    article_id         BIGINT      NOT NULL REFERENCES news_article (id) ON DELETE CASCADE,
    topic_id           BIGINT      NOT NULL REFERENCES news_topic (id) ON DELETE CASCADE,
    rule_version       VARCHAR(64) NOT NULL,
    match_evidence_key VARCHAR(128),
    PRIMARY KEY (article_id, topic_id, rule_version)
);

CREATE INDEX idx_news_article_topic_topic ON news_article_topic (topic_id, article_id);

-- 기사와 섹터의 연결. sector_id는 spec 001이 소유하는 외부 식별자이며 FK를 걸지 않는다 (spec FR-005).
CREATE TABLE news_article_sector (
    article_id   BIGINT      NOT NULL REFERENCES news_article (id) ON DELETE CASCADE,
    sector_id    VARCHAR(64) NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    PRIMARY KEY (article_id, sector_id, rule_version)
);

CREATE INDEX idx_news_article_sector_sector ON news_article_sector (sector_id, article_id);

-- 같은 사건의 표시 묶음.
-- 여기 저장한 대표·요약·매체 수는 전체 멤버 기준 값이다. 공개 응답은 허가된 기사만으로 조회 시점에
-- 다시 계산한 값을 쓰며 이 행을 허가 변경으로 덮어쓰지 않는다 (spec FR-016, CHK035·036).
CREATE TABLE news_digest (
    id                        BIGINT                   NOT NULL PRIMARY KEY,
    digest_id                 VARCHAR(40)              NOT NULL,
    representative_article_id BIGINT                   NOT NULL
                                  REFERENCES news_article (id) ON DELETE CASCADE,
    representative_title      VARCHAR(1000)            NOT NULL,
    factual_summary           VARCHAR(1200)            NOT NULL,
    first_published_at        TIMESTAMP WITH TIME ZONE,
    latest_published_at       TIMESTAMP WITH TIME ZONE,
    distinct_publisher_count  INTEGER                  NOT NULL,
    grouping_rule_version     VARCHAR(64)              NOT NULL,
    importance_rule_version   VARCHAR(64)              NOT NULL,
    created_at                TIMESTAMP                NOT NULL,
    updated_at                TIMESTAMP                NOT NULL,
    CONSTRAINT uk_news_digest_digest_id UNIQUE (digest_id),
    CONSTRAINT ck_news_digest_publisher_count CHECK (distinct_publisher_count >= 1)
);

CREATE INDEX idx_news_digest_latest_published ON news_digest (latest_published_at, digest_id);

-- 묶음 멤버십. 묶음 근거로 시간 차·제목 유사도·공통 주제를 남기고 Tone과 본문 근거는 남기지 않는다.
CREATE TABLE news_digest_article (
    digest_id         BIGINT           NOT NULL REFERENCES news_digest (id) ON DELETE CASCADE,
    article_id        BIGINT           NOT NULL REFERENCES news_article (id) ON DELETE CASCADE,
    time_gap_seconds  BIGINT,
    title_similarity  DOUBLE PRECISION,
    shared_topic_key  VARCHAR(64),
    PRIMARY KEY (digest_id, article_id)
);

CREATE INDEX idx_news_digest_article_article ON news_digest_article (article_id);

-- 규칙 버전. 규칙 변경은 기존 결과를 덮어쓰지 않고 새 버전으로 식별한다 (원칙 VIII).
CREATE TABLE news_rule_version (
    rule_version   VARCHAR(64)              NOT NULL PRIMARY KEY,
    rule_type      VARCHAR(32)              NOT NULL,
    definition     JSONB                    NOT NULL,
    effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
    retired_at     TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP                NOT NULL,
    updated_at     TIMESTAMP                NOT NULL,
    CONSTRAINT ck_news_rule_version_type
        CHECK (rule_type IN ('URL_NORMALIZATION', 'LINK', 'GROUPING', 'IMPORTANCE'))
);

-- 뉴스원별 실행 상태와 cursor, 그리고 인스턴스 간 점유(lease).
-- source_key는 수집 뉴스원뿐 아니라 일일 정리 작업('retention-cleanup')도 포함한다 (spec FR-022).
-- INCOMPLETE는 GDELT 결과 상한 때문에 요청 구간을 다 받지 못한 상태다. 이때 cursor를 전진시키지
-- 않고 last_success_at도 갱신하지 않아 다음 회차가 같은 구간을 재시도한다 (spec FR-013).
-- error_category는 가림 정책을 통과한 분류 코드만 담는다. URL·secret·응답 원문을 담지 않는다 (spec FR-021).
CREATE TABLE news_collection_state (
    source_key      VARCHAR(128)             NOT NULL PRIMARY KEY,
    status          VARCHAR(16)              NOT NULL,
    cursor_value    TEXT,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    last_success_at TIMESTAMP WITH TIME ZONE,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    retry_count     INTEGER                  NOT NULL DEFAULT 0,
    error_category  VARCHAR(64),
    lease_owner     VARCHAR(128),
    lease_until     TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP                NOT NULL,
    updated_at      TIMESTAMP                NOT NULL,
    CONSTRAINT ck_news_collection_state_status
        CHECK (status IN ('IDLE', 'RUNNING', 'SUCCEEDED', 'INCOMPLETE', 'FAILED', 'RATE_LIMITED')),
    CONSTRAINT ck_news_collection_state_retry_count CHECK (retry_count >= 0)
);

-- due source를 고르는 경로. 점유되지 않았고 다음 시도 시각이 지난 행을 찾는다.
CREATE INDEX idx_news_collection_state_due ON news_collection_state (next_attempt_at, lease_until);

-- 규칙 버전 seed. 값의 근거는 specs/012-market-news-digest/plan.md의 규칙 v1이다.
INSERT INTO news_rule_version
    (rule_version, rule_type, definition, effective_from, created_at, updated_at)
VALUES
    ('news-url-normalization-v1', 'URL_NORMALIZATION',
     '{"lowercaseSchemeHost": true, "removeDefaultPort": true, "removeFragment": true,
       "emptyPathToSlash": true, "removeTrailingSlashExceptRoot": true, "sortQuery": true,
       "trackingParameters": ["utm_source", "utm_medium", "utm_campaign", "utm_term",
                              "utm_content", "gclid", "fbclid"],
       "preserveOtherQueryValues": true, "preservePathCase": true, "maxRedirects": 3}'::JSONB,
     TIMESTAMP WITH TIME ZONE '2026-09-10T00:00:00Z', NOW(), NOW()),
    ('news-link-v1', 'LINK',
     '{"registryFile": "news/news-link-v1.json", "trustSourceCategoryDirectly": false,
       "sectorIdOwner": "spec-001", "overseasRequiresDomesticTopic": true}'::JSONB,
     TIMESTAMP WITH TIME ZONE '2026-09-10T00:00:00Z', NOW(), NOW()),
    ('news-grouping-v1', 'GROUPING',
     '{"maxTimeGapHours": 48, "minSharedTopics": 1, "titleJaccardThreshold": 0.60,
       "linkage": "complete", "mergeOnUncertain": false, "transitiveBridge": false,
       "titleNormalization": ["NFKC", "lowercase", "stripPunctuation", "collapseWhitespace"]}'::JSONB,
     TIMESTAMP WITH TIME ZONE '2026-09-10T00:00:00Z', NOW(), NOW()),
    ('news-importance-v1', 'IMPORTANCE',
     '{"order": ["distinctPublisherCountDesc", "hasVerifiedPublishedAtDesc",
                 "latestVerifiedPublishedAtDesc", "collectedAtDesc", "digestIdAsc"],
       "usesTone": false, "permissionFilterFirst": true, "maxDigests": 3}'::JSONB,
     TIMESTAMP WITH TIME ZONE '2026-09-10T00:00:00Z', NOW(), NOW());

-- 정리 작업도 수집 뉴스원과 같은 점유 규칙을 쓴다. 다중 인스턴스에서 하나만 실행된다.
INSERT INTO news_collection_state (source_key, status, retry_count, created_at, updated_at)
VALUES ('retention-cleanup', 'IDLE', 0, NOW(), NOW());
