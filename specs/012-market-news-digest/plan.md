# Implementation Plan: 시장 흐름 뉴스 묶음

**Branch**: `012-market-news-digest` (spec 식별자; 실제 구현은 작업 묶음별 Jira Task·브랜치·MR) | **Date**: 2026-09-10 | **Spec**: [spec.md](spec.md)

**Input**: `specs/012-market-news-digest/spec.md`와 사용자 지정 범위(`backend/`, `worker/`, `data/`, `docs/`; `frontend/` 읽기 전용)

## Summary

국내 경제 뉴스 RSS를 주 수집원, GDELT를 해외 사건·보도 보완원으로 사용해 기사 본문 없이 메타데이터만 수집한다. URL 중복 제거, 섹터·주제 연결, 보수적인 사건 묶음, 보도 수·최근성 기반 정렬을 적용하고 PostgreSQL에 규칙 버전과 수집 상태를 함께 저장한다. 운영 수집·조회·내보내기·보관 책임은 기존 Spring Boot 애플리케이션의 독립 `news` 도메인이 맡는다. 공개 조회와 내보내기는 매체 허가를 먼저 필터링하며, 빈 자료·지연·허가 대기 상태도 HTTP 200의 명시적 상태로 반환한다.

화면 코드는 범위 밖이다. 기존 `frontend/`는 소비 형태 파악을 위해서만 읽었으며, 필요한 화면 상태와 필드는 [contracts/openapi.yaml](contracts/openapi.yaml) 및 `contracts/examples/`의 예시 JSON으로 전달한다. `worker/`는 백테스트 전용 경계, `data/`는 비배포 오프라인 파이프라인이므로 이번 구현에서는 변경하지 않는다.

## Technical Context

**Language/Version**: Java 21; 문서 계약은 OpenAPI 3.1 및 JSON

**Primary Dependencies**: Spring Boot 3.5.4, Spring Web, Validation, Data JPA, Actuator, Java HTTP client/XML parser, Jackson

**Storage**: PostgreSQL 16, Flyway migration `V3__market_news_digest.sql`; 기사 본문·HTML·이미지는 저장하지 않음

**Testing**: JUnit 5, Spring Boot Test, MockMvc, ArchUnit, Testcontainers PostgreSQL 16, 고정 `Clock`, RSS/GDELT 로컬 fixture

**Target Platform**: 기존 Linux 컨테이너의 단일 backend 인스턴스. 향후 다중 인스턴스에서도 중복 실행되지 않도록 DB lease 사용

**Project Type**: 모노레포 웹 서비스의 backend bounded context와 API 계약

**Performance Goals**: 100,000개 기사 fixture와 PostgreSQL 16 Testcontainers 환경에서 30회 warm-up 후 100회 측정한 뉴스 묶음 조회 p95 500ms 이하; 응답 최대 3묶음; 로컬 HTTP fixture로 RSS 20개와 GDELT 1개를 동시에 due 상태로 만든 한 회차가 15분 이내 완료

**Constraints**: `backend/`, `worker/`, `data/`, `docs/`만 구현 범위; `frontend/` 생성·수정 금지; `infra/` 변경 금지; 기사 본문 저장 및 본문 기반 요약 금지; GDELT Tone 미사용; 공개 조회·내보내기 전 매체 허가 필터; 실행 환경이 유지될 경우 2026-11-07 뉴스 데이터 일괄 삭제

**Scale/Scope**: 초기 등록 RSS 20개 이하와 GDELT 1개, 프로젝트 기간 중 최대 100,000개 기사 메타데이터, 현재 뉴스와 최대 93일 과거 구간 조회. 이 수치는 설계·성능 측정을 위한 계획 상한이며 검증된 운영 용량이 아니다. 확대는 원칙 IX에 따라 실측 후 결정한다.

## Constitution Check

*GATE: Phase 0 전 및 Phase 1 설계 후 재검사 완료.*

