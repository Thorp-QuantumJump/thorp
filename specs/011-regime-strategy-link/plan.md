# Implementation Plan: 경제환경 사례와 전략 비교 연결

**Branch**: `011-regime-strategy-link` (spec 디렉터리 식별자. 구현 Task의 Git 브랜치는 Jira Task마다 `{type}({position})/{JIRA_NUMBER}-{description}`로 따로 만든다.) | **Date**: 2026-09-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/011-regime-strategy-link/spec.md` (Clarifications 2026-09-17·09-18 및 2026-09-20 합의 반영판)

## Summary

010이 찾은 경제환경 사례(관측 3개월)의 **직후 63거래일**(보조 21거래일)에서 사용자의 전략이 어떻게
행동했는지를, 이미 돌려 둔 004 전체 기간 연속 운용 결과에서 **잘라 요약**해 사례 옆에 붙인다. 진입은
둘이다 — 시장 흐름(009)에서 사례를 먼저 고르고 섹터·전략을 정하는 길과, 진단 결과(005)에서 기존
조건을 들고 들어와 분야를 고른 뒤 사례를 찾는 길. 두 번째 길은 비교 묶음(005 비교 기준·비교 대상
최대 2개)의 모든 전략이 평가 가능한 사례만 기본으로 보이고, 자료가 부족한 전략 결과만 제한하며
사례를 바꾸지 않는다.

기술 접근은 세 가지 축이다.
1. **판정은 Backend, 계산은 Worker.** 가용성 판정(상장일·워밍업·사후 자료·분석 기간)은 메타데이터
   조회라 Backend `regimelink` 도메인이 하고, 구간 요약(수익률·최대 하락·거래·비용·회전율·손실 기여·
   KOSPI 참고)은 별도 큐 `regime-link.jobs`로 Worker가 (실행 × 사례 × 시나리오) 단위로 계산해
   저장한다. Backend는 잘라낼 시계열 사본을 메시지에 담고 Worker는 시장 자료만 더한다(research R1·R2).
2. **새 실행은 004로.** 현금 시작(`FRESH_CASH_FROM`, d0~d63)과 분석 기간 밖 재계산(`FULL_PERIOD`,
   공통 가용 범위)은 004 접수 서비스를 호출한다. 둘 다 사용자가 켤 때·처음 열 때만이며, 완료되면
   그 실행이 잘라내기 입력이 된다(R5).
3. **010 위에 얹는다.** 사례 식별자는 `(snapshotKey, endMonth)`, 필터는 010 표시 후보(≤10) 안에서만,
   순위는 010 원래 순위 그대로. 011은 순위를 다시 매기지 않고 표시 후보를 다시 뽑지 않는다(R6).

## Technical Context

**Language/Version**: Java 21 (Backend `regimelink` 도메인 — 판정·접수·조회), Python 3.11 (Worker
`regime_link` 소비자 — 잘라내기·요약 계산), TypeScript 5 / React 19 (Frontend 화면 3개)

**Primary Dependencies**: Spring Boot 3.5 (Web·Data JPA·Validation·AMQP), Flyway, PostgreSQL 드라이버 /
pandas·NumPy·pika, 기존 `worker.market_data`와 구간 전용 계산(R3, DEP-004-011 검증 선행) / 프론트는 기존 의존만
(`@visx`·d3 차트 시스템 재사용 여부는 FE 묶음에서 결정, 표 위주 화면이라 필수는 아니다)

**Storage**: PostgreSQL. Flyway 마이그레이션 신규 1개(`regime_link_filter`·`regime_link_search`·
`regime_link`·`regime_link_result`). 010 표(`regime_search_snapshot`·`regime_search_case`)와 004 표
(`backtest_run`·`backtest_result`·`backtest_trade`)는 **읽기만** 한다. 011이 만드는 표는 넷이다
([data-model.md](data-model.md))

**Testing**: JUnit 5 + Testcontainers(PostgreSQL·RabbitMQ) + Spring Boot Test (Backend), pytest
(Worker — 잘라내기 정의 수기 대조 포함), Vitest (Frontend — 금지 표현 정적 검사 포함)

**Target Platform**: Linux 서버(Docker), 데스크톱 브라우저 우선 한국어 웹

**Project Type**: 모노레포 웹 서비스. 이번 범위는 Backend 신규 도메인 1개 + Worker 소비자 1개(별도
엔트리포인트) + Frontend 화면 3개(분야 선택·검색 결과 with 가용성·사례의 전략 결과 영역) + 003 화면
제약 1건(시장 흐름 경로의 분석 기간 하한)

**Performance Goals**: 잘라내기 작업 대기 포함 p95 3초, 가용성 판정 조회 p95 300ms — **측정
목표이지 한도가 아니다**(원칙 IX, research R9). 현금 시작·전체 기간 재계산은 004의 측정 체계에
잡힌다

**Constraints**: Backend에서 시계열 산술 0건(잘라낼 범위만 정한다) / 사례 대체·순위 재계산 0건
(FR-004·FR-017) / 유지율·초과 수익·승패 표현 0건(FR-013·FR-021) / 사후 구간 미리 계산 0건
(Clarifications) / 워커는 결과 DB 접속 없음 / 010·004 표에 쓰기 없음

**Scale/Scope**: 사례 ≤10 × 실행 ≤2 × 시나리오 2 = 사용자 검색 하나에 최대 40 링크. 메시지 하나
≈ 65거래일 × (자산가치 + 종목 ≤10 비중 + 거래) ≈ 수십 KB. Backend 신규 패키지 1개, 표 4개,
검색 기록 POST와 구간 조회를 포함한 엔드포인트 7개, 워커 모듈 5개, 프론트 화면 3개

### 해소된 NEEDS CLARIFICATION

Phase 0 결정 11건에 2026-09-20 수정과 R12를 반영했다. DEP-004-011 입력 확보는 미완료다. 근거는 [research.md](research.md).

| 항목 | 결론 |
|---|---|
| 계산 작업 전달 경로 | 별도 exchange/큐 `regime-link`, 워커 별도 엔트리포인트 (R1) |
| 워커 입력 | Backend가 시계열·거래 사본을 메시지에 담고, 워커는 시장 자료만 추가 (R2) |
| 구간 요약 정의 | 구간 경계 손익·실제 체결대금으로 계산. 회전율만 연환산 단위 명시; DEP-004-011 선행 (R3) |
| 가용성 판정·필터 키 | Backend 메타데이터 조회 5단계, sha256 키, KOSPI 거래일 달력 (R4) |
| 현금 시작·전체 기간 재계산 | 004 접수 서비스 호출 → 완료 후 잘라내기 작업 연쇄 (R5) |
| 진입 계약·사례 식별자 | `(snapshotKey, endMonth)`를 `~`로 이어 한 값, 주소 둘 (R6) |
| 로그인 요구 | 쓰기 컨트롤러가 요청자 확인, `401 LOGIN_REQUIRED` (R7) |
| 010·005 미완 상태 | 계산·판정·계약 먼저, BE 조회·FE는 010 M6·M7 뒤 (R8) |
| 성능 | 측정 목표만 (R9) |
| 006 경계 | 011 표에 FR-023 항목 기록, 006이 읽음 (R10) |
| 문구 통제 | 금지 표현 정적 검사 + 타입으로 차이 값 배제 (R11) |

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 이후 재확인.*

판정 기준은 승인된 constitution 3.6.0 본문이다. 미승인 3.3.0·3.5.0 Git·Jira 초안은 근거로 쓰지 않는다.

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. 중심 약속은 전략 진단 | PASS | 시장 흐름의 사례를 '이 기간으로 전략 비교'로 진단에 연결하고(US1), 진단 결과에서 맥락으로 이어진다(US2). 검색은 신호가 아니라 맥락임을 FR-016이 고정한다 |
| II. 첫 화면은 단순하게 | PASS | 사용자는 사례를 고르고 결과를 본다. 필터 키·구간 날짜·계산 단위를 입력하지 않는다. 63거래일이 기본, 21거래일과 손실 기여 상세는 펼쳐 보는 층이다(FR-006) |
| III. 진단이지 추천이 아니다 | PASS | 사후 성과 정렬 없음(비교 묶음은 005 순서), 승자·추천·"더 벌 수 있다" 0건(SC-007), 유지율 화면 비표시(FR-021), KOSPI 차이 값 없음(FR-013). 정적 검사로 고정(R11) |
| IV. 비교는 같은 조건에서만 | PASS | 같은 사례의 모든 전략이 같은 d0~d63·같은 비용(FR-011). 사후 구간 값은 전체 기간과 같은 경제적 정의를 구간 경계에 맞춰 적용(R3, 입력 확보 전 계산 착수 금지). 현금 시작은 기준 실행의 조건 사본(R5). 길이가 다른 사례는 21거래일 보조 평가가 공통 자(FR-008) |
| V. 당시에 알 수 있던 정보만 | **조건부 PASS** | 최신 개정자료 기반 **역사 탐색**이며 당시 재현이 아니다. 응답 `basis.kind=HISTORICAL_REVISION`을 010과 같이 필수로 내보내고 화면이 "역사 설명용 기준"을 표시한다(FR-007·FR-015). 사후 구간은 시간이 지나야 완성되므로 미완성은 미완성으로 표시한다(FR-008) |
| VI. 없는 결과를 만들지 않음 | PASS | 사례 대체 0건(FR-017), 미완성은 완성 거래일 수와 함께(FR-008), 빈 공통 가용 범위는 정상 화면(FR-019), 계산 실패는 실패로(Edge Cases), 필터 후 부족해도 아래 순위에서 채우지 않음(FR-003). 분석 기간 밖은 '분석 기간 밖'으로 '자료 부족'과 구분 |
| VII. 탐색과 검증 구분 | PASS | 화면 문구는 "역사 탐색"이며 "유효성 검증"이라 쓰지 않는다. 63/21·d0 정의는 기획서 확정(PD-08)이고 결과를 보고 바꾸지 않는다. 미확인 U05는 FR-020의 검증 전 동작대로 |
| VIII. 재현할 수 있게 기록 | PASS | 링크는 (실행 × 사례 × 시나리오)로 식별하고 필터 키·구간 날짜·시나리오·검색 경로·`basis`를 `regime_link_search`·`regime_link`에 남긴다(FR-023). 004 실행과 010 snapshot revision을 FK로 고정한다. 잘라낸 범위가 메시지에 그대로 있어 재실행이 같은 값을 낸다 |
| IX. 실행 한도는 실측 | PASS | 목표는 측정 목표만(R9). 미리 계산하지 않는다. 새 실행은 004 상한(`EXECUTION_LIMIT`)을 그대로 받는다. 결제·과금 없음 |
| X. 자료는 이용 조건 안에서 | PASS | 시세·KOSPI는 001 적재 표만 읽는다. 외부 원천 호출 없음. 출처 표시는 001·005 화면 규칙을 따른다 |

**조건부 PASS(원칙 V)**는 회피가 아니다 — 011의 모든 결과는 "최신 개정자료를 이용한 역사 탐색"이며
spec FR-007·FR-015가 표시를 요구하고, 이 spec은 010 SC-009와 같은 방식으로 응답 필수 필드
`basis.kind`와 화면 표시로 그것을 검사한다. Complexity Tracking에 적을 위반이 아니다.

### Phase 1 이후 재확인

| 원칙 | 판정 | 설계가 바꾼 것 |
|---|---|---|
| IV | **강화** | 잘라내기 입력(시계열·거래 사본)과 구간 날짜를 메시지에 담아 같은 사례의 모든 링크가 같은 d0~d63을 쓰는 것이 메시지 수준에서 고정된다. 현금 시작 실행은 기준 실행 `config` 사본에서 `scenario`·`start`·`end`만 바꾼다(계약 §2) |
| VI | **강화** | 가용성 상태를 `NORMAL / DATA_INSUFFICIENT / POST_INCOMPLETE / OUT_OF_PERIOD`로 저장 값으로 두고 재판정하지 않는다. 필터 후 사례 수가 줄어도 응답의 `cases[]`는 010 순위 번호를 유지한다(계약 §1) |
| VIII | **강화** | `regime_link.snapshot_key`를 복사해 010 revision이 철회돼도 "무엇을 봤나"가 남는다(010 `regime_search_log`와 같은 방식). `regime_link_filter.definition`에 키의 입력을 그대로 둔다 |
| III | PASS 유지 | 계약 §5 "내보내지 않는 것" — 유지율·초과 수익·승패·정렬 점수를 응답에 두지 않는다 |
| V | **강화** | `basis.kind`를 링크 응답과 검색 응답 둘 다의 필수 필드로 |

## Project Structure

### Documentation (this feature)

```text
specs/011-regime-strategy-link/
├── plan.md                              # 이 파일
├── research.md                          # Phase 0 (R1~R11)
├── data-model.md                        # Phase 1 — 표 4개, 상태 전이, 보관, 소유 경계
├── quickstart.md                        # Phase 1 — 검증 시나리오 S1~S9
├── contracts/
│   ├── regime-link-api.md               # Backend ↔ Frontend HTTP
│   ├── regime-link-messaging.md         # Backend ↔ Worker RabbitMQ (RegimeLinkJob v1)
│   └── entry-handoff.md                 # 009·005 → 011 진입 주소와 003 제약
├── checklists/requirements.md
└── tasks.md                             # /speckit-tasks 산출물
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/regimelink/      # 신규 도메인. 계산하지 않는다
├── presentation/
│   ├── controller/RegimeLinkController.java           # GET 4 · POST 2, 검색 기록 POST는 별도 컨트롤러
│   ├── request/                                       # CreateRegimeLinkRequest …
│   └── response/                                      # AvailabilityResponse, RegimeLinkResponse …
├── application/
│   ├── service/
│   │   ├── AvailabilityService.java                   # 5단계 판정 + 필터 키 (R4)
│   │   ├── RegimeLinkCommandService.java              # 링크 생성·재시도, 004 접수 호출 (R5)
│   │   ├── RegimeLinkQueryService.java
│   │   ├── RegimeLinkJobAssembler.java                # 잘라낼 범위 산정 + 메시지 조립 (R2)
│   │   └── RegimeLinkEventHandler.java                # FINISHED 반영, 004 COMPLETED 후처리 연쇄
│   ├── command/ · result/
│   ├── repository/                                    # RegimeLinkRepository, RegimeSearchReadRepository(010 읽기) …
│   └── port/
│       ├── RegimeLinkJobPublisher.java
│       └── message/                                   # RegimeLinkJob, RegimeLinkEvent (record)
├── domain/model|vo/                                   # LinkId, CaseRef(snapshotKey,endMonth), PostWindow,
│                                                      #   Availability, Scenario, FilterKey
└── infrastructure/
    ├── persistence/                                   # *JpaEntity, *Mapper, Spring Data
    ├── messaging/                                     # RegimeLinkRabbitConfig(토폴로지 선언), 발행·이벤트 소비
    └── fixture/                                       # ⚠ [임시] 010 표가 없을 때의 읽기 대역 (R8) — 010 M6 머지 시 삭제

