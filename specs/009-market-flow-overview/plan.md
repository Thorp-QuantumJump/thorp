# Implementation Plan: 시장 흐름 개요

**Branch**: `009-market-flow-overview` (spec 식별자; 실제 구현은 작업 묶음별 Jira Task·브랜치·MR) | **Date**: 2026-09-16 | **Spec**: [spec.md](spec.md)

**Input**: `specs/009-market-flow-overview/spec.md`(2026-09-16 clarify 반영판)

## Summary

시장 흐름 기본 화면과 분야 상세를 만든다. `backend/`에 읽기 전용 `marketflow` bounded context를 더해
7개 분야의 변화 요약과 분야별 지표 표시 항목을 `/api/market-flow`로 제공하고, `frontend/`에
`/market-flow` 화면을 만들어 기존 `/home` 목업 화면을 대체한다.

009는 지표 자료를 소유하지 않는다. 008이 소유한 지표 관측값·기준월·자료 버전을 포트로 읽어 화면이
그대로 그릴 수 있는 읽기 모델로 바꾸는 것까지가 범위다. **008은 다른 팀원이 병렬로 진행 중이고 아직
코드에 없다.** 두 작업이 서로를 기다리지 않도록 포트 뒤에 예시 자료 fixture 어댑터를 두고, 예시 자료로
동작하는 분야는 화면에 예시임을 표시한다(008 FR-022, 009 FR-014). 합쳐질 때 바뀌는 것은 어댑터
구현체 하나이며, **포트 시그니처는 확정 전에 008 담당자와 맞춘다**([research.md](research.md) R2 합류 위험).

**작업량의 상당 부분은 새 화면을 더하는 것이 아니라 기존 화면을 걷어내는 것이다.** 지금 '시장 흐름'
메뉴가 가리키는 `/home`은 spec 작업을 시작하기 전에 만든 화면이라 spec을 따르지 않는다. 회귀가 아니라
예정된 교체 대상이다. `MarketRegimeHero`가 "지금은 조심하되, 완전히 나쁜 시장은 아닙니다."라는 경제
전체 판정을 띄우고(FR-003·SC-002·D08) `StrategyRecommendationSection`이 전략을 추천한다(원칙 III).
무엇을 걷어내고 무엇을 남기는지는 [research.md](research.md) "현재 코드 상태 조사"와 tasks의 T023에 있다.

## Technical Context

**Language/Version**: Java 21(backend), TypeScript 5 + React 19(frontend); 문서 계약은 OpenAPI 3.1과 JSON

**Primary Dependencies**: Spring Boot 3.5.4, Spring Web, Validation, Data JPA, Actuator, Jackson / Vite, React Router

**Storage**: PostgreSQL 16. 읽기 모델 테이블 하나와 Flyway migration 하나를 더한다. 지표 관측값 저장소는 008 소유이며 009는 만들지 않는다

**Testing**: JUnit 5, Spring Boot Test, MockMvc, ArchUnit, Testcontainers PostgreSQL 16, 고정 `Clock` / Vitest, Testing Library

**Target Platform**: 기존 Linux 컨테이너의 단일 backend 인스턴스와 데스크톱 브라우저 우선 한국어 SPA

**Project Type**: 모노레포 웹 서비스. backend bounded context 하나와 frontend 라우트 하나

**Performance Goals**: 화면 진입 후 2초 이내 7개 분야 요약 표시(SC-007). 백엔드는 17개 지표 × 120개월 fixture와 Testcontainers PostgreSQL 16에서 30회 warm-up 후 100회 측정한 `/market-flow/fields` p95를 기록한다

**Constraints**: 경제 전체를 하나의 점수·등급·신호등으로 요약하는 표시 금지(FR-003). 지표 값의 표준화 점수·거리 표시 금지(FR-007). 자료 '지연' 판정 금지(FR-011, 2026-09-16 clarify). 섹터 선택이 경제환경 표시·사례 순위를 바꾸지 않음(FR-016). 자유 생성 문장·LLM 호출 금지. 008 PD-11과 010 PD-08을 앞당겨 결정하지 않음