| 원칙 | 판정 | 설계 근거 |
|---|---|---|
| I. 전략 진단이 중심 | PASS | 뉴스는 시장 흐름의 보조 맥락이며 전략 선택 신호가 아니다. |
| II. 첫 화면 단순성 | PASS | API는 최대 3묶음과 명시적 상태만 제공하며 공급원 선택을 요구하지 않는다. |
| III. 진단이지 추천이 아님 | PASS | Tone·투자 시사점·수익 원인 문구를 계약과 모델에서 제외한다. |
| IV. 같은 조건 비교 | PASS | 뉴스는 경제환경 순위와 전략 성과 입력에 포함되지 않는다. |
| V. 당시 정보만 사용 | PASS | 과거 조회는 검증된 `publishedAt`이 구간 안인 기사만 반환한다. GDELT 기록 시각은 별도다. |
| VI. 없는 결과를 만들지 않음 | PASS | 요약 근거가 부족하면 제외하고, 빈 결과·지연은 상태로 반환한다. |
| VII. 탐색과 검증 구분 | PASS | 뉴스 묶음은 추천·성과 검증으로 표현하지 않는다. |
| VIII. 재현 가능 기록 | PASS | 정규화·연결·묶음·정렬 규칙 버전과 수집 실행 ID를 보존한다. |
| IX. 한도는 실측 | PASS | 초기 규모와 관측 지표를 두며 확대는 측정 후 결정한다. |
| X. 이용 조건 준수 | PASS | 매체 허가를 조회·순위·내보내기 전에 필터링하고 본문을 저장하지 않는다. 요청·회신은 별도 이력으로 기록한다. |
| Git·Jira 추적 단위 | PASS | 1인 당일 개발을 고려해 실행 task 51개를 독립 구현·검증·리뷰·롤백 가능한 작업 묶음 4개로 구성하고, 각 묶음을 Jira Task·브랜치·MR 하나에 대응한다. |

**Post-design re-check**: Constitution v3.0.0 기준으로 재검사했다. 데이터 모델에는 본문 필드가 없고, 공개 권한·허가 이력·발행 시각 검증·규칙 버전·종료 정리가 명시됐다. API 계약은 READY뿐 아니라 PREPARING, EMPTY, STALE과 허가 기반 JSON 내보내기를 포함한다. `tasks.md`의 작업 묶음은 보안 정책을 수집 배치에 포함하고 각 묶음의 목적과 롤백 경계를 분리해야 한다.

## Project Structure

### Documentation (this feature)

```text
specs/012-market-news-digest/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── openapi.yaml
│   └── examples/
│       ├── current-ready.json
│       ├── current-stale.json
│       ├── public-preparing.json
│       ├── historical-empty.json
│       └── export-allowed.json
└── tasks.md                 # /speckit.tasks에서 생성
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/ssafy/thorp/news/
│   ├── presentation/
│   │   ├── controller/
│   │   └── response/
│   ├── application/
│   │   ├── service/
│   │   ├── result/
│   │   ├── repository/
│   │   └── port/
│   ├── domain/
│   │   ├── model/
│   │   └── vo/
│   └── infrastructure/
│       ├── client/
│       ├── config/
│       ├── persistence/
│       └── scheduling/
├── src/main/resources/
│   └── db/migration/V3__market_news_digest.sql
└── src/test/java/com/ssafy/thorp/news/
    ├── presentation/
    ├── application/
    ├── infrastructure/client/
    └── infrastructure/persistence/

docs/
└── specs/market-news-api.md

worker/                       # 변경 없음: 백테스트 실행 전용
data/                         # 변경 없음: KRX 오프라인 파이프라인
frontend/                     # 읽기 전용, 생성·수정 금지
```

**Structure Decision**: HTTP 조회, 영속화, 스케줄 실행을 이미 소유하는 `backend/`에 `com.ssafy.thorp.news` bounded context를 추가한다. 외부 수집기는 application port 뒤에 두고 네트워크 호출은 DB 트랜잭션 밖에서 수행한다. `worker/`에 넣으면 다중 replica가 같은 수집을 중복 수행하고 DB 비접근 규칙을 위반한다. `data/`는 배포 서비스가 아니므로 운영 수집에 사용하지 않는다. 별도 서비스와 `infra/` 변경도 만들지 않는다.

