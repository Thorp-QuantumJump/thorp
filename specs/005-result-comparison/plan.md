# Implementation Plan: 결과 이해·비교

**Spec 디렉터리**: `005-result-comparison` | **Date**: 2026-09-16 | **Spec**: [spec.md](spec.md)

> 구현 Task의 Git 브랜치는 Jira Task마다 `{type}({position})/{JIRA_NUMBER}-{description}` 형식으로 따로 만든다.
> 브랜치 이름은 아래 "작업 묶음 구성"에 티켓 번호와 함께 적는다.

**Input**: Feature specification from `/specs/005-result-comparison/spec.md`

**선행 작업**: clarify [S15P21E205-262](https://ssafy.atlassian.net/browse/S15P21E205-262) · branch `docs(fe)/262-spec-005-clarify` (2026-09-16 dev 병합, `c1bccf3`). 세 세션에 걸쳐 13개 항목을 좁혔고 그 결정이 spec의 Clarifications에 있다.

**이 문서의 작업**: [S15P21E205-265](https://ssafy.atlassian.net/browse/S15P21E205-265) · branch `docs(fe)/265-spec-005-plan`.

## Summary

분석이 끝난 결과를 사용자가 이해하고 둘을 견주는 화면이다. 값은 [004](../004-backtest-execution/spec.md)가 계산해 저장한 것을 그대로 표시하며, 이 spec은 계산하지 않는다.

**이 화면은 004의 결과를 읽기만 하는 얇은 층이 아니다.** clarify에서 두 가지가 더해졌다.

1. **비교 대상 결과가 없으면 004에 실행을 요청한다.** 결과 화면이 실행을 만들어 내는 주체가 되었다. 요청 조건은 기준 결과에서 그대로 가져오고, 동시 실행 상한은 004의 PD-06이 관리한다.
2. **결과가 주소를 가진 공개 자원이 되었다.** 로그인하지 않은 사용자도 주소로 결과를 다시 열고, 그 주소는 추측할 수 없어야 하며, 비로그인 결과는 유효 시간이 지나면 사라진다.

두 번째가 이 plan의 가장 큰 일이다. 지금 004의 조회 경로는 요청자 인증을 요구하고(`CurrentRequester.requireRequesterId`) `backtest_request.requester_id`는 NOT NULL이라, **현재 코드 상태로는 결과 화면을 어떤 방식으로도 띄울 수 없다.** 004 research R-12가 "인증·006 spec에서 익명을 허용하기로 하면 컬럼을 nullable로 완화한다"고 미리 열어 둔 경로를 따라 이 완화를 수행하는 것이 선행 작업이다([research.md](research.md) R-02).

화면 쪽은 저장소에 이미 있는 차트 시스템(`frontend/src/components/charts/`, @visx + d3)을 쓴다. 기존 `BacktestPage`는 목 데이터(`@/data/backtestMock`)를 그리는 DEMO 화면이라 이 spec의 결과 화면과 별개이며, 이 작업에서 대체하지 않는다.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5 / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5, Gradle / Vite 6, react-router-dom 7, @visx(shape·scale·grid·responsive), d3-array, d3-shape, axios, Vitest, Testing Library

**Storage**: 004의 `backtest_request`에 공개 토큰과 만료 시각 두 컬럼을 더하고 `requester_id`를 nullable로 완화한다(Flyway 새 버전). 새 테이블은 만료로 지운 토큰의 묘비 `expired_public_token`(토큰 SHA-256 해시·만료 시각) 하나다 — FR-021이 요청과 토큰을 지우라고 하고 Edge Case가 만료 주소에 "남아 있지 않다" 안내를 요구해서, 지운 뒤에도 410을 주려면 원문 없이 만료 사실만 남겨야 한다(2026-09-17 T087, [research.md](research.md) R-03). 결과·시계열·거래 내역은 004가 저장한 것을 그대로 읽는다.

**Testing**: backend `./gradlew spotlessApply build`(ArchUnit 포함), frontend `npm run lint && npm run build && npm run test`. 계약 검증은 003·004가 쓰는 방식을 따라 `docs/specs/` 아래 응답 예시를 픽스처로 두고 실제 응답과 대조한다.

**Target Platform**: 웹 브라우저(데스크톱 우선, 좁은 화면에서 세로로 쌓여 읽힘) + Spring Boot 서버

**Project Type**: 웹 애플리케이션 (frontend + backend)

**Performance Goals**: 정하지 않는다. 004의 실행 측정(PD-06)이 나온 뒤 같은 기준으로 정한다([research.md](research.md) R-11). 다만 첫 화면이 받는 자료를 `summary`와 시계열로 한정하고 상세는 열 때 받는다(SC-001).

**Constraints**:

- 첫 화면 구성(숫자 3개·그래프 1개·문장 1개)은 constitution 원칙 II가 MUST로 고정한 값이다. 음영·이름표는 곡선에 겹치는 표시로 다루어 그래프 수를 늘리지 않는다.
- 해석 요약은 고정 문장 틀에서만 나온다. 생성 모델은 쓰지 않는다(FR-006, PD-22 미확정).
- PD-23(비로그인 결과 유효 시간)이 미확정이므로 값은 설정으로 빼고 장치만 먼저 만든다.
- 004의 `summary`는 최대 하락의 **값**만 주고 **구간**을 주지 않는다. 음영은 `/timeseries`의 `drawdown` 계열에서 찾아 쓰되 `summary.maxDrawdown`과 어긋나면 그리지 않는다([research.md](research.md) R-05).

**Scale/Scope**: 화면 1개(결과), 한 그래프에 최대 2개 결과, 시계열 한 계열 약 1,900점(7년 일봉), 거래 내역 수백~수천 행(페이지 조회), 상세 패널 항목 10종

## Constitution Check

*GATE: Phase 0 연구 전에 통과해야 하고, Phase 1 설계 후 다시 확인한다.*

| 원칙 | 이 기능에서의 의미 | 판정 |
|---|---|---|
| I. 중심 약속은 전략 진단이다 | 전략 진단 흐름의 마지막 단계다 | 통과 |
| II. 첫 화면은 단순하게 | 첫 화면은 숫자 3개·그래프 1개·요약 1문장·조건 한 줄이고 그 외는 요청해야 열린다(SC-001). 받아 오는 자료도 같은 범위로 한정한다 | 통과 — 음영과 이름표는 같은 곡선 위 표시라 그래프 수를 늘리지 않는다 |
| III. 진단이지 추천이 아니다 | 해석 요약은 고정 틀에서 골라 값만 채우고, 금지 어휘를 단위 테스트로 막는다(FR-006·FR-016, SC-004). 생성 모델 해설은 PD-22 확정 전까지 만들지 않는다 | 통과 |
| IV. 비교는 같은 조건에서만 | 두 결과는 같은 섹터 후보·기간·금액·비용·정보 이용 기준을 쓰고, 기간이 어긋나면 겹쳐 그리지 않는다(FR-005, SC-003) | 통과 |
| V. 당시에 알 수 있던 정보만 쓴다 | 이 화면은 계산하지 않으므로 해당 규칙은 004가 지킨다. 005는 `versions`를 조건 한 줄에 표시한다 | 해당 없음(표시만) |
| VI. 없는 결과를 만들어내지 않는다 | 계산 제한 항목에 0이나 임의 값을 넣지 않고(FR-009), 취소로 지표가 없으면 숫자 자리를 채우지 않으며(FR-013), 만료된 주소에 빈 결과 화면을 그리지 않는다(SC-008) | 통과 |
| VII. 탐색과 검증을 구분한다 | '탐색 결과 / 미사용 검증 결과 / 미검증'을 결과 옆에 구분 표시한다(FR-012). 판정 기준은 PD-12(007 소유)이며 확정 전 모두 '미검증'이다 | 통과 |
| VIII. 재현할 수 있게 기록한다 | 결과는 006에 보존되고 조건 한 줄에 자료·규칙·비용 버전을 표시한다(FR-003·FR-019) | 통과 |
| IX. 실행 한도는 실측으로 정한다 | **이 화면이 004에 실행을 요청하게 되었다.** 한도는 004의 PD-06이 관리하고 005는 취소로 대신 조절하지 않는다(FR-004). 응답 목표도 실측 전에는 정하지 않는다 | 통과 — 아래 주의 참고 |
| X. 자료는 이용 조건 안에서만 쓴다 | KRX 자료를 쓰는 화면에 '한국거래소 통계정보' 출처를 표시한다(FR-020) | 통과 |

**원칙 IX에 관한 주의**: 비교 대상을 고를 때마다 실행이 만들어지고, 앞선 실행을 취소하지 않기로 했다(FR-004). 004가 동일 버전 재사용·합류로 중복을 걸러 주지만, 사용자가 다섯 전략을 훑으면 서로 다른 조건의 실행이 최대 네 건 쌓인다. 004의 대기열 상한(`429 EXECUTION_LIMIT`)에 걸릴 수 있으므로, **화면은 그 429를 "한도에 걸렸다"는 사실과 `retryAfterSeconds`로 사용자에게 보여야 한다.** 이를 tasks의 요구 항목으로 남긴다.

## Constitution 재확인 (Phase 1 설계 후)

설계 산출물([data-model.md](data-model.md), [contracts/result-api.md](contracts/result-api.md), [quickstart.md](quickstart.md))을 만든 뒤 다시 판정했다. 새로 생긴 위반은 없다. 설계가 바꾼 판정은 둘이다.

| 원칙 | 설계가 더한 사실 | 판정 |
|---|---|---|
| II. 첫 화면은 단순하게 | 상세 패널에 001의 대표 상품 정보까지 펼치기로 해 상세의 정보량이 늘었다. 다만 FR-020의 세 층 배치에서 "요청하면 펼치는 정보" 층이고 첫 화면이 받는 자료는 `summary`·시계열로 한정된다 | 통과 |
| VI. 없는 결과를 만들어내지 않는다 | 만료·실패·부분 결과·계산 제한의 네 갈래를 각각 어느 필드에서 읽을지 고정했다([research.md](research.md) R-12). 값이 없을 때 0을 넣는 경로가 설계에 남아 있지 않다 | 통과 |

## 004에 필요한 변경

이 설계는 004 소유 영역 세 곳의 변경을 전제한다. 005가 임의로 고치지 않고 tasks에서 협의 항목으로 올린다. 자세한 내용은 [contracts/result-api.md](contracts/result-api.md) "004에 요청하는 변경"에 있다.

| 항목 | 없으면 생기는 일 |
|---|---|
| `backtest_request.requester_id` nullable 완화 | 결과 화면이 어떤 경로로도 뜨지 않는다. 가장 시급하다 |
| 접수 응답에 `publicToken` 추가 | 화면이 004의 내부 식별자를 들고 다니게 되어 SC-009를 지킬 수 없다 |
| `summary.maxDrawdownWindow` 추가 | 최대 하락 구간 음영을 화면이 계산해야 한다. 임시로 시계열에서 찾되 값이 어긋나면 음영을 생략한다 |
| `analytics`의 상관관계·위험 기여·손실 기여 노출 | FR-007이 상세 패널에 요구하는데 계산·저장만 되고 API 밖으로 나오지 않는다. 그때까지 해당 항목은 제공되지 않음으로 둔다 |
| 참조하는 요청이 없는 계산·결과의 정리 | FR-021 만료 삭제가 004 FR-003의 결과 공유와 부딪히지 않으려면, 만료된 요청이 가리키던 계산은 다른 요청이 참조하지 않을 때만 지워야 한다. 그 판정은 계산 기록을 소유한 004의 일이다. 없으면 005는 요청 행과 토큰만 지우고 계산은 남긴다 |

## 미해결 위반

없다. 다만 다음 두 가지는 이 spec 밖에서 정해져야 하며 그 전까지 기능이 열리지 않는다.

| 항목 | 상태 | 이 plan의 대응 |
|---|---|---|
| PD-22 결과 해석 보조 AI 해설 | 미확정. 기획서에 근거 결정 없음 | 만들지 않는다. spec Out of Scope에 있다 |
| PD-23 비로그인 결과 주소의 유효 시간 | 미확정 | 값은 설정으로 빼고 만료 장치를 먼저 만든다 |

## Project Structure

### Documentation (this feature)

```text
specs/005-result-comparison/
├── plan.md              # 이 문서
├── research.md          # Phase 0 산출물
├── data-model.md        # Phase 1 산출물
├── quickstart.md        # Phase 1 산출물
├── contracts/
│   └── result-api.md    # Phase 1 산출물
├── checklists/
│   └── requirements.md  # spec 단계 산출물
└── tasks.md             # /speckit-tasks 산출물 (이 명령이 만들지 않는다)
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/
├── backtest/                          # 004 소유. 이 작업에서 최소한으로 손댄다
│   ├── domain/                         #   requester_id nullable 완화, public_token 필드
│   ├── presentation/support/           #   CurrentRequester: 요청자 없음을 허용하는 경로
│   └── ...
└── result/                            # 신규. 공개 열람 경계
    ├── presentation/controller/        #   GET /results/{token} 및 하위 자원
    ├── presentation/response/          #   004 응답을 공개용으로 감싼 DTO
    ├── application/                    #   토큰 → 요청 해석, 만료 판정, 만료 정리
    ├── application/repository/         #   묘비 저장소 포트(이 패키지의 유일한 저장소)
    └── infrastructure/persistence/     #   expired_public_token 매핑

backend/src/main/resources/db/migration/   # public_token·만료·requester_id 완화, expired_public_token 마이그레이션

frontend/src/
├── pages/
│   └── ResultPage.tsx                 # /results/:token
├── features/result/                   # 신규
│   ├── summarySentences.ts            #   해석 요약 문장 틀 (검수 대상)
│   ├── ResultSummaryCard.tsx          #   핵심 숫자 3개 + 조건 한 줄
│   ├── ComparisonChart.tsx            #   곡선 2개 + 하락 구간 음영
│   ├── ResultStatusLine.tsx           #   결과 옆 한 문장
│   ├── DetailPanel.tsx                #   '자세히 보기'
│   └── CompareTargetPicker.tsx        #   비교 대상 선택·실행 요청
├── api/result.ts                      # 신규. 공개 결과 조회
└── components/charts/                 # 기존 차트 시스템 재사용
```

**Structure Decision**: 백엔드는 004의 `backtest` 패키지를 그대로 두고 공개 열람을 담당하는 `result` 패키지를 새로 둔다. 004는 인증된 소유자 경로를, `result`는 토큰 기반 공개 경로를 맡는다. 두 경계를 나누는 이유는 004가 이미 구현·검증을 마쳤고 그 계약을 쓰는 곳이 여럿이라, 공개 열람 규칙을 그 안에 섞으면 004의 권한 판정이 복잡해지기 때문이다. 프런트는 화면 하나가 여러 구성 요소로 갈리므로 `features/result/`에 모은다.

## 작업 묶음 구성

constitution이 작업 묶음을 plan에 두라고 요구한다. 아래는 제안이며 티켓 번호는 tasks 단계에서 채운다. 묶음마다 브랜치 하나, MR 하나다.

| 묶음 | 내용 | 파트 | 선행 |
|---|---|---|---|
| A. 공개 열람 경계 | `requester_id` 완화, `public_token`·만료 컬럼과 마이그레이션, `GET /results/{token}` 및 하위 자원, 410 만료 응답 | BE | 없음 |
| B. 결과 첫 화면 | `/results/:token` 라우트, 핵심 숫자 3개·조건 한 줄·해석 요약 문장 틀, 상태 한 문장 | FE | A |
| C. 비교 그래프 | 곡선 2개·하락 구간 음영·색 비의존 구분·좁은 화면 배치 | FE | B |
| D. 비교 대상 선택과 실행 요청 | 대상 선택, 결과 없으면 실행 요청, 기간 불일치 확인, 실패·한도(429) 표시, 비교 주소 | FE | C |
| E. 상세 패널 | '자세히 보기', 거래 내역 페이지 조회, 001 대표 상품 정보 펼치기, 계산 제한 표시 | FE | B |

A가 없으면 나머지가 화면을 띄울 수 없다. B~E는 A 뒤에서 병렬로 갈 수 있으나 C는 B의 화면 골격을 쓴다.

## Complexity Tracking

| 항목 | 왜 필요한가 | 더 단순한 방법을 버린 이유 |
|---|---|---|
| `result` 패키지를 새로 둔다 | 공개 토큰 열람과 소유자 인증 열람은 권한 판정 규칙이 다르다 | 004 컨트롤러에 토큰 분기를 넣으면 이미 검증을 마친 경로에 조건이 늘고, 004가 소유한 식별자 규칙과 005가 요구하는 공개 식별자 규칙이 한 클래스에 섞인다 |
| `backtest_request`에 컬럼 두 개를 더한다 | 공개 주소와 만료가 요청 단위 속성이다 | 별도 테이블로 빼면 요청 수명과 토큰 수명이 갈라져 만료 정리가 두 곳을 봐야 한다 |
| 004의 `requester_id`를 nullable로 완화한다 | 비로그인 실행·열람을 spec이 정했고 014 SC-005가 전략 진단에 인증을 강제하지 않는다 | 시스템 사용자 id 부여는 여러 사람의 요청을 한 주체 아래 섞어 취소 권한 판정을 무너뜨린다. 004 research R-12가 이 완화를 이미 예상해 두었다 |