backend/src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__regime_link.sql
backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java   # spec 011 블록: LOGIN_REQUIRED, LINK_NOT_READY, OUT_OF_PERIOD_CONFIRM_REQUIRED

worker/src/worker/regime_link/                         # 신규. 백테스트 소비자와 분리
├── __init__.py
├── contracts.py                                       # RegimeLinkJob·RegimeLinkEvent (pydantic, ContractModel)
├── messaging.py                                       # 계약 상수(exchange·queue·routing key) — 백엔드 상수와 대조 테스트
├── slicer.py                                          # 구간 잘라내기·요약 — R3 구간 산식, DEP-004-011 입력 검증 선행
├── consumer.py                                        # 수신·검증·계산·FINISHED 발행·ACK
└── rabbitmq.py                                        # pika 구현
worker/src/worker/regime_link_main.py                  # 엔트리포인트 python -m worker.regime_link_main
worker/tests/regime_link/                              # 수기 대조 픽스처 포함

frontend/src/
├── pages/RegimeLinkPage.tsx                           # /regime-link — 분야 선택 → 검색 결과(가용성) → 사례의 전략 결과 영역
├── pages/RegimeLinkStartPage.tsx                      # /regime-link/start — 009 진입, case를 들고 002로
├── components/regimelink/                             # AvailabilityBanner, CaseStrategyResults, PostWindowTable,
│                                                      #   ScenarioToggle, FullPeriodConfirm, forbiddenPhrases.test.ts
├── hooks/useRegimeLink.ts · useAvailability.ts
├── api/regimeLink.ts
├── types/regimeLink.ts
└── pages/StrategySettingsPage.tsx                     # 변경: case가 있으면 분석 기간 하한 제약 (entry-handoff §3)