## Design Decisions

### 수집과 재시도

- RSS와 GDELT 회차는 15분마다 시작하되 소스별 `nextAttemptAt`을 확인해 독립 실행한다.
- 공식 채널 판정 기준은 매체별 등록 도메인이다. registry 항목은 매체의 등록 도메인을 갖고 채널 URL의 host가 그 도메인 또는 하위 도메인과 일치해야 하며, HTTPS가 아니거나 host가 다른 집계·재배포 feed는 등록을 거부한다.
- 초기 RSS 매체·채널은 버전 관리되는 `news-publishers-v1.json` registry로 등록하고 운영 환경의 secret은 별도 서버 설정에서 주입한다. registry의 스키마와 검증 규칙은 구현 시점에 고정하고, 실제 매체 항목은 BACK 파트가 공개 전에 채운다. 항목이 비어 있어도 구현과 내부 검증은 테스트 fixture로 진행하며 이때 공개 조회는 PREPARING을 반환한다. PD-15 회신은 공개 게이트이지 구현 차단 조건이 아니다.
- 모든 외부 HTTP 요청은 공통 HTTP 보안 경계를 통과해야 하며 이 정책은 RSS/GDELT adapter 구현의 선행 조건이다. 고정 한도는 다음과 같고 서버 설정으로만 더 엄격하게 조일 수 있다.
  - scheme는 `https`만 허용한다. host는 등록된 publisher registry와 GDELT host allowlist에 있는 값만 허용한다.
  - redirect는 최대 3회 따르고 매 hop마다 scheme와 host allowlist를 다시 검사한다. 초과하거나 allowlist를 벗어나면 그 항목을 수집에서 제외한다.
  - 연결 제한 시간 5초, 요청 전체 제한 시간 20초를 적용한다.
  - 응답 본문은 최대 10MiB까지만 읽고 초과하면 스트림을 중단해 그 응답을 격리한다.
  - content-type은 RSS가 `application/rss+xml`·`application/xml`·`text/xml`, GDELT가 `application/json`만 허용하고 그 밖의 값은 malformed로 분류한다.
  - XML parser는 DTD, 외부 general/parameter entity, XInclude를 비활성화하고 secure processing을 켠다.

