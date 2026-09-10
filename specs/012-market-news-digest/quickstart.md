# Quickstart Validation: 시장 흐름 뉴스 묶음

이 문서는 구현 후 012 기능을 검증하는 실행 가이드다. 구현 코드는 포함하지 않는다.

## Prerequisites

- Java 21
- Docker가 실행 중인 환경(Testcontainers PostgreSQL 16용)
- 저장소 루트에서 PostgreSQL을 사용할 수 있는 설정
- RSS/GDELT 실연동은 별도 opt-in 환경에서만 수행; 기본 테스트는 로컬 fixture 사용
- API 계약: [contracts/openapi.yaml](contracts/openapi.yaml)
- 데이터 규칙: [data-model.md](data-model.md)

## 1. 정적 계약과 전체 backend 검증

```powershell
Set-Location backend
.\gradlew.bat spotlessApply build
```

Expected:

- Spotless, ArchUnit, 단위·통합 테스트 통과
- Testcontainers/Flyway 테스트가 `skipped`되지 않고 V3 schema validation 통과
- `frontend/`, `worker/`, `data/`에는 변경이 없어야 함

## 2. 뉴스 도메인 집중 검증

```powershell
Set-Location backend
.\gradlew.bat test --tests 'com.ssafy.thorp.news.*' --rerun-tasks
.\gradlew.bat test --tests 'com.ssafy.thorp.ArchitectureTest' --tests 'com.ssafy.thorp.common.persistence.FlywayMigrationTest' --rerun-tasks
```

Expected:

- RSS 누락 필드, 잘못된 날짜, 조건부 GET 304 처리
- GDELT 발행 시각과 기록 시각 분리
- URL 정규화와 재수집 멱등성
- 불확실한 사건 미병합
- 공개 허가 필터가 순위 계산보다 먼저 적용됨
- 과거 조회에 발행 시각 미확인·기간 밖 기사 0건
- 본문 형태 필드가 persistence/API에 없음

## 3. 현재 뉴스 API

backend를 실행한 후 외부 프록시 기준으로 요청한다. Spring 직접 호출 시 `/api`를 제거한다.

```powershell
Invoke-RestMethod 'http://localhost:8080/market-news/digests'
Invoke-RestMethod 'http://localhost:8080/market-news/digests?sectorId=semiconductor'
```

Expected:

- 응답은 [current-ready.json](contracts/examples/current-ready.json), [current-stale.json](contracts/examples/current-stale.json), [public-preparing.json](contracts/examples/public-preparing.json) 중 하나와 같은 구조
- `digests`는 0~3개
- 빈 상태와 준비 중도 HTTP 200
- `body`, `content`, `html`, `tone`, 투자 추천 필드가 없음

## 4. 과거 뉴스 API

```powershell
Invoke-RestMethod 'http://localhost:8080/market-news/digests?from=2026-07-01T00:00:00Z&to=2026-09-30T23:59:59Z'
```

Expected:

- 두 경계가 모두 포함되는 93일 이하 요청만 허용
- 모든 반환 article의 `publicationTimeStatus`가 `VERIFIED`
- 범위 밖 또는 발행 시각 미확인 기사 없음
- 결과가 없으면 [historical-empty.json](contracts/examples/historical-empty.json)처럼 HTTP 200 + `EMPTY`

## 5. 실패·지연 검증

fixture client로 timeout, 429, 503을 각각 재현한다.

Expected:

- `Retry-After` 우선 적용
- bounded retry 소진 뒤 source 상태와 `nextAttemptAt` 기록
- 마지막 성공 자료가 있으면 `STALE`과 `lastUpdatedAt` 반환
- 뉴스 실패가 `/markets/*` 조회나 backend health 전체를 DOWN으로 만들지 않음
- 로그에 secret, query token, 기사 본문이 없음

## 6. 공개 허가 검증

동일 fixture를 INTERNAL과 PUBLIC 서버 설정에서 조회한다.

Expected:

- INTERNAL은 테스트용 PENDING 매체를 볼 수 있음
- PUBLIC은 ALLOWED 매체만 순위와 묶음 계산에 포함
- 허가 매체가 하나도 없으면 `PREPARING`
- 허가 매체가 있지만 자료가 없으면 `EMPTY`, 기존 자료가 있고 마지막 성공 후 30분 이상이면 `STALE`
- 클라이언트 query로 INTERNAL 모드를 활성화할 수 없음
- 반복 요청·회신은 이력에 추가되고 publisher의 최신 상태와 원자적으로 일치함

## 7. 기사 메타데이터 내보내기

```powershell
Invoke-RestMethod 'http://localhost:8080/market-news/articles/export?from=2026-09-01T00:00:00Z&to=2026-09-10T23:59:59Z&limit=100'
```

Expected:

- [export-allowed.json](contracts/examples/export-allowed.json)과 같은 JSON 구조
- 한 페이지 최대 500개와 안정된 `nextCursor`
- PUBLIC에서 ALLOWED 매체와 해당 회신이 허용한 필드만 반환
- PENDING·DENIED 항목과 `body`, `content`, `html` 필드가 없음

## 8. 삭제 정책 검증

주입한 Clock을 2026-11-07 전후로 고정해 retention service를 반복 실행한다.

Expected:

- 이전에는 삭제 0건
- 이후 첫 성공 실행에서 뉴스 기사·묶음·연결·수집 상태·매체·허가 이력·규칙 버전 삭제
- 이후 반복 실행은 오류 없는 no-op
- 별도 장기 감사 데이터는 생성하지 않음
- 실행 환경과 저장소가 먼저 회수된 경우 추가 작업을 요구하지 않음

## 9. 성능과 수집 주기 검증

100,000개 기사 fixture를 적재한 PostgreSQL 16 Testcontainers 환경에서 조회를 30회 준비 실행한 뒤 100회 측정한다. RSS 20개와 GDELT 1개 로컬 HTTP fixture를 동시에 due 상태로 만든 수집 회차도 측정한다.

Expected:

- 뉴스 묶음 조회 p95 500ms 이하
- 정상 source 전체 회차 15분 이내 완료

## 10. 계약 예시 동치

MockMvc 통합 테스트가 실제 response JSON을 `contracts/examples/*.json`과 비교한다. 예시가 바뀌면 OpenAPI, 예시, response DTO와 테스트를 같은 Task/MR에서 갱신한다.
