# Data Model: 시장 흐름 뉴스 묶음

## 공통 원칙

- 모든 외부 시각은 UTC `Instant`/`TIMESTAMP WITH TIME ZONE`으로 저장한다.
- 기사 본문, HTML 원문, 이미지, GDELT Tone 필드를 만들지 않는다.
- 외부 노출 ID는 안정된 문자열 ID를 사용하고 DB PK는 노출하지 않는다.
- 외부 공개 허가, 기간, 섹터, 중복 제거에 쓰는 값은 JSONB가 아니라 컬럼·관계로 둔다.
- 규칙 변경은 기존 결과를 덮어쓰지 않고 새 버전으로 식별한다.

## Entity: NewsPublisher

매체와 공식 RSS 채널, 공개 허가를 관리한다.

| Field | Type | Rules |
|---|---|---|
| id | bigint | PK, 외부 미노출 |
| publisherKey | varchar(64) | UNIQUE, 영문 소문자/하이픈 |
| displayName | varchar(200) | NOT NULL |
| language | varchar(16) | BCP 47 형식 |
| rssUrl | text | nullable, server-only |
| registeredDomain | varchar(253) | NOT NULL, 공식 채널 판정 기준. `rssUrl`의 host가 이 도메인 또는 하위 도메인이어야 한다 |
| permissionStatus | enum | `PENDING`, `ALLOWED`, `DENIED` |
| permissionUpdatedAt | timestamptz | nullable |
| enabled | boolean | 수집 활성화 여부 |
| etag | varchar(512) | nullable, 조건부 GET |
| lastModified | varchar(512) | nullable, 조건부 GET |

상태 전이: `PENDING → ALLOWED | DENIED`; 재확인이 필요하면 현재 상태를 PENDING으로 바꾸고 새 이력을 추가한다. PUBLIC 조회는 ALLOWED만 사용한다.

## Entity: NewsPublisherPermissionHistory

매체 허가 요청과 회신을 append-only로 기록한다.

| Field | Type | Rules |
|---|---|---|
| id | bigint | PK |
| publisherId | bigint | FK → NewsPublisher, ON DELETE CASCADE |
| eventType | enum | `REQUESTED`, `RESPONDED` |
| permissionStatus | enum | 요청은 `PENDING`, 회신은 `ALLOWED` 또는 `DENIED` |
| allowedFields | varchar array | `TITLE`, `PUBLISHER`, `PUBLISHED_AT`, `ORIGINAL_URL`의 부분집합 |
| occurredAt | timestamptz | NOT NULL |
| reference | text | 회신 위치/식별자, 기사 본문 아님 |

같은 매체의 이력은 `(publisherId, occurredAt, id)` 순으로 조회한다. 이력 갱신과 `NewsPublisher` 현재 상태 갱신은 한 트랜잭션에서 수행한다.

## Entity: NewsArticle

RSS 또는 GDELT에서 발견한 기사 메타데이터다.

| Field | Type | Rules |
|---|---|---|
| id | bigint | PK |
| articleId | varchar(40) | UNIQUE, 외부용 안정 ID |
| sourceType | enum | `RSS`, `GDELT_DOC`, `GDELT_FILE` |
| publisherId | bigint | FK → NewsPublisher |
| sourceItemId | varchar(512) | nullable; feed 내 guid 등 |
| originalUrl | text | NOT NULL |
| canonicalUrl | text | NOT NULL, UNIQUE |
| title | varchar(1000) | NOT NULL, 빈 문자열 금지 |
| language | varchar(16) | NOT NULL |
| publishedAt | timestamptz | nullable |
| publishedAtStatus | enum | `VERIFIED`, `UNKNOWN` |
| collectedAt | timestamptz | NOT NULL |
| gdeltRecordedAt | timestamptz | nullable, publishedAt 대체 금지 |
| duplicateKey | varchar(64) | NOT NULL, indexed |
| sourcePayloadHash | varchar(64) | NOT NULL |
| ingestRunId | varchar(40) | NOT NULL |
| displayEligible | boolean | 신뢰 가능한 요약 근거·필수 필드 충족 여부 |

불변식:

- `publishedAtStatus=VERIFIED`이면 timezone이 확인되고 `publishedAt <= collectedAt + 5분`인 `publishedAt`이 필수다.
- UNKNOWN이면 과거 조회에서 제외한다.
- timezone 누락·파싱 실패·5분 초과 미래 시각은 UNKNOWN과 null `publishedAt`으로 저장한다.
- canonical URL 중복 insert는 기존 항목을 갱신하되 최초 collectedAt과 articleId는 유지한다.
- `articleId`는 canonical URL의 SHA-256을 base32url로 인코딩해 앞 32자를 쓴다. 같은 canonical URL은 재수집해도 같은 값이 나온다.
- `digestId`는 `대표 articleId + '|' + groupingRuleVersion`의 SHA-256을 같은 방식으로 인코딩한 32자다.
- `ingestRunId`는 회차마다 새로 발급하는 UUIDv7이며 사업 키에서 유도하지 않는다.
- ID 생성에 난수나 DB 시퀀스를 쓰지 않으며 내부 PK를 인코딩해 노출하지 않는다.
- 서로 다른 사업 키가 같은 ID를 만드는 충돌은 UNIQUE 제약 위반으로 감지한다. 이때 기존 행을 덮어쓰지 않고 새 항목을 수집에서 제외한 뒤 `errorCategory=ID_COLLISION`으로 기록한다.
- body/content/fullText/html 필드는 존재하지 않는다.

## Entity: NewsTopic / NewsArticleTopic

등록된 경제 주제와 기사 연결이다. `topicKey`, 한국어 표시명, 국내 연결 여부를 갖는다. 연결 행에는 ruleVersion과 matchEvidenceKey를 기록하되 기사 본문 조각은 저장하지 않는다. 뉴스원의 원문 category 문자열은 저장하지 않고 `news-link-v1`에 등록된 매핑을 통과한 `topicKey`만 남긴다. 매핑되지 않은 category는 연결 근거로 쓰지 않고 미등록 건수 지표로만 집계한다.

## Entity: NewsArticleSector

기사와 001의 sector ID 사이 N:M 연결이다. `(articleId, sectorId, ruleVersion)`이 유일하다. 해외 기사는 국내 연결 주제 또는 sector 연결이 없으면 저장 후보에서 제외한다. `sectorId`는 001이 소유하는 외부 식별자이며 012는 sector 마스터 테이블을 만들지 않고 값을 정의·생성하지도 않는다. `news-link-v1.json`은 sector 목록을 선언하지 않고 주제 키와 외부 sector 식별자의 연결만 담는다. 등록되지 않은 `sectorId`로 들어온 조회는 400으로 거부하고, 001 대응표가 비어 있으면 sector 지정 조회는 정상 `EMPTY`를 반환한다.

## Entity: NewsDigest

같은 사건의 표시 묶음이다.

| Field | Type | Rules |
|---|---|---|
| id | bigint | PK |
| digestId | varchar(40) | UNIQUE, 외부 노출 |
| representativeArticleId | bigint | FK → NewsArticle |
| representativeTitle | varchar(1000) | NOT NULL |
| factualSummary | varchar(1200) | NOT NULL, 한 문장 |
| firstPublishedAt | timestamptz | nullable |
| latestPublishedAt | timestamptz | nullable |
| distinctPublisherCount | integer | 1 이상 |
| groupingRuleVersion | varchar(64) | NOT NULL |
| importanceRuleVersion | varchar(64) | NOT NULL |
| createdAt | timestamptz | NOT NULL |
| updatedAt | timestamptz | NOT NULL |

요약을 신뢰할 수 없으면 digest를 공개/내부 표시 후보로 만들지 않는다. 저장된 `representativeTitle`과 `factualSummary`는 전체 멤버 기준 값이며 PUBLIC 응답에는 허가 기사만으로 다시 계산한 값을 쓴다.

## Entity: NewsDigestArticle

NewsDigest와 NewsArticle의 N:M membership이다. `(digestId, articleId)`가 유일하다. grouping 근거로 시간 차, title similarity, 공통 topic key를 저장한다. Tone과 본문 근거는 저장하지 않는다.

## Entity: NewsRuleVersion

| Field | Type | Rules |
|---|---|---|
| ruleVersion | varchar(64) | PK (`news-url-normalization-v1`, `news-grouping-v1`, `news-importance-v1`, `news-link-v1`) |
| ruleType | enum | `URL_NORMALIZATION`, `LINK`, `GROUPING`, `IMPORTANCE` |
| definition | jsonb | 임계값·정렬 tuple 등 재현 정보 |
| effectiveFrom | timestamptz | NOT NULL |
| retiredAt | timestamptz | nullable |

## Entity: NewsCollectionState

source/publisher별 실행 상태와 cursor를 보존한다.