- RSS는 `ETag`·`Last-Modified` 조건부 요청과 채널의 `ttl`·`skipHours`·`skipDays` 힌트를 존중한다.
- RSS `304 Not Modified`는 `SUCCEEDED`로 기록하고 `lastSuccessAt`을 갱신하되 cursor와 기사 데이터는 변경하지 않는다.
- GDELT 근시점 조회는 DOC 2.0 ArticleList API를 기간·주제로 제한한다. 원시 15분 배포 파일은 필요한 구간의 보완 처리만 oldest-first로 수행하고 장기 전체 적재는 하지 않는다.
- GDELT 조회가 결과 상한에 도달하면 요청 구간을 절반씩 분할해 재조회한다. 최소 15분 구간에서도 상한이 유지되면 수신분만 저장하고 `NewsCollectionState.status=INCOMPLETE`, `errorCategory=RESULT_CAP_REACHED`로 기록한다. cursor는 그 구간 너머로 전진하지 않고 `lastSuccessAt`도 갱신하지 않아 다음 회차가 같은 구간을 재시도한다. 부분 수집 구간을 완전 수집 커버리지로 표현하지 않는다.
- GDELT 배포 레코드만으로 검증된 기사 발행 시각·제목을 확보하지 못하면 그 레코드는 연결·중복 판정 보조에만 쓰고 표시 후보로 만들지 않는다.
- RSS 발행 시각은 timezone 포함·파싱 성공·`publishedAt <= collectedAt + 5분`을 모두 만족할 때만 VERIFIED다. 나머지는 UNKNOWN과 null publishedAt으로 저장하며 현재 조회의 후순위에만 포함하고 과거 조회에서는 제외한다.
- timeout·429·503·일시적 5xx는 transient로 분류한다. 429·503은 `Retry-After`를 우선하고, 없으면 지수 백오프와 jitter를 적용해 회차당 최대 3회 후 다음 회차로 넘긴다. 401·403은 terminal configuration 오류로 분류해 자동 재시도하지 않고 비활성화 후보로 기록한다.
- malformed XML·JSON은 해당 응답을 격리하고 필수 필드가 누락된 개별 항목은 그 항목만 제외한다. 단일 source 실패는 다른 source 회차와 시장 API로 전파하지 않는다.
- 소스별 DB lease, 영속 watermark/cursor, source item key와 canonical URL unique 제약으로 재시작·재처리를 멱등하게 만든다.
- 수집 소스와 일일 정리 작업은 같은 lease 테이블에서 조건부 `UPDATE ... WHERE leaseUntil IS NULL OR leaseUntil < now()`로 하나의 인스턴스만 획득한다. lease TTL은 15분이며 만료된 lease는 다른 인스턴스가 회수한다. 정리 작업의 sourceKey는 `retention-cleanup`이다.
- 네트워크 호출은 트랜잭션 밖에서 수행하고, 한 회차의 결과는 `기사 upsert → 섹터·주제 연결 → 묶음 재생성 → cursor·수집 상태 갱신` 순서로 하나의 트랜잭션에서 커밋한다. cursor는 이 트랜잭션 안에서만 전진하므로 중단·실패 시 기사와 cursor가 함께 롤백되고 다음 회차가 같은 구간을 멱등하게 재처리한다.
- 묶음 재생성은 그 회차가 건드린 기사가 속한 묶음 범위로 한정하고 같은 입력에 대해 같은 결과를 낸다.

### 필터·중복·묶음 규칙 v1

1. 요청 기간 밖 항목 제거
2. 등록된 섹터·기업·경제 주제에 연결되지 않은 항목 제거; 해외 보도는 국내 연결 주제가 있어야 함. RSS `category`는 원문 값을 그대로 쓰지 않고 `news-link-v1`의 주제 키로 매핑된 경우에만 연결 근거가 되며, 매핑되지 않은 값은 무시하고 미등록 category로 집계한다. 매핑 전 원문 category 값은 저장하지 않는다
3. URL이 있으면 allowlist 안의 제한된 redirect를 모두 따른 최종 URL에 scheme·host 소문자화, 기본 포트 제거, fragment 제거, 빈 path의 `/` 통일, 루트 외 후행 `/` 제거, query 이름·값 정렬, `news-url-normalization-v1`에 등록된 `utm_*`·`gclid`·`fbclid` 추적 parameter 제거를 적용해 동일 원문 병합. 그 밖의 query 값과 path 대소문자는 보존하고 malformed URL·allowlist 밖 redirect는 제외
4. URL이 없으면 `(publisherId, sourceItemId)`로 중복 판정해 매체 간 GUID 충돌을 분리한다. 그것도 없으면 정규화 제목·publisher·유효 시각 hash를 사용하고 어느 식별자도 만들 수 없으면 제외
5. 사건 묶음은 48시간 이내, 공통 주제 1개 이상, 정규화 제목 token Jaccard 0.60 이상을 모두 만족할 때만 병합한다. 새 기사는 묶음의 모든 기존 기사와 세 조건을 만족해야 하는 complete-link 방식을 사용하며, 경계 미달·불확실·전이적 bridge 항목은 별도 묶음으로 둔다.

외부 노출 ID는 사업 키의 SHA-256을 base32url 32자로 인코딩해 결정론적으로 만든다. `articleId`는 canonical URL, `digestId`는 대표 articleId와 묶음 규칙 버전에서 유도하며 `ingestRunId`만 회차별 UUIDv7이다. 사업 키가 다른데 ID가 같은 충돌은 UNIQUE 제약으로 감지해 해당 항목을 제외하고 `ID_COLLISION`으로 기록하며 기존 행을 덮어쓰지 않는다.

