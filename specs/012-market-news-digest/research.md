# Phase 0 Research: 시장 흐름 뉴스 묶음

## Decision 1: 운영 소유자는 backend의 독립 news 도메인

**Decision**: 실시간 수집, 저장, 묶음 조회, 공개 필터, 보관 삭제를 `backend/`의 `com.ssafy.thorp.news`가 소유한다.

**Rationale**: backend는 현재 PostgreSQL, Flyway, 외부 HTTP, 조회 API를 소유한다. `worker/`는 RabbitMQ 백테스트 consumer이고 PostgreSQL에 쓰지 않으며 여러 replica가 실행된다. `data/`는 배포되지 않는 KRX/Parquet 오프라인 파이프라인이다.

**Alternatives considered**:
- `worker/`: DB 비접근 경계와 백테스트 전용 책임을 깨고 replica별 중복 스케줄을 만든다.
- `data/`: 수동 backfill에는 맞지만 운영 배포·DB 저장 경로가 없다.
- 신규 서비스: 운영 분리는 좋지만 `infra/` 변경과 별도 배포가 필요해 현재 범위와 3주 일정에 맞지 않는다.

## Decision 2: RSS 2.0을 주 기사 메타데이터 원천으로 사용

**Decision**: publisher RSS의 `title`, `link`, `pubDate`, `guid`, `category`, channel/source 이름만 사용한다. `description`은 매체마다 요약 또는 본문 조각일 수 있으므로 v1에서는 저장하거나 요약 근거로 사용하지 않는다.

