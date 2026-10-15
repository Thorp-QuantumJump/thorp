# Thorp

국내 섹터 ETF의 배분 규칙과 설정이 과거의 수익·손실·비용에 어떤 차이를 만들었는지 같은 조건에서 비교하는
전략 진단 서비스입니다.

- 팀: Team Quantum Jump · SSAFY S15P21E205
- 운영 주소: <https://thorp.thisis-joe.com>
- 최상위 원칙: [constitution](.specify/memory/constitution.md) · 기능 범위와 미확정 결정: [specs/README.md](specs/README.md)

사용자가 섹터와 배분 전략을 고르면 Thorp는 과거 KRX 자료로 백테스트를 실행하고, 비용을 차감한 수익률과
가장 크게 줄었던 폭, 거래비용을 같은 조건에서 비교해 보여 줍니다. Thorp는 수익이 가장 높았던 전략을 골라
주지 않습니다. 그 대신 선택한 규칙이 언제 어떻게 움직였는지를 사용자가 이해하도록 돕습니다.

제품 방향과 범위의 원본은 팀 Notion 기획서입니다. 이 README에 적은 구현 상태는 2026-10-13 시점의 dev
브랜치(`26411723`)를 기준으로 합니다.

바로 가기: [현재 구현 상태](#현재-구현-상태) · [시스템 구조](#시스템-구조) ·
[로컬 개발 환경 구성](#로컬-개발-환경-구성) · [문서 지도](#문서-지도) · [배포와 운영](#배포와-운영)

## Thorp가 하는 일

### 풀려는 문제

투자 전략은 이름만으로 비교하기 어렵습니다. 같은 전략이라도 대상 섹터, 리밸런싱 주기, 비용, 분석 기간이
달라지면 결과가 뜻하는 바가 달라집니다. 설정 조합이 많아지면 유리한 사례만 골라 보거나, 조건이 다른 결과를
같은 기준처럼 나란히 놓기도 쉽습니다. Thorp는 다음 세 질문에 답할 수 있도록 분석 절차와 화면을 제공합니다.

1. 같은 조건에서 배분 규칙이나 설정을 바꾸면 결과가 어떻게 달라지는지
2. 전체 수익률뿐 아니라 가장 크게 줄었던 폭과 거래비용은 어떠했는지
3. 경제환경이 지금과 가까웠던 과거 구간 직후에 그 규칙을 따른 포트폴리오가 어떻게 움직였는지

### 접근 방식

- **같은 조건에서만 비교합니다.** 후보 전략은 같은 섹터 후보, 평가 기간, 금액, 비용, 정보 이용 기준으로
  계산합니다. 수수료·세금·평가 기간은 수익을 높이도록 자동으로 조정하는 대상이 아닙니다.
- **진단이며 추천이 아닙니다.** 추천 비중, 주문 버튼, 단일 승자, 사용자 간 순위를 만들지 않습니다. 기본값은
  입력을 돕는 출발점이며 최적값이나 투자 권고가 아닙니다.
- **당시에 알 수 있던 정보만 씁니다.** 리밸런싱 판단에는 판단 시점까지의 자료만 쓰고, 가상 체결은 다음
  거래일에 합니다. 상장 전 구간을 합성 이력으로 채우지 않습니다. 경제환경 검색은 최신 개정자료로 과거
  경제환경을 지금과 비교하는 역사 탐색이며, 당시 발표된 값으로 의사결정을 재현하는 분석이 아닙니다.
- **없는 결과를 만들어 내지 않습니다.** 자료 부족, 계산 실패, 미검증 상태를 0이나 확신 있는 요약으로 바꾸지
  않고, 그 상태를 해당 결과 옆에 그대로 표시합니다.
- **재현할 수 있게 기록합니다.** 분석마다 전략, 파라미터, 비용 조건, 자료 버전, 엔진 버전을 함께 저장합니다.
  설정을 바꾸면 기존 결과를 덮어쓰지 않고 새 분석으로 남깁니다.
- **복잡한 계산은 시스템이 맡습니다.** 사용자는 섹터와 전략을 고르고, 계산은 비동기 Worker가 처리합니다.

제품의 중심 기능은 전략 진단입니다. 시장 흐름의 경제환경 검색과 뉴스는 진단 결과를 해석할 때 참고하는
맥락을 제공하고, 배우기는 입문자가 진단을 시작하도록 돕는 보조 기능입니다(constitution 원칙 I).

## 화면과 기능

상단 메뉴는 전략 진단, 시장 흐름, 배우기, 내 기록 네 개입니다. 대부분의 화면은 로그인 없이 쓸 수 있으며,
로그인이 필요한 곳은 따로 적었습니다. 경로는 [`frontend/src/App.tsx`](frontend/src/App.tsx)를 기준으로 합니다.

### 첫 화면 (`/`)

첫 화면 상단 창은 KOSPI, KOSDAQ, 원/달러 환율, VKOSPI 200 네 지표의 최근 거래일 흐름을 Backend에서 받아
차례로 보여 줍니다. 흐름을 아직 수집하지 못한 지표는 '움직임 예시'라고 밝힌 예시 흐름으로 그리며, 최신값까지
받지 못하면 화면에 내장된 예시 값을 그 흐름의 출발점으로 씁니다. 그 아래의 시장 흐름 소개 창은 시장 흐름
API에서 17개 경제지표와 일곱 분야의 요약을 받아 보여 주고, 받지 못하면 '연결 실패'로 표시합니다. 진단 소개
구역의 곡선은 수치가 없는 장식용 예시 곡선입니다.

### 전략 진단

| 단계 | 경로 | 사용자가 하는 일 |
|---|---|---|
| 투자 대상 고르기 | `/diagnosis/sectors` | 여덟 섹터 가운데 둘 이상을 고릅니다. 비중은 이 화면에서 정하지 않으며, 고른 섹터에는 동일 비중이 초깃값으로 잡힌 채 다음 단계로 넘어갑니다. 대표 ETF 정보, 가격 흐름, 함께 평가할 수 있는 기간, '지금 국내 시장' 지표를 함께 보여 주며, 화면 하단에는 뉴스 구역이 있습니다. |
| 전략 선택과 설정 | `/strategy` | 고정 비중, 동일 비중, 역변동성, 섹터 모멘텀, 최소분산 다섯 전략 가운데 하나를 고릅니다. 고정 비중 전략을 고르면 상세 설정의 '목표 비중' 항목에서 섹터별 비중을 정하며, 합계가 100%가 아니면 그 자리에서 남은 비중(부족·초과)을 보여 줍니다. 기본 설정을 그대로 두거나 등록표 범위 안에서 상세 설정을 바꾼 뒤 '분석하기'를 누를 수 있고, '설정 조합 자동 비교'로 넘어갈 수도 있습니다. |
| 결과 | `/results/:token` | 비용 차감 후 전체 수익률, 가장 크게 줄었던 폭, 거래비용의 세 숫자와 비교 그래프 하나, 요약 한 문장을 먼저 보여 줍니다. 요약, 결과 분석, 설정·근거, 전체 거래 내역, 손실이 커진 때 다섯 탭이 있고, 같은 조건의 결과를 최대 셋까지 KOSPI 참고선과 함께 비교합니다. 실행 중에는 진행 곡선을 실시간으로 그립니다. 결과 주소(공개 토큰)만 있으면 로그인 없이 열립니다. |
| 설정 조합 자동 비교 | `/param-search` | 등록표 범위 안의 설정 조합을 서버가 한 묶음으로 실행합니다. 실행 전에 후보 수와 제외 이유를 확인하고, 진행 확인·취소·실패 후보 재시도를 할 수 있습니다. 화면은 누구나 볼 수 있지만 실행하려면 로그인해야 합니다. |

### 시장 흐름

| 화면 | 경로 | 내용 |
|---|---|---|
| 시장 흐름 | `/market-flow` | 생산, 소비·투자, 대외거래, 노동, 물가·원자재, 금융·주택, 해외 경기의 일곱 분야를 지표의 실제 값·단위·최근 경로로 보여 줍니다. 분야 메뉴 끝의 'Thorp 종합 비교'는 사용자가 골라서 엽니다. 분야 상세 안에서 경제환경이 지금과 가까웠던 과거 사례를 같은 지표 그래프에 겹쳐 볼 수 있습니다. |
| 사례와 전략 비교 | `/regime-link/start`, `/regime-link` | 과거 사례를 고르면 투자 대상 고르기와 전략 선택을 거친 뒤, 그 사례 직후 63거래일(보조 21거래일) 구간에서 후보 전략들을 같은 조건으로 비교합니다. 결과 화면의 '경제환경이 비슷했던 과거 보기'로도 들어올 수 있으며, 계산을 새로 만들 때는 로그인해야 합니다. 사례 화면에는 '당시 뉴스' 영역이 있지만, 저장된 뉴스가 2026-09-16 이후분뿐이라 과거 사례에서는 대개 빈 상태 안내가 보입니다(과거 뉴스 원천은 spec 016, PD-24 미확정). |
| 경제환경 검색 | `/regime-search` | 시장 흐름의 분야 상세에서 쓰는 경제환경 검색을 단독 화면으로 엽니다. 이 화면은 메뉴에 없으며, 내 기록의 '다시 보기'로만 들어올 수 있습니다. |

### 그 밖의 기능

- **뉴스**: 뉴스만 따로 보는 메뉴는 없습니다. 뉴스는 투자 대상 고르기 하단의 뉴스 구역(주요 뉴스, 고른 섹터
  뉴스, 분류별 뉴스)에서 볼 수 있습니다. 기사 본문은 저장하지 않고 제목·매체·발행 시각·원문 링크만 다룹니다.
- **배우기** (`/learn`, `/learn/:articleId`, `/learn/me`): 단계별 커리큘럼 글을 읽고 확인 문제를 풉니다.
  일부 글에는 '이 예시로 전략 진단하기' 버튼이 있으며, 이 버튼을 누르면 글의 예시 섹터가 채워진 투자 대상
  고르기 화면으로 이동합니다. 비로그인 사용자도 글을 읽고 문제를 풀 수 있지만, 읽은 글·경험치·레벨·배지
  같은 학습 기록은 로그인했을 때만 '나의 학습'에 남습니다.
  전략 상세 설정과 배우기 글 본문의 용어 도움말은 같은 설명을 서버에서 받아 보여 줍니다.
- **내 기록** (`/records`, 로그인 필요): 완료한 분석, 자동 비교 묶음, 경제환경 검색 기록을 목록으로 보고,
  상세 보기·이름 변경·삭제·JSON/CSV 내보내기·다시 보기를 할 수 있습니다.
- **계정**: 이메일 인증을 거치는 회원가입(`/signup`), 로그인(`/login`), 카카오·네이버·Google 소셜 가입과 기존
  계정 연결(`/signup/social`, `/auth/social/*`)이 있습니다. 회원정보(`/account/profile`, 로그인 필요)에서
  닉네임을 바꾸거나 학습 기록을 초기화할 수 있습니다. 소셜 로그인 버튼은 서버에 client id와 secret을 모두
  설정한 제공자의 것만 보입니다.
- **배우기 관리** (`/admin/learning`): 메뉴에는 없고 주소로만 들어갑니다. 로그인하지 않았으면 로그인 화면으로
  이동하고, 로그인한 계정이 서버의 관리자 허용 목록에 없으면 404를 받습니다. 이 화면에서 글을 편집하고 원고를
  가져와 미리 본 뒤 게시하거나 단계를 공개할 수 있습니다. 단계 공개는 되돌릴 수 없습니다.
- **화면 맥락 도우미**: 결과 화면의 플로팅 버튼을 눌러, 그 결과에 나온 값에 관해 질문할 수 있습니다. 서버
  설정으로 켜야 버튼이 보이며 기본값은 꺼져 있습니다.

## 현재 구현 상태

아래 표는 코드, 마이그레이션, 각 spec의 `tasks.md`, 검증 기록 문서를 근거로 정리한 상태입니다.
constitution은 **구현 완료**, **사용성 확인**, **효과 입증**을 서로 다른 단계로 보며, 하나를 다른 단계의
근거로 쓰지 않습니다. 표의 '구현'은 코드가 dev에 병합되어 동작한다는 뜻이며, constitution이 말하는
'구현 완료'(§7.1 기능·계산 확인 통과)와 같은 뜻이 아닙니다. 수기 대조나 화면 확인처럼 §7.1에 해당하는 확인이
남은 spec은 '구현(확인 남음)'으로 적고, 남은 확인을 오른쪽 칸에 적었습니다. 17개 spec의 `spec.md` 상태
표기는 모두 Draft로 남아 있습니다.

| 영역 | spec | 구현 상태 | 확인된 범위와 남은 일 |
|---|---|---|---|
| 대표 ETF 자료 | [001](specs/001-etf-market-data/spec.md) | 구현(확인 남음) | 여덟 섹터의 대표 ETF 대응표(`SEM-2026-09-16-001`, PD-01)를 확정했고, KRX OPEN API에서 일봉과 KOSPI를 받아 적재합니다. 여덟 종목이 모두 상장되어 있는 기간은 가장 늦게 상장한 `305720`의 상장일인 2018-09-12부터입니다. 분배금 수기 대조(T016)와 거래정지 처리 규칙(PD-19·20)이 남았습니다. 운영 DB의 적재 기간은 저장소에 기록되어 있지 않습니다. |
| 투자 대상 고르기 | [002](specs/002-sector-weight-selection/spec.md) | 구현 | 섹터 선택, 동일 비중 초깃값, 자료 부족 안내, 하단 뉴스 구역이 동작합니다. 비중 입력과 합계 처리는 2026-09-22에 전략 설정 화면으로 옮겨졌지만, spec 002 본문은 아직 섹터 목록과 비중 입력을 한 화면에 둔다고 적고 있습니다. 사용성 관찰은 하지 않았습니다. |
| 전략 선택·설정 | [003](specs/003-strategy-settings/spec.md) | 구현 | 다섯 전략 카드와 파라미터 등록표에 기반한 상세 설정이 있습니다. spec 003은 파라미터 값과 시작 금액 범위(PD-02)를 2026-09-15 확정으로 적었지만, [specs/README.md](specs/README.md)의 결정 항목 표와 역변동성 계산 규칙의 결정 기록(`decision_ref`)에는 아직 미확정(출발안)으로 남아 있습니다. 사용성 관찰(T074a)이 남았습니다. |
| 백테스트 실행 | [004](specs/004-backtest-execution/spec.md) | 구현 | 다섯 전략의 계산 규칙이 모두 등록되어 있습니다(고정 비중·동일 비중·역변동성 2026-09-11, 최소분산 2026-09-17, 섹터 모멘텀 2026-09-18). 비동기 접수, 취소, 재시도, 진행 중 실행 합류, 완료 결과 재사용, 실행 한도가 동작합니다. 기본 비용 조건 `KR-ETF-2026-09`(PD-07)는 확정했지만, 실행 한도 값(PD-06)은 실측 전 임시값이고 역변동성 규칙 버전 1은 잠정(provisional)으로 표시되어 있습니다. |
| 결과 이해·비교 | [005](specs/005-result-comparison/spec.md) | 구현 | 공개 토큰 결과 화면, 같은 조건 비교, 자세히 보기, 진행 스트림이 있습니다. 비로그인 결과 주소의 유효 시간(PD-23)과 결과 해설(PD-22)은 미확정입니다. 사용성 관찰(T083a)이 남았습니다. |
| 내 기록 | [006](specs/006-my-records/spec.md) | 구현 | 행 메뉴의 '거래 내역' 항목(T054)이 아직 없습니다. 보관 기간(PD-13)이 미확정이라 시간 기반 자동 삭제는 없고, 사용자가 직접 삭제하는 기능만 있습니다. |
| 설정 조합 자동 비교 | [007](specs/007-auto-parameter-search/spec.md) | 구현 | 평가 분할(PD-12)이 정해지지 않아 모든 후보가 `UNVALIDATED`로 표시됩니다. 실행 측정 기록(T117·T126)과 Worker 회귀 확인(T124)이 남았습니다. |
| 경제지표 등록·수집 | [008](specs/008-macro-indicator-registry/spec.md) | 구현(자료 계층) | 일곱 분야 17개 지표를 ECOS·KOSIS·관세청·OECD에서 수집한 뒤 버전을 붙여 발행합니다. 전용 화면은 없고, 시장 흐름과 경제환경 검색이 이 자료를 읽습니다. 2026-09-21에 운영 DB에 전 구간을 적재한 기록이 있으며, 정기 갱신은 사람이 배치를 직접 실행해서 합니다. |
| 시장 흐름 | [009](specs/009-market-flow-overview/spec.md) | 구현 | 일곱 분야와 Thorp 종합 비교, 분야 상세 안의 과거 사례가 있습니다. |
| 경제환경 검색 | [010](specs/010-economic-regime-search/spec.md) | 구현 | 검색 결과는 data 배치가 기준월마다 미리 계산합니다. 종합 비교(모델 `REGIME-V3-EQ.2`)는 교정된 문턱을 적용합니다(PD-09, 2026-09-25 확정). 분야 검색(모델 `REGIME-V1.1`)은 문턱이 미확정이라 가까운 순서만 제공하고 유사성은 판정하지 않습니다. 사용성 관찰(T155)이 남았습니다. |
| 사례와 전략 연결 | [011](specs/011-regime-strategy-link/spec.md) | 구현 | 두 진입 경로와 사례 직후 구간의 전략 비교가 있고, 전용 Worker가 계산합니다. 저장소의 운영 구성([`infra/docker-compose.prod.yml`](infra/docker-compose.prod.yml))에는 이 Worker가 없어서, 그 구성대로 띄운 서버에서는 새 사례 비교 계산이 처리되지 않습니다(spec 011 plan의 미해결 항목). 응답 시간 실측(T089)도 남았습니다. |
| 뉴스 | [012](specs/012-market-news-digest/spec.md) | 구현 | 국내 RSS 7개 채널(경향신문·한국경제·매일경제)을 주 원천으로, GDELT를 보완 원천으로 삼아 15분마다 뉴스를 수집합니다. 매체의 표시 허가 회신(PD-15)은 아직 확인되지 않았습니다. 매체 등록표의 허가 표시는 매체 회신이 아니라 팀이 정한 기록이며, 그 판단의 전제 조건은 [등록표](backend/src/main/resources/news/news-publishers-v1.json)의 `_permission` 설명에 있습니다. |
| 배우기 | [013](specs/013-learning-pages/spec.md) | 구현(확인 남음) | 여섯 단계 27편, 확인 문제, 경험치·레벨·배지, 관리 화면이 있습니다. 2026-10-11 운영에서 여섯 단계를 공개했습니다. 최종 주제 목록(PD-16)은 미확정이라 출발안으로 운영합니다. 수동 화면 확인(T075·T097)과 사용성 확인이 남았습니다. |
| 사용자 인증 | [014](specs/014-user-auth/spec.md) | 구현(확인 남음) | 실제 카카오·Google·네이버 계정으로 하는 확인(T096)이 남았고, 로그인 반복 시도 제한은 정하지 않았습니다. |
| 화면 맥락 도우미 | [015](specs/015-context-assistant/spec.md) | 구현(확인 남음), 기본 꺼짐 | 1차 범위는 결과 화면 한 곳입니다. 서버가 읽은 결과 값으로 숫자를 채우고, 외부 LLM은 설명만 합니다. 자동 품질 기준은 충족했지만, 두 명의 독립 채점(T053)이 끝나야 기능을 켭니다. 데스크톱과 모바일에서 사람이 하는 배치 확인(T046)도 남았습니다. |
| 투자 대상 그래프의 당시 뉴스 | [016](specs/016-historical-news/spec.md) | 명세만 있음 | 향후 확장이며 착수 시점이 정해지지 않았습니다. 과거 뉴스 원천(PD-24)을 확정한 뒤 착수합니다. |
| 진단 화면 용어 도우미 | [017](specs/017-diagnosis-term-assistant/spec.md) | 일부(Backend 공통 경계) | Backend에 진단 두 화면 종류와 그 요청 검증·한도 키·검증 정책·응답 필드 같은 공통 경계(Phase 2)만 만들었습니다. 용어 근거, 화면 연결, 품질 관문 작업에는 아직 착수하지 않았으므로, 이 기능은 사용자에게 보이지 않습니다. |

**사용성 확인**: PD-14의 2026-09 회차 사용성 관찰은 아직 하지 않았습니다. 002·003·005·010의 관찰 기록
문서는 빈 틀이고([진행 서식](docs/guides/usability-observation-2026-09.md)), 배우기의 사용성 확인도 수행하지
않았습니다.

**효과 입증**: 자동 비교 후보는 평가 분할이 정해지지 않아 모두 `UNVALIDATED`로 표시됩니다. 경제환경 종합
비교의 문턱은 사전에 고정한 기준으로 교정했습니다(PD-09). 이 판정은 기준을 충족한 과거 사례가 경기순환
국면(확장·수축)을 더 자주 맞히는지를 본 것이며, 표본이 작아 유의성 검정 없이 '탐색 결과'로 기록했습니다.
따라서 이 판정은 전략 성과가 좋아진다는 근거가 아닙니다.

**재현성**: 규칙 스냅샷 6개의 결과 해시가 Windows x86_64, linux/amd64 이미지, linux/arm64 에뮬레이션에서
같았다는 기록이 있습니다(2026-09-19, spec 004 T030). 운영 서버, 실제 ARM 장비, 실제 자료로는 비교하지
않았습니다.

검증 범위와 보증하지 않는 것은
[백테스트 실행 검증 기록](docs/guides/backtest-execution-verification-2026-09.md),
[ETF 자료 검증 기록](docs/guides/etf-market-data-verification-2026-09.md),
[경제지표 검증 기록](specs/008-macro-indicator-registry/verification-2026-09-22.md),
[배우기 검증 기록](specs/013-learning-pages/validation.md)에서 확인할 수 있습니다. 각 기록은 작성 날짜의 상태를
적은 것입니다.

## 시스템 구조

```mermaid
flowchart LR
    browser["브라우저<br/>React SPA"] -->|"/api"| backend["Backend<br/>Spring Boot"]
    backend --> db[("PostgreSQL")]
    backend -->|"작업 발행"| mq["RabbitMQ"]
    mq -->|"backtest.jobs<br/>backtest.jobs.bulk"| worker["백테스트 Worker<br/>single, flex"]
    worker -->|"backtest.events"| mq
    mq -->|"regime-link.jobs"| rlworker["사례 연결 Worker"]
    rlworker -->|"regime-link.events"| mq
    mq -->|"이벤트 소비"| backend
    worker -.->|"시장 자료 읽기"| db
    rlworker -.->|"종가 읽기"| db
    sources["KRX, ECOS, KOSIS,<br/>관세청, OECD"] --> batch["data 배치<br/>수동 실행"]
    batch --> db
    backend -->|"시장 지표 조회"| marketapi["KRX, ECOS, KOSIS,<br/>관세청 API"]
    backend -->|"뉴스 수집"| news["RSS, GDELT"]
    backend -->|"도우미 질문"| llm["외부 LLM<br/>GMS 경유"]
```

- **Backend는 백테스트를 계산하지 않습니다.** 요청을 검증해 PostgreSQL에 저장하고, 트랜잭션이 커밋된 뒤
  RabbitMQ에 작업을 발행합니다. 발행에 실패해도 접수는 `202`로 끝나며, 주기적으로 도는 재발행 스케줄러
  (`JobRepublisher`)가 아직 발행되지 않은 실행을 다시 발행합니다. Worker가 보낸 이벤트를 받아 상태와 결과를
  저장하는 일도 Backend가 맡습니다.
- **요청과 실행을 분리합니다.** 같은 조건의 실행이 진행 중이면 그 실행에 합류(`JOINED`)하고, 이미 완료됐으면
  결과를 재사용(`REUSED`)합니다([ADR-0006](docs/adr/0006-backtest-request-run-split.md)).
- **Worker는 같은 이미지를 역할만 바꿔 실행합니다.** 백테스트 Worker에는 단건 큐만 처리하는 `single`과, 묶음
  실행을 먼저 처리하고 묶음이 없으면 단건을 돕는 `flex`의 두 역할이 있습니다. 사례 연결 Worker는 별도
  엔트리포인트로 실행합니다. RabbitMQ의 competing consumers 방식이므로 같은 역할의 Worker를 여러 개 띄워
  수평 확장합니다. 큐별 역할과 소비 방식은 [`worker/README.md`](worker/README.md)와 아래 메시지 계약 문서에
  있습니다.
- **상태 흐름**은 `QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED`입니다. `CANCELLED`는 사용자가
  취소했거나 실행 한도에 걸려 중단된 실행입니다. 계산 도중에 중단됐다면 그 시점까지의 부분 결과가 남을 수
  있고, 대기 중에 취소됐다면 결과가 없습니다. 결과 화면은 실행 중에 SSE로 진행 곡선을 받습니다.
- **결과는 공개 토큰 주소로 엽니다.** 결과 화면(`/results/:token`)은 내부 `backtestId`를 받지도 보이지도
  않습니다. 비로그인 사용자도 단건 분석을 접수할 수 있으며, 비로그인 결과 주소는 정해진 시간이 지나면
  만료됩니다. 유효 시간은 팀이 확정하기 전의 임시값입니다(PD-23. 공통 설정과 `.env.example`의 값은 7일이고,
  local 프로필의 값은 1시간입니다).
- **자료의 흐름**: ETF 시세, 경제지표, 경제환경 사전 계산 결과는 data 배치가 적재하고 Backend는 읽기만
  합니다. 시장 지표 11개는 Backend가 외부 API를 직접 부르고 메모리에 캐시합니다. 뉴스는 Backend 스케줄러가
  수집합니다.
- **인증**은 Spring Security 서버 세션(Spring Session JDBC)과 쿠키 방식 CSRF를 씁니다. Backend는 세션으로만
  요청자를 식별합니다.
- **Kafka와 분산 처리**: Kafka는 dev compose에서는 기본으로 기동하고, 운영 compose에서는 `kafka` 프로필에
  있어 기본 기동에서 제외되며, 어느 쪽에서도 Job 전달에 쓰지 않습니다. Spark·Hadoop·분산 Worker는
  도입하지 않았으며, 도입 여부는 실행을 측정해 병목을 확인한 뒤 판단합니다(constitution 원칙 IX).

메시지 계약은 [`backtest-messaging`](docs/specs/backtest-messaging/)과
[`regime-link-messaging`](docs/specs/regime-link-messaging/)에, HTTP 계약은 [문서 지도](#문서-지도)의 계약
문서 목록에 있습니다. 브로커를 고른 근거는 [ADR-0002](docs/adr/0002-rabbitmq-backtest-message-contract.md)에,
자동 비교의 발행 창 결정은 [ADR-0005](docs/adr/0005-param-search-dispatch-window.md)에 있습니다.

## 기술 구성

| 영역 | 주요 기술 | 역할 |
|---|---|---|
| Frontend | React 19, TypeScript 5.8, Vite 6, React Router 7, axios, Tailwind CSS 4, shadcn(Radix), visx·d3 기반 차트, Motion, Vitest | SPA 화면과 결과 시각화 |
| Backend | Java 21, Spring Boot 3.5.4, Gradle 8.14.3, Spring Security, Spring Session JDBC, Spring AMQP, Flyway, Spotless, ArchUnit, Testcontainers | API, 인증·세션, 실행 접수와 조정, 상태·결과 저장, 시장 지표 조회, 뉴스 수집 |
| Worker | Python 3.11, NautilusTrader 1.219.0, pandas 2.3, SciPy 1.17.1, pika, psycopg 3, pydantic 2, uv | 다종목 리밸런싱 백테스트, 사례 직후 구간 계산 |
| Data | Python 3.11, requests, pandas, pyarrow(Parquet), SQLAlchemy, psycopg, SciPy | KRX 시세·경제지표 수집·정제·적재, 경제환경 사전 계산 |
| Messaging | RabbitMQ 3.13(작업 큐는 quorum 큐) | 작업 분배, 진행·결과 이벤트, 취소 전달 |
| Storage | PostgreSQL 16 | 계정·세션, 실행 상태와 결과·재현 정보, 시장·경제지표 자료 |
| Infra | Docker Compose, nginx 1.27, Cloudflare Tunnel, Jenkins | 로컬·운영 실행, CI, 배포 |

외부 연동 대상은 KRX OPEN API, 한국은행 ECOS, KOSIS, 관세청(공공데이터포털), OECD, 국내 경제 RSS와 GDELT,
Gmail SMTP, 카카오·네이버·Google OAuth, GMS(외부 LLM 게이트웨이)입니다. 버전은 `package.json`·`build.gradle`·
`gradle-wrapper.properties`·`pyproject.toml`·`uv.lock`·compose 파일에 적힌 값을 기준으로 합니다.

## 로컬 개발 환경 구성

권장 구성은 PostgreSQL과 RabbitMQ만 컨테이너로 띄우고, Backend·Worker·Frontend는 호스트에서 실행하는
것입니다. 모든 서비스를 컨테이너로 띄우는 구성에는 아래 [현재 제약](#전체-컨테이너-실행의-현재-제약)이 있습니다.

### 준비물

- Docker와 Docker Compose
- Java 21 (Gradle wrapper가 Gradle을 받습니다)
- Python 3.11과 `uv` (Worker, Data)
- Node.js와 npm: 22.x(22.22.2 이상) 또는 24.x(24.15 이상)를 씁니다. Vitest가 쓰는 jsdom 30이 이 범위를
  요구합니다. CI는 Node.js 24를 씁니다.

### 1. 환경변수 파일

루트 `.env`는 compose의 변수 치환에 쓰입니다. 기본 PostgreSQL·RabbitMQ 계정(`thorp`/`thorp`)은 로컬 개발용
예시이며, `.env` 파일은 커밋하지 않습니다.

```bash
cp .env.example .env              # macOS/Linux
```

```powershell
Copy-Item .env.example .env       # Windows PowerShell
```

`./gradlew bootRun`은 루트 `.env`가 아니라 `backend/.env`를 읽습니다. 이 파일에 메일 계정이 없으면 설정
누락을 드러내기 위해 Backend 기동이 실패하도록 되어 있습니다. 로그인 없이 쓰는 화면만 볼 때는 아래처럼
더미 값으로 충분합니다. 더미 값이면 메일 서버 연결 검사가 실패해 `/actuator/health`가 `503`을 돌려주므로,
예시처럼 그 검사를 끕니다. 로그인이 필요한 기능(설정 조합 자동 비교 실행, 내 기록, 사례 비교의 새 계산,
나의 학습, 배우기 관리)을 로컬에서 확인하려면 Gmail 앱 비밀번호를 넣거나 소셜 로그인 제공자 키를 설정합니다.
외부 지표 키가 있다면 같은 파일에 [`.env.example`](.env.example)의 변수 이름으로 넣습니다.

```dotenv
# backend/.env
MAIL_USERNAME=dev@example.com
MAIL_PASSWORD=dummy
# 더미 메일 값일 때 /actuator/health가 503을 돌려주지 않도록 메일 연결 검사를 끕니다.
management.health.mail.enabled=false
```

### 2. PostgreSQL과 RabbitMQ

```bash
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq
```

2026-09-11 이전부터 쓰던 `rabbitmq-data` 볼륨이라면 먼저
[`backtest-queue-migration.md`](docs/guides/backtest-queue-migration.md)를 확인합니다. 새 볼륨에는 해당하지
않습니다.

### 3. Backend

local 프로필의 기본값은 Flyway를 끄고 Hibernate가 엔티티 표만 만드는 방식입니다. 이 상태에서는 시장 자료 표
(`daily_price` 등), 경제지표 표, 학습 기록 표가 생기지 않아 해당 화면과 백테스트 접수가 동작하지 않습니다.
운영과 같은 스키마를 쓰려면 아래처럼 Flyway를 켜고 실행합니다. Flyway가 세션 표도 만들기 때문에 Spring
Session의 스키마 초기화는 끕니다.

```bash
cd backend
SPRING_FLYWAY_ENABLED=true SPRING_JPA_HIBERNATE_DDL_AUTO=validate \
SPRING_SESSION_JDBC_INITIALIZE_SCHEMA=never ./gradlew bootRun
```

```powershell
cd backend
$env:SPRING_FLYWAY_ENABLED = "true"
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = "validate"
$env:SPRING_SESSION_JDBC_INITIALIZE_SCHEMA = "never"
.\gradlew.bat bootRun
```

- 이미 Hibernate가 만든 스키마가 남아 있는 볼륨이라면 `docker compose -f docker-compose.dev.yml down -v`로
  볼륨을 비운 뒤 2단계부터 다시 시작합니다. 이 명령은 로컬 DB와 브로커의 자료를 모두 지웁니다.
- 배우기 관리 화면을 쓰려면 관리자로 쓸 계정의 사용자 ID를 환경변수 `THORP_LEARNING_ADMIN_USER_IDS`에 넣어
  함께 넘깁니다.
- Backend가 기동하면서 RabbitMQ 토폴로지를 선언하므로, Worker는 Backend가 뜬 뒤에 실행합니다.

### 4. Worker

```bash
cd worker
uv sync --python 3.11 --group dev

# 별도 터미널마다 하나씩 실행합니다.
MARKET_DATA_URL=postgresql://thorp:thorp@localhost:5432/thorp WORKER_ROLE=single uv run python -m worker.main
MARKET_DATA_URL=postgresql://thorp:thorp@localhost:5432/thorp WORKER_ROLE=flex uv run python -m worker.main
MARKET_DATA_URL=postgresql://thorp:thorp@localhost:5432/thorp uv run python -m worker.regime_link_main
```

`RABBITMQ_URL`을 지정하지 않으면 `amqp://thorp:thorp@localhost:5672/`를 씁니다. `MARKET_DATA_URL`에는 기본값이
없어서, 지정하지 않으면 Worker가 시작하지 않습니다. PowerShell에서는 `$env:MARKET_DATA_URL = "..."`처럼 변수를
먼저 지정합니다. 백테스트 Worker 두 개는 컨테이너로 띄울 수도 있습니다.

```bash
docker compose -f docker-compose.dev.yml --profile app up -d --build worker worker-flex
```

Worker의 동작 규칙과 실행 절차는 [`worker/README.md`](worker/README.md)에 있습니다.

### 5. Frontend

```bash
cd frontend
npm ci
npm run dev
```

개발 서버는 `/api`로 시작하는 요청을 `http://localhost:8080`으로 넘기면서 `/api`를 뗍니다. 클라이언트가 읽는
환경변수는 `VITE_API_BASE_URL` 하나이며, 지정하지 않으면 `/api`를 씁니다. 이 변수를 빈 문자열로 두면
접두사 없이 요청하므로 개발 서버의 프록시를 타지 않습니다([`frontend/.env.example`](frontend/.env.example)). 개발 빌드의 회원가입 화면에는 서버 없이 화면 흐름만 미리
보는 선택지가 있지만, 이 선택지는 메일을 보내거나 계정을 만들지 않습니다.

### 접속 주소

| 대상 | 주소 |
|---|---|
| Frontend | `http://localhost:5173` |
| Backend 상태 확인 | `http://localhost:8080/actuator/health` (더미 메일 값이면 1단계의 메일 검사 설정이 있어야 `UP`이 나옵니다) |
| RabbitMQ 관리 화면 | `http://localhost:15672` (로컬 기본 계정 `thorp` / `thorp`) |
| PostgreSQL | `localhost:5432`, DB·계정 `thorp` |

### 자료 채우기

저장소에는 로컬 DB 덤프나 시장 자료 시드가 없습니다. 자료가 없으면 서비스는 없는 값을 지어내지 않고, 자료가
준비되지 않았다는 상태를 보여 줍니다. 예를 들어 섹터 대응표가 없으면 투자 대상 고르기 화면에 안내 문장이 나오고
(`409 SECTOR_MAP_NOT_READY`), 발행된 시장 자료가 없으면 백테스트 접수가 `422`로 거절되며, 사전 계산된 기준월이
없으면 경제환경 검색은 아직 비교할 과거 자료가 준비되지 않았다고 안내합니다.

- **실제 KRX 자료**: `data/.env`(예시: [`data/.env.example`](data/.env.example))에 `KRX_API_KEY`와
  `DATABASE_URL`을 넣습니다. `data/`에서 `python scripts/ingest_sector_map.py --map-file config/sector-map-2026-09.json`으로
  확정 대응표를 발행한 뒤 `scripts/ingest_krx.py`로 일봉을 적재합니다. `ingest_krx.py`는 대응표 버전·종목·
  기간·상장 정보를 필수 인자로 받고, `--commit`을 주지 않으면 DB를 바꾸지 않습니다. 인자는 `--help`로
  확인합니다. 가상환경 준비와 규칙은 [`data/CLAUDE.md`](data/CLAUDE.md)에 있습니다.
- **Worker 단독 확인용 합성 자료(키 불필요)**: [`worker/scripts/load_three_etf_fixture.py`](worker/scripts/load_three_etf_fixture.py)는
  3종목의 2023~2024년 합성 자료를 예시 자료 표시(`is_sample`)와 함께 넣습니다. 이 자료는 거래일 달력을
  `KOSPI`가 아닌 별도 지수로 넣기 때문에 Backend 접수(`POST /backtests`)에는 쓸 수 없고
  `422 INSUFFICIENT_HISTORY`로 거절됩니다. 또 같은 섹터 코드를 다른 속성으로 넣으므로 실제 KRX 자료와 한
  DB에 함께 둘 수 없습니다. 실제 자료로 넘어갈 때는 `docker compose -f docker-compose.dev.yml down -v`로 DB를
  비우고 2단계부터 다시 시작합니다.

  ```bash
  cd worker
  MARKET_DATA_URL=postgresql://thorp:thorp@localhost:5432/thorp uv run python scripts/load_three_etf_fixture.py
  ```
- **경제지표와 경제환경 검색**: `register_macro_registry.py`(처음 한 번), `ingest_macro.py --mode initial|regular`,
  `precompute_regime.py --source macro --all` 순서로 실행합니다. 수집에는 ECOS·KOSIS·공공데이터포털 키가
  필요합니다. 로컬에서는 `data/`에서 `python scripts/ingest_macro.py --mode initial --data-root .`처럼
  `--data-root`를 지정합니다. 기본값 `/data`는 운영 `data-batch` 컨테이너의 마운트 경로이기 때문입니다.
  [`regime-batch-operation.md`](docs/guides/regime-batch-operation.md)는 운영 호스트에서 컨테이너로 실행하는
  절차이므로, 로컬에서는 명령 순서와 확인 방법만 참고합니다.
  예시 자료 모드(`--profile dev --sample-root <경로>`, 환경변수 `MACRO_ALLOW_SAMPLE_DATA=1`)가 있지만, 저장소에는
  이 모드로 바로 쓸 수 있는 예시 응답 묶음이 없습니다. 요청마다 맞는 응답 파일을 직접 준비해야 하고, 17개
  지표가 전 구간을 채우지 못하면 발행이 보류되어 경제환경 검색으로 이어지지 않습니다. 따라서 키 없이
  경제지표·경제환경 화면을 확인하는 준비된 경로는 아직 없습니다.
- **배우기 글**: 마이그레이션은 단계와 글의 빈 초안 행만 만듭니다. 글을 보이게 하려면 관리 화면에서
  [`docs/learning-content/`](docs/learning-content/README.md)의 원고를 가져와 저장하고 게시합니다. 단계 공개는
  되돌릴 수 없으므로 비워도 되는 로컬 DB에서 시험합니다.

### 외부 키와 계정이 필요한 기능

| 기능 | 필요한 설정 | 설정이 없을 때 |
|---|---|---|
| 회원가입 인증 메일 | `MAIL_USERNAME`, `MAIL_PASSWORD`(Gmail SMTP) | 값이 없으면 Backend가 기동하지 않습니다. 더미 값으로는 기동하지만 인증번호 메일이 나가지 않아 이메일 회원가입을 끝낼 수 없습니다. 로그인이 필요한 기능(자동 비교 실행, 내 기록, 사례 연결 계산, 나의 학습, 배우기 관리)을 로컬에서 확인하려면 Gmail 앱 비밀번호를 넣거나 소셜 로그인 키를 설정합니다. |
| 시장 지표 11개 | `KRX_API_KEY`, `ECOS_API_KEY`, `KOSIS_API_KEY`, `DATA_GO_KR_SERVICE_KEY` | 키가 없는 지표만 `NOT_CONFIGURED`로 실패하고 Backend는 정상 기동합니다. |
| 소셜 로그인 | `KAKAO_*`·`GOOGLE_*`·`NAVER_*`의 `CLIENT_ID`와 `CLIENT_SECRET`, `THORP_PUBLIC_BASE_URL` | id와 secret이 모두 있는 제공자만 버튼이 보입니다. |
| 화면 맥락 도우미 | `THORP_ASSISTANT_ENABLED_SCOPES`, `THORP_ASSISTANT_API_KEY`, `THORP_ASSISTANT_BASE_URL`, `THORP_ASSISTANT_MODEL` | 기능이 꺼지고 Backend는 정상 기동합니다. |
| 뉴스 수집 | 별도 키 없음 | 기본 설정으로 15분마다 RSS와 GDELT에서 수집합니다. |
| data 수집 | `data/.env`의 `KRX_API_KEY`, `ECOS_API_KEY`, `KOSIS_API_KEY`, `DATA_GO_KR_SERVICE_KEY` | 해당 원천을 수집할 수 없습니다. 테스트는 저장소의 픽스처로 실행됩니다. |

변수의 뜻과 발급 조건은 [`.env.example`](.env.example)의 주석에 있습니다.

### 전체 컨테이너 실행의 현재 제약

`docker compose -f docker-compose.dev.yml --profile app up -d --build`는 PostgreSQL, Kafka, RabbitMQ, Backend,
백테스트 Worker 두 개(`worker`, `worker-flex`), 사례 연결 Worker(`worker-regime-link`)를 모두 띄웁니다. 다만
지금의 compose 파일을 그대로 쓰면 다음 세 가지 문제가 생깁니다.

- `backend` 컨테이너에 `MAIL_USERNAME`·`MAIL_PASSWORD`가 전달되지 않으므로 Backend가 기동에 실패합니다.
- `backend` 컨테이너는 local 프로필의 기본값(Flyway 꺼짐)으로 뜨기 때문에, 시장 자료 표와 학습 기록 표가 생기지
  않습니다.
- `worker-regime-link`는 `WORKER_ROLE`을 빈 문자열로 받는데, Worker 설정은 이 값을 `single` 또는 `flex`로만
  허용하므로, 설정 검증을 통과하지 못해 시작되지 않습니다.

### 종료

```bash
docker compose -f docker-compose.dev.yml --profile app down      # 볼륨은 남깁니다
docker compose -f docker-compose.dev.yml --profile app down -v   # 로컬 DB와 브로커 자료까지 지웁니다
```

## 저장소 구조

| 경로 | 내용 | 먼저 볼 문서 |
|---|---|---|
| `frontend/` | React SPA(화면, API 연동, 차트) | [`frontend/CLAUDE.md`](frontend/CLAUDE.md), [`frontend-guidelines.md`](frontend/docs/frontend-guidelines.md) |
| `backend/` | Spring Boot API. `com.ssafy.thorp` 아래 도메인별 4계층 패키지와 Flyway 마이그레이션 | [`backend/CLAUDE.md`](backend/CLAUDE.md) |
| `worker/` | 백테스트 Worker와 사례 연결 Worker | [`worker/README.md`](worker/README.md), [`worker/CLAUDE.md`](worker/CLAUDE.md) |
| `data/` | KRX 시세·경제지표 수집과 적재, 경제환경 사전 계산, 서버 이관 SQL | [`data/CLAUDE.md`](data/CLAUDE.md), [`data/sql/README.md`](data/sql/README.md) |
| `ai/` | 예약 폴더(코드 없음). 도우미 기능은 Backend `assistant` 도메인에 있습니다. | [`ai/README.md`](ai/README.md), [ADR-0008](docs/adr/0008-external-llm-via-gms.md) |
| `infra/` | Dockerfile, 운영 compose, nginx, RabbitMQ 설정, Jenkins 구성, 배포·백업·점검 스크립트 | [`infra/jenkins/README.md`](infra/jenkins/README.md), [`docker-compose.prod.yml`](infra/docker-compose.prod.yml) |
| `specs/` | 기능별 spec·plan·tasks·계약(001~017) | [`specs/README.md`](specs/README.md) |
| `docs/adr/` | 설계 결정 기록 | [문서 지도](#문서-지도) |
| `docs/specs/` | 구현된 API·메시지 계약 | [문서 지도](#문서-지도) |
| `docs/guides/` | 운영 절차와 검증 기록 | [문서 지도](#문서-지도) |
| `docs/learning-content/` | 배우기 원고(V2, 여섯 단계 27편)와 용어 대응표 | [`docs/learning-content/README.md`](docs/learning-content/README.md) |
| `docs/content/learning/` | 2026-09 배우기 1판(9편) 게시 기록 | [`docs/content/learning/README.md`](docs/content/learning/README.md) |
| `.specify/` | Spec Kit 설정, constitution, 문서 템플릿 | [constitution](.specify/memory/constitution.md) |
| `.claude/`, `.agents/` | Claude·Codex용 Spec Kit 스킬 | [`AGENTS.md`](AGENTS.md) |
| `docker-compose.dev.yml`, `Jenkinsfile` | 로컬 compose, MR CI 파이프라인 | 이 README |

## 개발 방식과 검증

### 파트별 검증 명령

각 파트의 `CLAUDE.md`가 정한 명령이며, 아래 명령은 모두 저장소 루트에서 시작합니다.

```bash
# Backend: Docker가 없으면 Testcontainers DB 테스트가 건너뛰어집니다.
cd backend && ./gradlew spotlessApply build

# Frontend: Vitest와 node --test를 차례로 돌립니다.
cd frontend && npm run lint && npm run build && npm test

# Worker
cd worker && uv run ruff check src tests scripts && uv run ruff format --check src tests scripts && uv run pytest

# Data: 처음 한 번은 아래 주석의 명령으로 data/.venv를 만들고, 그 가상환경을 활성화한 상태에서 실행합니다.
#   cd data && uv venv --python 3.11 .venv && uv pip install -r requirements.txt -r requirements-dev.txt
cd data && ruff check src scripts tests && pytest

# 마이그레이션 버전 역전·중복 검사(CI와 같은 검사, 기준 ref 기본값은 origin/dev)
bash infra/scripts/check-migration-version.sh
```

- 테스트 결과의 `skipped` 수를 확인합니다. Backend는 Docker가 없으면 DB 테스트를 건너뛰고, Frontend의 Vitest는
  `vite.config.ts`의 `test.include` 허용 목록에 든 파일만 수집합니다.
- Data 가상환경은 `data/.venv`를 씁니다. Windows에서는 PATH의 `python`이 Microsoft Store 스텁일 수 있으므로
  `.venv/Scripts`를 PATH 앞에 둡니다.

### CI (Jenkins)

루트 [`Jenkinsfile`](Jenkinsfile)은 MR마다 다음을 실행합니다. Jenkins Job 구성과 운영 방법은
[`infra/jenkins/README.md`](infra/jenkins/README.md)에 있습니다.

1. **Merge with dev**: MR의 마지막 커밋을 그 시점의 dev와 병합한 결과를 검사합니다. 각 MR이 따로 통과한 뒤
   합친 결과가 깨지는 일을 막기 위해서입니다.
2. **Migration version**: Flyway 마이그레이션 버전의 중복과 역전을 검사합니다.
3. **파트 단계**: `frontend/`·`backend/`·`worker/`·`data/` 가운데 변경된 파트의 lint·test·build 단계만
   실행합니다. Backend 테스트 단계는 Docker가 있어야 하며, 파트별 명령은 `Jenkinsfile`에 있습니다.
   `infra/`·`docs/`·`specs/`만 바꾼 MR에서는 1·2단계만 실행합니다. 마지막 커밋 메시지에 `[ci-all]`을 넣으면 모든
   파트의 단계를 실행합니다.

결과는 GitLab 커밋 상태와 Mattermost 알림으로 보고합니다.

### 작업 흐름

기능은 Spec Kit 절차(`spec.md` → `plan.md` → `tasks.md` → 구현)에 따라 만들고, 진척은 `tasks.md` 체크와
브랜치·MR로 추적합니다. 템플릿과 스킬은 `.specify/`, `.claude/skills/`, `.agents/skills/`에 있습니다.
브랜치·커밋·MR 형식과 리뷰 규칙은 [`AGENTS.md`](AGENTS.md)를 따르고, MR 본문은
[MR 템플릿](.gitlab/merge_request_templates/Default.md)을 씁니다. 최상위 원칙과 완료 기준은
[constitution](.specify/memory/constitution.md)에 있으며, 루트 `CLAUDE.md`는 `AGENTS.md`를 불러오기만 합니다.

## 문서 지도

### 기능 명세

[`specs/README.md`](specs/README.md)는 spec 001~017의 색인, 흐름과 의존 관계, 우선순위, 결정 항목(PD-01~PD-26)의
목록을 담은 기준 문서입니다. 결정 항목의 상세 조건은 각 항목을 소유한 spec의 표에 있으며, 두 문서의 상태가
다른 경우가 있으므로 소유 spec을 함께 확인합니다(예: [현재 구현 상태](#현재-구현-상태) 표의 PD-02).

### 계약 문서 (`docs/specs/`)

| 문서 | 내용 |
|---|---|
| [`backtest-api/`](docs/specs/backtest-api/) | 백테스트 HTTP 계약(004) |
| [`backtest-messaging/`](docs/specs/backtest-messaging/) | Backend·Worker 백테스트 메시지 계약(스키마 버전 2) |
| [`result-api/`](docs/specs/result-api/) | 공개 토큰 결과 열람(005) |
| [`param-search-api/`](docs/specs/param-search-api/) | 설정 조합 자동 비교(007) |
| [`strategy-registry-api/`](docs/specs/strategy-registry-api/) | 전략·파라미터 등록표 조회(003) |
| [`regime-link-api/`](docs/specs/regime-link-api/) | 사례와 전략 연결 HTTP 계약(011) |
| [`regime-link-messaging/`](docs/specs/regime-link-messaging/) | 사례 연결 메시지 계약(스키마 버전 1) |
| [`learning-api/`](docs/specs/learning-api/) | 배우기 API(013) |
| [`assistant-api/`](docs/specs/assistant-api/) | 화면 맥락 도우미 API와 품질 평가 기록(015), 진단 화면 용어 도우미의 공통 요청(017) |
| [`market-api.md`](docs/specs/market-api.md) | 시장·거시 지표 최신값과 일부 지표의 최근 흐름(series) |
| [`market-flow-api.md`](docs/specs/market-flow-api.md) | 시장 흐름(009) |
| [`market-news-api.md`](docs/specs/market-news-api.md) | 뉴스(012) |
| [`101-nautilus-momentum.md`](docs/specs/101-nautilus-momentum.md) | 폐기한 단일 종목 엔진 계약(이력 문서) |

인증(014), 내 기록(006), 경제환경 검색(010), 경제지표(008)의 계약은 각 spec의 `contracts/` 폴더에 있습니다
(예: [`auth-api.yaml`](specs/014-user-auth/contracts/auth-api.yaml),
[`my-records-api.md`](specs/006-my-records/contracts/my-records-api.md),
[`regime-search-api.md`](specs/010-economic-regime-search/contracts/regime-search-api.md)).

### 설계 결정 (`docs/adr/`)

| ADR | 결정 |
|---|---|
| [0001](docs/adr/0001-be-layered-package-architecture.md) | Backend를 도메인별 4계층 패키지로 나누고 ArchUnit으로 의존 방향을 검사합니다. |
| [0002](docs/adr/0002-rabbitmq-backtest-message-contract.md) | 백테스트 브로커를 RabbitMQ로 확정하고 메시지 계약을 정합니다. |
| [0003](docs/adr/0003-nautilus-momentum-engine-boundary.md) | 단일 종목 모멘텀 엔진의 경계를 정했습니다. 그 엔진은 spec 004에서 삭제했으므로 이력 문서로 읽습니다. |
| [0004](docs/adr/0004-backtest-commission-model.md) | 거래비용 모델을 정합니다(2026-09-17 PD-07 확정값 반영 메모 포함). |
| [0005](docs/adr/0005-param-search-dispatch-window.md) | 자동 비교는 후보를 발행 창만큼만 큐에 올립니다. |
| [0006](docs/adr/0006-backtest-request-run-split.md) | 백테스트 요청과 실행을 분리하고, 제어 채널·quorum 큐·큐 분리·실행 측정을 둡니다. |
| [0007](docs/adr/0007-data-pipeline-not-deployed.md) | `data/`를 운영에 배포하지 않고, 자료를 로컬에서 만들어 서버 DB로 옮기기로 했습니다. 이후 경제지표 적재와 경제환경 사전 계산은 운영 호스트의 `data-batch` 컨테이너로 실행하게 되어, 이 부분은 결정과 어긋납니다. |
| [0008](docs/adr/0008-external-llm-via-gms.md) | 도우미는 별도 AI 서버 없이 Backend가 GMS를 거쳐 외부 LLM을 부릅니다. |

### 운영 절차와 검증 기록 (`docs/guides/`)

| 문서 | 내용 |
|---|---|
| [`regime-batch-operation.md`](docs/guides/regime-batch-operation.md) | 운영 호스트에서 경제지표 적재와 경제환경 사전 계산 배치를 실행하는 절차 |
| [`data/sql/README.md`](data/sql/README.md) | 로컬에서 적재한 ETF 시세를 서버 DB로 옮기는 절차 |
| [`backtest-queue-migration.md`](docs/guides/backtest-queue-migration.md) | 2026-09-11 이전 RabbitMQ 볼륨의 quorum 큐 전환(최초 1회) |
| [`prod-db-flyway-migration.md`](docs/guides/prod-db-flyway-migration.md) | 예전 운영 DB를 Flyway로 전환한 절차(이력 문서) |
| [`backtest-execution-verification-2026-09.md`](docs/guides/backtest-execution-verification-2026-09.md) | spec 004 실제 프로세스 검증 기록(2026-09-15) |
| [`etf-market-data-verification-2026-09.md`](docs/guides/etf-market-data-verification-2026-09.md) | spec 001 자료 확보 검증 기록(2026-09-17) |
| [`usability-observation-2026-09.md`](docs/guides/usability-observation-2026-09.md) | 사용성 관찰 진행 서식(관찰은 아직 하지 않음) |
| [`nautilus-momentum-engine.md`](docs/guides/nautilus-momentum-engine.md) | 삭제한 DEMO 엔진 입문서(이력 문서) |

### 내용 일부가 현재 코드와 맞지 않는 문서

- [`data/README.md`](data/README.md)의 예시 명령은 현재 스크립트 인자와 맞지 않습니다.
  [`data/CLAUDE.md`](data/CLAUDE.md)와 각 스크립트의 `--help`를 기준으로 합니다.
- 프론트 디자인 기준 [`design-system.md`](frontend/docs/design-system.md)의 일부 토큰 값은 낡았습니다. 색 토큰은
  `frontend/src/index.css`를 기준으로 합니다.
- [ADR-0007](docs/adr/0007-data-pipeline-not-deployed.md)은 운영 `data-batch` 도입 전의 결정이며, 그 차이는 위
  설계 결정 표에 적었습니다.

## 배포와 운영

운영 서비스는 <https://thorp.thisis-joe.com>입니다.

- **운영 구성**: [`infra/docker-compose.prod.yml`](infra/docker-compose.prod.yml)은 postgres, rabbitmq, backend,
  worker, worker-flex, nginx, cloudflared를 기본으로 띄웁니다. Kafka는 `kafka` 프로필, data 배치(`data-batch`)는
  `batch` 프로필에 있어 기본 기동에 포함되지 않습니다.
- **요청 경로**: TLS 연결은 Cloudflare에서 종료하고, Cloudflare Tunnel이 nginx로 요청을 전달합니다. nginx는 SPA
  정적 파일을 제공하고, `/api/`로 시작하는 요청은 접두사를 뗀 뒤 Backend로 전달하며, 진행 SSE를 위해 이 경로의
  프록시 버퍼링을 끕니다([nginx 템플릿](infra/nginx/templates/default.conf.template)).
- **배포 경로**: [`infra/jenkins/Jenkinsfile.deploy`](infra/jenkins/Jenkinsfile.deploy)는 dev 브랜치 푸시를 받아
  저장소를 동기화하고 [`deploy.sh`](infra/scripts/deploy.sh)와 상태 확인(스모크 테스트)을 실행하도록 정의돼
  있습니다. `deploy.sh`는 Frontend를 빌드해 nginx 정적 경로로 복사하고, backend·worker 이미지를 빌드한 뒤
  `docker compose up -d`로 컨테이너를 다시 기동하고, 마지막으로 nginx 설정을 다시 불러옵니다. `data-batch`
  이미지는 배포 파이프라인이 만들지 않으므로 사람이 다시 빌드합니다.
- **운영 스키마**: prod 프로필은 Flyway와 `ddl-auto=validate`를 씁니다.
  실행 한도(`THORP_LIMIT_*`), 결과 공개(`THORP_RESULT_*`), 사례 연결(`THORP_REGIMELINK_*`) 값에는 기본값이 없어,
  하나라도 비면 Backend가 기동하지 않습니다.
- **운영 변수**: 예시는 [`infra/.env.prod.example`](infra/.env.prod.example)에 있습니다. 이 파일에는 메일과 소셜
  로그인 변수가 빠져 있으므로 루트 [`.env.example`](.env.example)을 함께 확인합니다. 실제 값은 저장소에 두지
  않습니다.
- **자료 갱신**: ETF 시세는 로컬에서 적재한 뒤 [`data/sql`](data/sql/README.md) 절차로 서버 DB에 옮깁니다.
  경제지표 적재와 경제환경 사전 계산은 사람이 운영 호스트에서 `data-batch` 일회성 컨테이너를 실행해 처리합니다. 두
  경로 모두 자동 실행이 없으므로, 배치를 실행하지 않으면 화면의 자료가 갱신되지 않습니다.
- **그 밖의 스크립트**: 운영 DB 백업([`backup-db.sh`](infra/scripts/backup-db.sh)), 큐 전환
  ([`migrate-jobs-queue.sh`](infra/scripts/migrate-jobs-queue.sh)), 마이그레이션 버전 검사
  ([`check-migration-version.sh`](infra/scripts/check-migration-version.sh))가 `infra/scripts/`에 있습니다.

## 주의

Thorp의 출력은 선택한 규칙을 과거 자료로 진단한 결과입니다. 미래 수익을 보장하지 않으며, 특정 전략이나 ETF의
매매를 추천하지 않습니다. 경제환경 검색은 최신 개정자료를 이용한 역사 탐색이며, 비슷한 과거 사례를 찾았다고
해서 지금의 전략 성과를 예측할 수 있다는 뜻은 아닙니다. 구현, 사용성 확인, 효과 입증의 상태는 위
[현재 구현 상태](#현재-구현-상태)와 각 spec의 검증 기록을 기준으로 판단합니다.

## 라이선스

저장소의 코드는 [MIT No Attribution(MIT-0)](LICENSE) 라이선스를 따릅니다. 이 라이선스는 저장소의 코드에만
적용되며, 서비스가 쓰는 외부 자료에는 각 원천의 이용 조건이 따로 적용됩니다. KRX OPEN API와 공공데이터포털
증권상품시세는 비상업 이용과 제3자 제공·재배포 금지를 조건으로 하는 개발·검증 단계의 원천입니다. 자료 이용
규칙은 [constitution](.specify/memory/constitution.md)의 원칙 X(자료는 이용 조건 안에서만 쓴다)에 있습니다.