규칙 버전은 `news-url-normalization-v1`과 `news-grouping-v1`로 저장한다. 제목 token에는 한국어/영문 Unicode NFKC 정규화, 소문자화, 문장부호·연속 공백 제거를 적용하고 원제목은 그대로 보존한다.

### 요약·정렬 규칙 v1

- 대표 기사는 검증된 발행 시각이 가장 빠른 기사, 동률이면 canonical URL 오름차순으로 정한다.
- 짧은 사실 요약은 제목과 매체명이 있고 등록된 섹터 또는 주제가 하나 이상일 때만 대표 제목을 핵심으로 사용하는 결정론적 한 문장 formatter로 만든다. PUBLIC 응답의 요약은 허가된 매체의 기사만 입력으로 받는 같은 formatter를 조회 시점에 다시 실행해 만든다. 저장된 `factualSummary`는 전체 멤버 기준 값이며 INTERNAL 모드에서만 노출한다. RSS description과 기사 본문은 사용하지 않고 새로운 사실·인과·전망 표현을 추가하지 않는다. 조건을 만족하지 않으면 표시 대상에서 제외한다.
- 공개 허가 필터를 먼저 적용한 뒤 `서로 다른 매체 수 내림차순 → 검증 발행 시각 존재 여부 내림차순 → 최신 검증 발행 시각 내림차순 → 발행 시각 미확인 항목의 collectedAt 내림차순 → digestId 오름차순`으로 정렬한다. 이를 `news-importance-v1`로 저장한다.
- GDELT Tone, 본문, 전략 결과는 묶음·정렬에 사용하지 않는다.

### 조회와 상태

- 외부 경로는 `/api/market-news/digests`; 현재 뉴스는 선택적 `sectorId`, 과거 뉴스는 필수 `from`, `to`를 사용한다. `sectorId`는 001이 소유하는 외부 식별자를 그대로 받으며 012는 sector 마스터를 만들지 않는다. 등록되지 않은 값은 400으로 거부하고, 001 대응표가 비어 있는 동안 sector 지정 조회는 `EMPTY`를 반환한다.
- 서버 설정으로 INTERNAL/PUBLIC 노출 모드를 정한다. 클라이언트가 내부 모드를 선택할 수 없다.
- PUBLIC 상태는 허가된 매체 존재 여부 → 표시 자료 존재 여부 → 신선도 순으로 판정한다. `lastUpdatedAt`은 활성화되고 현재 노출 가능한 뉴스원들의 `lastSuccessAt` 최댓값이다. 허가된 매체가 없으면 `PREPARING`, 허가 매체는 있지만 자료가 없으면 `EMPTY`, 기존 자료가 있고 `lastUpdatedAt`이 현재보다 30분 이상 오래됐으면 `STALE`, 나머지는 `READY`다. 뉴스원별 freshness는 별도 수집 상태와 운영 지표에 둔다.
- `READY`, `EMPTY`, `STALE`, `PREPARING`은 모두 HTTP 200이다. 외부 원천 장애가 시장 흐름 전체 장애로 전파되지 않는다.
- `STALE` 기본 기준은 마지막 성공 후 30분이며 설정으로 조정할 수 있다.
- 과거 조회는 `publishedAtStatus=VERIFIED`이며 `[from,to]` 안인 기사만 포함하고 요청 구간은 최대 93일이다.
- 세 가지 기간 경계는 서로 독립이며 어느 하나가 다른 하나를 보장하지 않는다.
  - **요청 구간 상한 93일**: 012가 응답 크기와 질의 비용을 묶기 위해 정한 값이다. 이 범위를 요청할 수 있다는 뜻일 뿐 자료가 있다는 뜻이 아니다.
  - **GDELT 자체 제공 범위**: 외부 서비스가 정하는 조건이며 012가 통제하지 않는다. 이 범위를 근거로 커버리지를 주장하지 않는다.
  - **2026-11-07 삭제 시점**: 저장된 뉴스 메타데이터의 보관 종료 시점이며 조회 상한과 무관하다.
  - 실제로 반환되는 범위는 세 경계와 실제 수집분의 교집합이고, 응답은 없는 구간을 정상 `EMPTY`로 처리한다.