**Scale/Scope**: 7개 분야, 17개 지표, 지표당 최근 3개월 경로. 화면 2개(기본·분야 상세)와 읽기 전용 API 2개. 이 수치는 008 FR-001이 고정한 값이며 009가 늘리지 않는다

## Constitution Check

*GATE: Phase 0 전 및 Phase 1 설계 후 재검사 완료.*

| 원칙 | 판정 | 설계 근거 |
|---|---|---|
| I. 전략 진단이 중심 | PASS | 시장 흐름은 전략을 정하지 않은 사용자의 진입 화면이고(D02), 전략 비교로 가는 진입점만 제공한다. 전략 추천 영역은 제거 대상이다. |
| II. 첫 화면 단순성 | PASS | 분야 이름과 짧은 요약만 보이고 자료 공급원·API 이름을 고르게 하지 않는다. 계약에 종합 점수 필드가 없다. |
| III. 진단이지 추천이 아님 | PASS | 변화 요약은 등록된 틀로만 만들고 판정·전망·투자 시사점 어휘를 금지 어휘 검사로 막는다(R8). 기존 `StrategyRecommendationSection`을 시장 흐름에서 뺀다. |
| IV. 같은 조건 비교 | PASS | 섹터를 요청 파라미터에서 아예 받지 않아 경제환경 표시가 섹터에 따라 갈릴 수 없다(계약 설계). |
| V. 당시 정보만 사용 | PASS | '최신 개정자료를 이용한 역사 비교'를 고정 문구로 싣고 당시 발표값 재현으로 표현하지 않는다(FR-012). |
| VI. 없는 결과를 만들지 않음 | PASS | 결측은 null로 통과시키고 보간하지 않는다. 3개월 경로가 없으면 문장을 만들지 않는다. 자유 생성 문장을 쓰지 않는다. |
| VII. 탐색과 검증 구분 | PASS | 009는 순위·성과를 만들지 않는다. 검색은 010, 전략 비교는 011이 소유한다. |
| VIII. 재현할 수 있게 기록 | PASS | 읽기 모델 PK에 등록표 버전·자료 버전을 넣고 서술 규칙 버전을 응답에 싣는다. 같은 버전 조합이면 같은 문장이 나온다. |
| IX. 한도는 실측 | PASS | SC-007은 측정 절차를 quickstart S5에 두고, 목표 미달 시 캐시를 더 얹기 전에 시간 사용처부터 기록한다. |
| X. 이용 조건 준수 | PASS | 지표 표시 영역에 출처·작성기관을 필수 필드로 두고(FR-015), 참고 정보 영역에 '한국거래소 통계정보'를 표시한다. |
| Git·Jira 추적 단위 | PASS | 작업 묶음 4개로 나누고 각 묶음을 Jira Task·브랜치·MR 하나에 대응시킨다. 아래 "작업 묶음" 참조. |

**Post-design re-check**: Phase 1 산출물로 재검사했다. `contracts/openapi.yaml`에 점수·등급·신호등 필드가
없고, `STALE`·`DELAYED` 상태가 없으며, 섹터 파라미터가 없다. `data-model.md`의 `MarketFlowField`에는
분야 전체를 좋고 나쁨으로 요약하는 필드가 없고 `SectorLinkState`는 서버에 저장되지 않는다.
`AfterTgRelease`는 `path`와 서술 계산 양쪽에서 배제된다. 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/009-market-flow-overview/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── openapi.yaml
│   └── examples/
│       ├── fields-ready.json
│       ├── fields-example-data.json
│       ├── fields-collection-failed.json
│       └── field-detail-ready.json
├── checklists/
└── tasks.md                 # /speckit-tasks에서 생성
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/ssafy/thorp/marketflow/
│   ├── presentation/
│   │   ├── controller/          # MarketFlowController (/market-flow)
│   │   └── response/
│   ├── application/
│   │   ├── service/
│   │   ├── result/
│   │   ├── repository/
│   │   └── port/                # MacroIndicatorSnapshotPort
│   ├── domain/
│   │   ├── model/               # 분야·지표 표시 항목·서술 규칙
│   │   └── vo/                  # 방향, 지속 길이, 기준월
│   └── infrastructure/
│       ├── persistence/         # 읽기 모델 조회·적재
│       └── example/             # 예시 자료 fixture 어댑터 (008 구현 전까지)
├── src/main/resources/
│   ├── db/migration/V20260916xxxxxx__market_flow_field_summary.sql
│   └── marketflow/macro-indicators-example-v1.json
└── src/test/java/com/ssafy/thorp/marketflow/