| Field | Type | Rules |
|---|---|---|
| sourceKey | varchar(128) | PK |
| status | enum | `IDLE`, `RUNNING`, `SUCCEEDED`, `INCOMPLETE`, `FAILED`, `RATE_LIMITED` |
| cursor | text | nullable; GDELT watermark/file name 등 |
| lastAttemptAt | timestamptz | nullable |
| lastSuccessAt | timestamptz | nullable |
| nextAttemptAt | timestamptz | nullable |
| retryCount | integer | 0 이상 |
| errorCategory | varchar(64) | nullable, sanitized category only |
| leaseOwner | varchar(128) | nullable |
| leaseUntil | timestamptz | nullable |

`sourceKey`는 수집 소스뿐 아니라 일일 정리 작업(`retention-cleanup`)도 포함해 같은 lease 규칙을 적용한다. lease는 조건부 UPDATE로 획득하고 TTL 15분이 지나면 다른 인스턴스가 회수한다.

상태 전이: due source claim → RUNNING → SUCCEEDED 또는 INCOMPLETE/FAILED/RATE_LIMITED → 다음 due 시각. cursor는 batch transaction 성공 뒤에만 전진한다. RSS `304 Not Modified`는 SUCCEEDED로 전이해 `lastSuccessAt`만 갱신하고 cursor와 기사 데이터는 유지한다.

`INCOMPLETE`는 GDELT 조회가 결과 상한에 도달해 요청 구간의 기사를 모두 받지 못했고 15분 최소 구간까지 분할한 뒤에도 상한이 유지된 경우다. 이때 받은 기사는 그대로 저장하되 `cursor`를 그 구간 너머로 전진시키지 않아 다음 회차가 같은 구간을 재시도한다. `lastSuccessAt`은 갱신하지 않으며 `errorCategory`에 `RESULT_CAP_REACHED`를 기록한다. 부분 수집 구간을 완전 수집으로 기록하지 않는다.

## Retention Cleanup

실행 환경이 유지되면 `2026-11-07T00:00:00+09:00` 이후 뉴스 도메인의 article, digest, link, collection state, permission history, publisher, rule version을 한 트랜잭션의 명시적 순서와 FK cascade로 삭제한다. 매체 등록, 허가 이력, 수집 상태, 규칙 버전도 예외 없이 삭제 대상이며 감사 목적으로 남기는 테이블은 없다. 별도 장기 감사 엔티티는 두지 않으며 반복 실행에서 삭제 대상 0건은 정상이다. 검증은 뉴스 도메인 모든 테이블의 행 수가 0인지로 한다.

## Relationships and Query Order

```text
NewsPublisher 1 ── N NewsPublisherPermissionHistory
      │
      └─────────── N NewsArticle N ── N NewsDigest
                         │  ├── N NewsArticleSector
                         │  └── N NewsArticleTopic
NewsRuleVersion ─────────┴──── grouping/link/importance provenance
NewsCollectionState ────────── source execution state
```

PUBLIC 현재 조회 순서:

1. `NewsPublisher.permissionStatus=ALLOWED`
2. 표시 가능 기사와 현재 기간 선택
3. 선택 sector/topic 필터
4. 허가된 기사만으로 묶음의 대표 기사·대표 제목·사실 요약·매체 수·최근성 재계산. 대표 기사가 미허가면 남은 허가 기사에 같은 대표 선정 규칙(검증 발행 시각 최초 → canonical URL 오름차순)을 다시 적용한다
5. 허가 기사가 없거나 남은 기사로 요약 조건을 만족하지 못하는 묶음 제외
6. importance tuple 정렬 후 최대 3건

`NewsDigest`에 저장된 `representativeArticleId`, `representativeTitle`, `factualSummary`, `distinctPublisherCount`는 전체 멤버 기준의 값이며 허가 상태 변경 시 갱신하지 않는다. PUBLIC 응답은 위 순서로 조회 시점에 계산한 값만 사용한다.

과거 조회는 위 조건에 `publishedAtStatus=VERIFIED`와 `[from,to]`를 추가한다.

## Required Indexes and Constraints

- UNIQUE `news_article(canonical_url)`
- UNIQUE `news_article(publisher_id, source_item_id)` WHERE source_item_id IS NOT NULL
- INDEX `news_article(published_at, id)`
- INDEX `news_article(collected_at, id)`
- INDEX `news_article(duplicate_key)`
- INDEX `news_article_sector(sector_id, article_id)`
- INDEX `news_article_topic(topic_id, article_id)`
- INDEX `news_digest(latest_published_at, digest_id)`
- INDEX `news_publisher_permission_history(publisher_id, occurred_at, id)`
- FK dependent links `ON DELETE CASCADE`