- `/api/market-news/articles/export`는 기간·섹터 필터와 cursor pagination을 받는 JSON API다. 페이지당 최대 500개를 반환하며 PUBLIC 정책에서 허용된 매체와 허용 필드만 투영하고 본문 필드는 스키마에 두지 않는다.

### 공개 허가 이력

- `NewsPublisher`에는 조회 최적화를 위한 현재 허가 상태를 둔다.
- 요청과 회신은 `NewsPublisherPermissionHistory`에 append-only로 저장하며 회신마다 허용 필드 집합을 기록한다.
- PUBLIC 조회·순위·내보내기는 현재 `ALLOWED` 상태와 허용 필드 투영을 먼저 적용한다.
- 허가·미허가 매체가 섞인 묶음은 허가된 기사만으로 대표·요약·매체 수·정렬을 조회 시점에 다시 계산한다. 저장된 digest 행은 전체 멤버 기준 값을 유지하고 허가 변경으로 덮어쓰지 않아 재현성과 허가 복원을 보장한다. 허가 기사가 남지 않거나 요약 조건 미달이면 그 묶음을 응답에서 제외한다.

### 보관과 관측성

- 실행 환경이 2026-11-07까지 유지되면 일일 cleanup이 뉴스 기사·묶음·연결·수집 상태·허가 이력·규칙 버전을 일괄 삭제한다. 매체 등록 행도 함께 삭제하며 감사 목적으로 남기는 예외 테이블은 없다. 완료 판정은 뉴스 도메인 모든 테이블의 행 수 0으로 한다. 반복 실행은 삭제 대상 0건의 정상 no-op이며 장기 감사 테이블은 만들지 않는다. 그 전에 EC2와 저장소가 회수되면 별도 cleanup을 요구하지 않는다.
- source별 시도/성공 시각, 상태, retry count, 다음 시도, cursor와 오류 분류를 보존한다. 로그에는 run ID, 처리·제외·중복·묶음 건수와 시간을 남긴다.
- 비밀값 가림은 로그, 저장된 `errorCategory`, 운영 지표 label, API 응답 전부에 동일하게 적용한다. URL query string, 인증 헤더, API key·token, `NewsPublisher.rssUrl`, GDELT 요청 URL, 외부 응답 원문은 어디에도 남기지 않는다. 진단에는 host, `sourceKey`, `publisherKey`, run ID, 사전 정의된 오류 분류 코드만 사용한다. 예외 메시지는 원문을 그대로 싣지 않고 분류 코드로 변환해 기록한다.
- 관측 지표는 다음을 고정해 남긴다. 모든 label에는 `sourceKey`, `sourceType`, 오류 분류 코드 등 가림 정책을 통과한 값만 사용한다.
  - `news.source.last_success.age` (source별 마지막 성공 경과 시간), `news.source.run.duration`, `news.source.retry.count`, `news.source.error.count{category}`
  - `news.ingest.items{outcome=collected|excluded|duplicate}`, `news.ingest.excluded{reason}`, `news.ingest.digest.count`
  - `news.query.duration`, `news.query.responses{state=READY|EMPTY|STALE|PREPARING}`
  - `news.retention.run{outcome}`, `news.retention.deleted.rows`
- 뉴스 신선도는 전용 health indicator `newsFreshness`로만 노출하고 Actuator 전체 health 집계에서 제외한다. 뉴스가 STALE·EMPTY·PREPARING이거나 모든 뉴스원이 실패해도 `/actuator/health`의 전체 status는 UP을 유지하며 시장 API와 다른 도메인에 영향을 주지 않는다.

## Complexity Tracking

신규 브로커·서비스·검색 엔진·LLM을 도입하지 않는다. 작업 묶음은 수집 보안을 WB02에 포함하고 WB04를 공개 허가·종료 정책으로 제한하여 Constitution의 단일 목적·독립 롤백 조건을 충족한다.