frontend/
├── src/pages/MarketFlowPage.tsx
├── src/components/marketflow/     # 분야 요약 카드, 분야 상세, 상태 표시, 섹터 선택
├── src/api/marketFlow.ts
├── src/types/marketFlow.ts        # 계약과 1:1
└── src/marketflow/sectorLink.ts   # sessionStorage 보관

docs/
└── specs/market-flow-api.md       # 외부 계약 요약 (market-api.md·market-news-api.md와 같은 자리)

worker/     # 변경 없음: 백테스트 실행 전용
data/       # 변경 없음: KRX 오프라인 파이프라인
infra/      # 변경 없음
```

**Structure Decision**: HTTP 조회와 영속화를 이미 소유하는 `backend/`에 `com.ssafy.thorp.marketflow`
bounded context를 더한다. 기존 `market` 도메인은 001의 시장 자료 조회를 소유하므로 확장하지 않는다
(R3). 화면은 `frontend/src/pages/MarketFlowPage.tsx` 라우트 하나로 두고, 섹터 선택 상태와 순수 규칙은
014가 `src/auth/`를 쓴 방식대로 `src/marketflow/`에 모아 화면 없이 단위 테스트할 수 있게 한다
(frontend/CLAUDE.md 폴더 규칙).

## Design Decisions

근거와 버린 선택지는 [research.md](research.md)에 있다. 여기에는 결론만 옮긴다.

- **서술 대상 지표 선정(R1)**: 분야 안에서 ① 방향 지속 길이 → ② 같은 단위끼리의 변화 폭 →
  ③ 등록표의 분야 내 고정 순서로 가른다. 최대 2개. 단위가 다른 지표의 변화 폭을 직접 비교하지 않고
  표준화 점수도 쓰지 않는다. 010 PD-08의 척도 선택을 앞당기지 않기 위해서다.
- **008 의존(R2)**: `MacroIndicatorSnapshotPort` 뒤에 예시 자료 fixture 어댑터를 둔다. 008이 저장소를
  만들면 어댑터 구현체만 바뀐다.
- **배치(R3)**: `marketflow` 새 bounded context, 외부 경로 `/api/market-flow`.
- **화면 경로(R4)**: `/market-flow` 신설, `/home`은 영구 리다이렉트. `MarketRegimeHero`·
  `StrategyRecommendationSection`·`SimilarMarketPanel`을 시장 흐름에서 제거한다.
- **섹터 유지(R5)**: `sessionStorage` 키 하나. `try/catch`로 감싸고 실패해도 화면을 막지 않는다.
- **상태 모델(R6)**: `READY` / `EXAMPLE` / `EMPTY` / `COLLECTION_FAILED` 넷, 전부 HTTP 200.
  지연 판정 상태는 두지 않는다.
- **성능(R7)**: 분야 요약은 등록표·자료 버전이 바뀔 때만 만들어 두고 조회는 읽기 모델에서 한다.
- **문장 생성(R8)**: 결정론적 formatter와 `market-flow-narrative-v1` 버전. 금지 어휘 검사를 단위
  테스트로 둔다.

## 작업 묶음

constitution "작업 묶음 = 1 Branch = 1 MR"의 세 조건(단일 목적 / 독립 구현·검증·롤백 / 한 번에 읽히는
범위)에 맞춰 네 묶음으로 나눈다. Jira 번호는 브랜치를 만들 때 발급한다.

| 묶음 | 제목 | 목적 | 주요 대상 | Jira |
|---|---|---|---|---|
| WB01 | `[BE] 지표 스냅샷 포트와 예시 자료 어댑터` | 008 자료를 읽는 경계를 세우고 008 구현 전에도 값이 흐르게 한다 | `application/port`, `infrastructure/example`, fixture JSON, 읽기 모델 마이그레이션 | 미발급 |
| WB02 | `[BE] 변화 요약 규칙과 시장 흐름 조회 API` | 분야 요약·분야 상세를 계약대로 제공한다 | 서술 formatter와 금지 어휘 검사, 선정 규칙, `MarketFlowController`, 상태 판정 | 미발급 |
| WB03 | `[FE] 시장 흐름 화면 교체와 분야 요약·상세` | 목업 진단 화면을 걷어내고 spec에 맞는 화면을 세운다 | `MarketFlowPage`, 분야 카드·상세, 상태 표시, `/home` 리다이렉트, `MarketRegimeHero`·`StrategyRecommendationSection`·`SimilarMarketPanel` 제거 | 미발급 |
| WB04 | `[FE] 섹터 연결과 참고 정보·연계 진입점` | 섹터 선택과 바깥 화면으로 나가는 길을 붙인다 | `sectorLink` 세션 보관, 섹터 선택 UI, KOSPI 참고 정보, '비슷했던 때 보기'·'이 기간으로 전략 비교' 진입점, 뉴스 영역 연결 | 미발급 |

**왜 이렇게 잘랐는가**

- WB01과 WB02를 나눈 이유는 검증 방식이 다르기 때문이다. WB01은 포트 계약과 fixture 적재를, WB02는
  문장 생성 규칙과 HTTP 계약을 검증한다. 합치면 한 MR에서 "자료를 어떻게 읽는가"와 "문장을 어떻게
  만드는가"를 동시에 봐야 한다.
- WB03과 WB04를 나눈 이유는 롤백 경계다. WB03은 기존 화면을 걷어내므로 되돌릴 일이 생길 수 있고,
  WB04는 바깥 spec(010·011·012·001)과 이어지는 부분이라 그쪽 사정으로 막힐 수 있다. 섞으면 한쪽
  때문에 다른 쪽을 되돌리게 된다.
- WB03은 제거와 신설을 한 묶음에 담는다. 제거만 먼저 머지하면 시장 흐름 메뉴가 빈 화면이 되어
  묶음 안에 미완성 상태가 남는다(조건 3 위반).
- 순서는 WB01 → WB02 → WB03 → WB04다. WB03은 WB02의 계약이 머지된 뒤 시작하고, 그 전에는
  `contracts/examples/`의 예시 JSON으로 화면을 먼저 짤 수 있다.

범위가 달라지면 그 사유와 새 묶음 구성을 이 절 또는 해당 MR 본문에 남긴다.

## Complexity Tracking

신규 서비스·브로커·검색 엔진·LLM을 도입하지 않는다. 새로 만드는 것은 bounded context 하나, 읽기 모델
테이블 하나, 화면 라우트 하나다.

분산 처리(Worker·Spark)는 쓰지 않는다. 7개 분야 × 17개 지표 × 3개월은 요청마다 다시 계산해도 작은
양이고, 그마저도 읽기 모델로 미리 만들어 둔다. 루트 CLAUDE.md의 "빅데이터 기술을 먼저 선택하지
않는다"를 따른다.

| 판단 | 왜 필요한가 | 더 단순한 대안을 버린 이유 |
|---|---|---|
| 읽기 모델 테이블 하나 추가 | SC-007(2초)과 원칙 VIII(재현) | 요청마다 계산하면 같은 버전에서 같은 문장이 나오는지 보장할 자리가 없다 |
| 예시 자료 fixture 어댑터 | 병렬로 진행 중인 008과 서로를 기다리지 않기 위해 | 008 완료를 기다리면 두 작업의 일정이 묶인다. 대신 포트 시그니처를 008 담당자와 맞추는 일이 남는다 |