docs/specs/regime-link-api/README.md · docs/specs/regime-link-messaging/{README.md,examples/}   # 계약 요약·픽스처
```

**Structure Decision**: 계산은 `worker/regime_link`, 판정·접수·조회는 `backend/regimelink`, 화면은
`frontend`다. 루트 AGENTS.md "Backend는 계산하지 않는다"를 지키되, 상장일·워밍업·거래일 달력 조회는
004 접수 검증이 이미 Backend에서 하는 종류의 일이므로 판정은 Backend에 둔다(R4). 워커는 별도
엔트리포인트로 두어 백테스트 소비자의 큐 규칙(`WORKER_ROLE`)과 섞이지 않게 한다(R1). Backend
패키지는 ADR-0001의 4계층을 따르고 `result`·`backtest`·`regime`(010) 도메인은 건드리지 않는다 —
`regime`을 읽는 것은 `application/repository` 인터페이스로만 한다.

## 문서 정리 묶음 (S15P21E205-358)

- Jira Task: S15P21E205-358, Story: S15P21E205-85, Epic: S15P21E205-1. 담당: 우동균_E205, 추정 3 SP.
- 브랜치: `docs(be)/358-regime-link-decisions`. 011 판정·계산·검색 기록 계약과 합의된 014 예외를 한 문서 MR로 정리한다. 구현 M1~M8과 별개다.
- 검증: 문서 간 용어·필드·작업 추적과 링크 검사, 현재 004 입력 계약 대조. 코드 구현이나 수치 정확성 검증 완료로 보고하지 않는다.

## 구현 순서

**선행 게이트**: M1의 계산 픽스처 동결 및 M2 착수 전에 DEP-004-011의 입력 대응과 독립 대조를 완료한다. 현재는 미완료이며, 필요한 004 계약 확장은 별도 구현 범위로 합의한다.


묶음마다 끝에 검증 명령이 통과해야 다음으로 간다. 파트가 갈리므로 Jira 티켓도 묶음 단위다.

| 묶음 | 파트 | 내용 | 끝났다고 말할 근거 |
|---|---|---|---|
| **M1** | DOCS·BE·WK | 메시지 계약 v1 픽스처(`docs/specs/regime-link-messaging/examples/`) + 백엔드 `port/message` record + 워커 `regime_link/contracts.py`. 같은 MR | 양쪽 계약 테스트가 같은 픽스처를 round-trip. M1은 문서 상수 대조, M5에서 Java 상수 대조 통과 |
| **M2** | WK | `slicer.py` — d0~d63 잘라내기·63/21 요약·손익 기여·KOSPI 참고. R3 구간 산식·실제 체결 입력 사용 | 합성 픽스처 수기 대조 일치. 미완성 구간에서 `completedTradingDays`만큼만. 수익률·하락 폭 연환산 없음, 회전율 연환산 단위 명시. 워밍업 부족이면 계산하지 않음 |
| **M3** | WK | `consumer.py`·`rabbitmq.py`·`regime_link_main.py` — 수신·검증·FINISHED·ACK 순서 | 계약 오류 → DLQ, 계산 실패 → FINISHED(FAILED)+ACK, 발행 실패 → requeue. 백테스트 소비자 테스트 무변경 |
| **M4** | DB·BE | 마이그레이션 + `regimelink` 도메인: `AvailabilityService`(5단계·필터 키), 010 읽기 리포지토리 + ⚠ 픽스처 대역, `GET /regime-links/availability` | 계약 예시와 응답 일치. 표시 후보 밖에서 채우지 않음. 21 미만 사례가 제외 수에 듦. `ArchitectureTest` 통과 |
| **M5** | BE | `POST /regime-link-searches`(목록 확정·변경 기록)와 `POST /regime-links`(로그인 확인, 분석 기간 밖 확인 요구, 004 접수 호출), 잘라내기 작업 조립·발행, 이벤트 반영, `GET /regime-links/{id}`, `retry`, 004 COMPLETED 후처리 연쇄 | 익명 POST가 401. `confirmFullPeriod` 없이 기간 밖이면 계산 0건. 같은 (실행×사례×시나리오) 재요청이 새 작업을 만들지 않음. FRESH_CASH 실행 완료 뒤 링크가 COMPLETED |
| **M6** | FE | `/regime-link/start`(009 진입) + 003 분석 기간 하한 제약 + 분석 완료 후 복귀 | case를 들고 002→003→분석→`/regime-link?base&case`로 돌아옴. 하한 밖 기간 선택 불가와 한 문장 이유 |
| **M7** | FE | `/regime-link` — 분야 선택, 검색 결과에 가용성 표시(제외 수·비교 가능 기간·전체 탐색 전환), 사례의 전략 결과 영역(63 기본·21 상세·진행 상태·실패 자리·현금 시작 토글·전체 기간 재계산 확인·KOSPI 두 값·역사 탐색 표시) | 금지 표현 정적 검사 0건. 유지율 화면 0건. 로그인 안내 후 같은 사례 복귀. **010 M7 이후** |
| **M8** | DOCS | quickstart S1~S9 실행 기록, `docs/specs/regime-link-*` README, 003·005 후속과 014 합의 반영 확인 | 기록이 남고, 미해결 의존성과 합의 완료 항목이 구분됨 |

**M1·M2를 먼저 두는 이유**: 011의 숫자는 전부 `slicer.py`에서 나온다. 화면과 API는 그것을 옮기는
일이다. 계약(M1)이 먼저 있어야 백엔드와 워커가 같은 픽스처로 따로 진행할 수 있다.

**M4·M5가 010 M6에 의존하는 방식**: 010 표를 읽는 리포지토리는 인터페이스이고, 010 표가 dev에
없는 동안은 `infrastructure/fixture/`의 대역이 010 계약 예시(`regime-search-api.md` §1·§3)의 값을
돌려준다. 010 M6이 머지되면 대역 디렉터리를 지우고 JPA 구현으로 바꾼다(R8). **011 마이그레이션은
010 표를 만들지 않는다.**

## 위험과 그에 대한 대비

| 위험 | 대비 |
|---|---|
| 010 M6·M7이 늦어 011 화면을 실제 검색 위에서 못 본다 | 판정은 010 없이 픽스처로 검증되며 계산 계약은 DEP-004-011 입력 검증을 먼저 통과해야 한다. FE 묶음만 010 뒤로 순서를 둔다(R8) |
| 004 저장 결과만으로 개별 체결·청산 비용을 얻을 수 없다 | DEP-004-011 입력 대응·독립 대조를 M1·M2 선행으로 둔다. 누락 입력을 추정하지 않으며 필요하면 004 계약 확장을 먼저 합의한다 |
| 워밍업·상장일 판정이 004 접수 검증과 어긋난다 | `MarketMetadataPort`의 원천 메타데이터를 사용하며 기존 실행 effectiveStart를 원천 워밍업으로 쓰지 않는다. 다른 산식을 만들지 않는다 |
| 메시지에 담은 시계열 사본이 커진다 | 사후 구간 ±1거래일만 담는다(≤65행). 종목은 대응표 상한(섹터 수)으로 묶인다. 상한 초과 시 작업을 거절하고 `FAILED(INPUT_TOO_LARGE)`로 표시 — 조용히 자르지 않는다 |
| 사용자가 현금 시작을 사례 10개에서 모두 켜 004 대기열이 찬다 | 004의 `EXECUTION_LIMIT`(429)을 그대로 화면에 보이고 011이 자체 상한을 만들지 않는다(원칙 IX). 동일 버전 재사용으로 중복은 없다 |
| 014 로그인 경계 | 2026-09-20 사용자 확인으로 소유자 합의 완료. 기본 진단·검색·공개 결과 조회와 계산 요청·개인 기록 저장·보호 진입을 나눠 양쪽 spec에 반영했다 |
| 003이 `case` 제약을 모른다 | entry-handoff §3이 003 화면의 변경 1건을 정의한다. 003 spec FR-009 문구 갱신은 사람 판단(spec 소유자) |
| 결과 화면(005 FE) 없이 `/regime-link?base=`로 들어올 길이 없다 | 005 FE 진입 버튼이 생기기 전에는 quickstart S5가 주소를 직접 열어 검증한다 |
| 금지 표현이 컴포넌트에 스며든다 | 010과 같은 정적 검사(R11). 대상 파일은 디렉터리를 훑어 모은다 |

## 교차 spec 후속 (합의 완료와 미해결 의존성 구분)

| spec | 어긋남 | 011의 처리 |
|---|---|---|
| 003 FR-009 | 시장 흐름 경로의 분석 기간 하한(사례 사후 구간 포함) 규칙 없음 | entry-handoff §3으로 화면 동작만 정의. spec 문구는 003 소유자 |
| 005 FR-018 | 넘기는 값이 "전략" 단수, 비교 대상 토큰 언급 없음 | entry-handoff §1이 `compare`를 정의. 005 문구 갱신은 005 소유자 |
| 014 FR-010·011 | 011 보호 동작의 예외 | 소유자 합의 완료(2026-09-20 사용자 확인), 문구 반영. 구현·테스트는 011 묶음에서 수행 |
| 010 계약 §3 | `postPeriod.available=false` 고정 | 011은 010 응답을 바꾸지 않고 자기 엔드포인트로 붙인다. 010이 `postPeriod`에 링크 주소를 넣을지는 010 소유자 |

## Complexity Tracking

> Constitution Check에 정당화가 필요한 위반이 없다. 이 절은 비운다.

검색 변경 처리는 research R12·REST §1b를 따른다. M5는 검색 기록 POST, M7은 목록 확정 시 호출을 구현한다. 사례 계산 POST에서는 기존 searchId만 참조한다. 익명 검색은 GET과 현재 탐색 흐름 내 변경 표시를 사용한다.