**Rationale**: RSS 2.0은 item 필드가 선택적이므로 누락을 정상 처리해야 한다. `guid`는 문법과 URL 여부가 보장되지 않으므로 feed 내부 opaque ID로 취급한다. `pubDate`가 없거나 파싱되지 않으면 수집 시각으로 대체하지 않고 발행 시각 미확인으로 보존한다. description을 제외하면 본문 조각이 영속화·요약 경로로 유입되는 것을 계약 수준에서 차단할 수 있다. [RSS 2.0 Specification](https://www.rssboard.org/rss-specification)

**Alternatives considered**:
- 기사 HTML 크롤링: 본문 저장·요약 금지와 매체 이용 조건에 어긋난다.
- Atom/JSON Feed 동시 지원: 확장 가능하지만 MVP 범위를 늘리므로 후속 adapter로 둔다.

## Decision 3: GDELT는 보완 발견원으로 제한

**Decision**: 가까운 시점은 GDELT DOC 2.0 ArticleList를 기간·주제 필터로 조회하고, spec이 요구하는 15분 배포 파일은 필요한 구간 보완에만 순차 처리한다. Event/Mentions의 기록 시각은 발행 시각으로 사용하지 않는다.

**Rationale**: DOC API는 기사 목록 검색에 맞고, Event/Mentions는 추출 사건과 언급 흐름 데이터다. GDELT 2.0 배포는 15분 주기로 제공되지만 Event `DATEADDED`, Mention 시각은 publisher 발행 시각이 아니다. [GDELT DOC 2.0](https://blog.gdeltproject.org/gdelt-doc-2-0-api-debuts/), [GDELT data](https://gdeltproject.org/data.html), [Event 2.0 Codebook](https://data.gdeltproject.org/documentation/GDELT-Event_Codebook-V2.0.pdf)

**Alternatives considered**:
- GDELT Event를 대표 기사 원천으로 사용: 발행 시각과 기사 메타데이터 의미가 맞지 않는다.
- BigQuery 전체 이력: 비용·운영 의존성이 현재 규모에 비해 크다.
- Web NGrams 전체 수집: 고용량 분석용이며 뉴스 묶음 MVP에 과하다.

## Decision 4: 조건부 요청과 중앙화된 속도 제한

**Decision**: RSS는 ETag/Last-Modified 조건부 GET을 사용하고, GDELT는 15분보다 빠르게 polling하지 않는다. 429/503의 `Retry-After`를 우선하며 없을 때만 bounded exponential backoff와 jitter를 적용한다.

**Rationale**: 불필요한 원천 부하를 줄이고 고정 수치가 공개되지 않은 API rate limit을 임의로 가정하지 않는다. HTTP 조건부 요청과 `Retry-After` 의미는 [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html)을 따른다. GDELT는 API rate limiting과 고용량 대안을 별도로 안내한다. [GDELT rate limiting notice](https://blog.gdeltproject.org/ukraine-api-rate-limiting-web-ngrams-3-0/)

**Alternatives considered**:
- 실패 시 즉시 무한 재시도: 원천과 서비스 양쪽 장애를 증폭한다.
- 고정 sleep만 사용: `Retry-After`와 소스별 회복 시점을 무시한다.

## Decision 5: 정규화 관계형 모델과 명시적 규칙 버전

**Decision**: publisher, article, digest, membership, sector/topic link, collection state, rule version을 별도 테이블로 둔다. 조회·필터·삭제에 쓰는 값은 컬럼과 join table로 저장한다.

**Rationale**: 공개 허가, 과거 기간, 섹터, 삭제 cascade는 핵심 질의 조건이다. JSONB 한 덩어리보다 FK/인덱스/unique 제약으로 불변식을 검증하기 쉽다.

**Alternatives considered**:
- 기사 전체 JSONB: 빠른 초기 적재는 가능하지만 허가 필터와 기간/섹터 질의, cascade 검증이 불명확하다.
- 검색 엔진 도입: 100,000건 이하 MVP와 보수적 제목 기반 규칙에 불필요하다.

## Decision 6: 보수적이고 결정론적인 묶음·정렬

**Decision**: canonical URL 중복을 먼저 제거하고, 사건 묶음은 48시간·공통 주제·제목 token Jaccard 0.60 조건을 모두 만족할 때만 수행한다. 중요도는 허가 필터 후 서로 다른 매체 수, 최근성, 안정 ID 순의 tuple 정렬이다.

**Rationale**: spec은 불확실한 기사를 합치지 말라고 요구한다. 결정론적 규칙과 버전은 같은 입력의 재현을 가능하게 하고 Tone이나 투자 판단을 배제한다.

**Alternatives considered**:
- embedding/LLM clustering: 외부 모델 의존, 비용, 비결정성, 설명 가능성 문제가 있다.
- 제목 유사도만 사용: 서로 다른 시점·주제의 유사 제목을 잘못 합칠 위험이 크다.

## Decision 7: 화면 상태는 API 계약으로만 제공

**Decision**: 기존 `HttpApiResponse<T>` envelope 안에 뉴스 전용 `READY|EMPTY|STALE|PREPARING` 상태, 마지막 갱신 시각과 최대 3개 묶음을 반환한다. `frontend/`는 변경하지 않는다.

**Rationale**: 기존 프론트는 `/api` proxy와 camelCase envelope를 사용한다. 사용자가 UI 구현을 제외했으므로 OpenAPI와 예시 JSON이 프론트 후속 작업의 유일한 전달 계약이다.

**Alternatives considered**:
- 외부 원천 실패를 503으로 반환: 빈 뉴스가 시장 흐름 오류로 번지며 spec FR-014와 충돌한다.
- 내부 모드를 query parameter로 제공: 공개 사용자가 미허가 데이터를 요청할 수 있다.

## Decision 8: 실행 환경이 남아 있을 때 수행하는 단순 일괄 cleanup

**Decision**: 실행 환경이 `2026-11-07`까지 유지될 때 daily cleanup이 뉴스 도메인 데이터를 일괄 삭제한다. 장기 감사 테이블은 두지 않고 재실행의 삭제 대상 0건을 정상 처리한다. EC2와 저장소가 먼저 회수되면 별도 cleanup을 요구하지 않는다.

**Rationale**: 프로젝트 종료 후 EC2가 회수되는 운영 조건에서 장기 보존·감사 구조는 과도하다. 환경이 남은 경우에만 DB cascade와 반복 가능한 삭제로 SC-007을 검증한다.

**Alternatives considered**:
- 수동 삭제만 사용: 실행 누락을 자동 검출할 수 없다.
- 무기한 보관: clarify에서 확정한 정책과 Constitution에 어긋난다.

## Decision 9: 허가 기반 JSON 내보내기

**Decision**: 012가 기간·섹터·cursor를 받는 기사 메타데이터 JSON 내보내기 API를 제공한다. 매체별 최신 허가 상태와 허용 필드를 먼저 적용하고 페이지당 최대 500개를 반환한다.

**Rationale**: 내보내기 책임을 012에 두기로 확정했으며, JSON 계약은 `frontend/` 변경 없이 006이 재사용할 수 있다. 제한된 page와 안정 cursor는 최대 100,000개 범위에서 무제한 응답을 피한다.

**Alternatives considered**:
- 006이 직접 뉴스 저장소를 조회: bounded context와 허가 필터가 중복된다.
- CSV 파일 생성: 별도 파일 보관·다운로드 수명주기가 필요해 현재 범위를 늘린다.

## Decision 10: 현재 상태와 append-only 허가 이력 분리

**Decision**: publisher에는 최신 허가 상태를 두고 요청·회신과 허용 필드는 별도 이력에 append-only로 저장한다.

**Rationale**: 공개 질의는 단순한 현재 상태 필터로 유지하면서 여러 차례의 요청·회신 근거를 잃지 않는다.

**Alternatives considered**:
- publisher 한 행에 최신 회신만 저장: 이전 회신 근거가 사라진다.
- 이력만 저장하고 매번 최신 상태 계산: 모든 공개 조회 비용과 구현 복잡도가 증가한다.

## Resolved Unknowns

- 구현 소유권: backend
- 외부 형식: RSS 2.0 + GDELT DOC/필요 구간 배포 파일
- 영속화: PostgreSQL 관계형 모델 + Flyway V3
- 현재/과거 API: 하나의 resource 아래 query 조합으로 구분
- stale 기준: 기본 30분, 서버 설정 가능
- 과거 조회 상한: 93일
- 초기 처리 규모: RSS 20개 이하, 메타데이터 100,000개 이하
- frontend 전달: OpenAPI + JSON examples only

미해결 기술 항목은 남아 있지 않다. PD-15는 외부 회신 전 PUBLIC 상태를 PREPARING으로 제한하는 기존 spec의 안전한 기본값을 따른다.
