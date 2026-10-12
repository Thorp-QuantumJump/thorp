# 배우기 용어 대응표

`next/`의 새 기준(V2) 원고 27편이 소유하는 용어와 예시를 모은 표다. 용어마다 처음 설명하는 글은 하나다
(spec 013 FR-008). 원고에서 뽑아 만들었으므로 원고를 고치면 이 표도 함께 고친다.
첫 단계 공개 조건 4([plan](../../specs/013-learning-pages/plan.md) "첫 단계 공개 조건", research R39)를 확인하는 근거다.

- ★는 9월 판에 게시된 열네 용어다. 식별자를 바꾸지 않는다(003 등록표와 도움말이 이 식별자를 가리킨다).
- 넘겨받기는 9월 판에서 다른 글이 소유하던 용어를 이 글이 가져오는 것이다(`transferFrom`, data-model §7-2).
- 이름은 2026년 10월 기준 진단 화면 이름이다(FR-006). 9월 판의 옛 전략 이름은 쓰지 않는다.

## 1단계 ETF와 국내 시장 기초 (`etf-market-basics`)

| 번호 | 글 (`articleId`) | 소유 용어 (`termId` — 이름) | 예시 |
|---|---|---|---|
| 1-1 | 섹터와 ETF (`sectors-and-etf`) | ★`sector` — 섹터<br>★`representative-etf` — 대표 ETF<br>`etf` — ETF | 없음 |
| 1-2 | ETF 가격은 왜 지수와 똑같이 움직이지 않을까? (`etf-tracking-difference`) | `nav` — 순자산가치<br>`tracking-error` — 추적오차<br>`premium-discount` — 괴리율 | 없음 |
| 1-3 | 분배금을 받으면 돈이 늘어난 걸까? (`etf-distributions`) | `distribution` — 분배금<br>`adjusted-close` — 조정 종가 | 없음 |
| 1-4 | ETF 보수는 어디로 빠져나갈까? (`etf-fees`) | `total-fee` — 총보수 | 없음 |

## 2단계 수익과 위험 읽기 (`returns-and-risk`)

| 번호 | 글 (`articleId`) | 소유 용어 (`termId` — 이름) | 예시 |
|---|---|---|---|
| 2-1 | 수익과 손실 (`returns-and-losses`) | ★`net-total-return` — 비용 차감 후 전체 수익률<br>★`max-drawdown` — 가장 크게 줄었던 폭 | 없음 |
| 2-2 | 같은 수익률인데 왜 느낌이 다를까? (`same-return-different-feel`) | `compounding` — 복리 | 없음 |
| 2-3 | 떨어진 뒤 얼마나 걸려 돌아왔을까? (`recovery-period`) | `recovery-period` — 회복 기간 | 없음 |
| 2-4 | 변동성은 손실일까? (`volatility-is-not-loss`) | ★`volatility` — 변동성 (넘겨받기: `inverse-volatility`) | 없음 |

## 3단계 배분 규칙과 설정 (`allocation-rules`)

| 번호 | 글 (`articleId`) | 소유 용어 (`termId` — 이름) | 예시 |
|---|---|---|---|
| 3-1 | 비중과 리밸런싱 (`weights-and-rebalancing`) | ★`weight` — 비중<br>★`rebalancing` — 리밸런싱<br>★`fixed-weight` — 고정 비중<br>★`equal-weight` — 동일 비중 | 반도체 50%·은행 50% |
| 3-2 | 나누기만 하면 분산일까? (`diversification-and-correlation`) | `correlation` — 상관관계<br>`diversification` — 분산 투자 | 반도체 33.4%·2차전지 33.3%·은행 33.3% |
| 3-3 | 역변동성 (`inverse-volatility`) | ★`inverse-volatility` — 역변동성 | 반도체 33.4%·은행 33.3%·화장품 33.3% |
| 3-4 | 섹터 모멘텀 (`sector-momentum`) | ★`sector-momentum` — 섹터 모멘텀 | 반도체 25%·2차전지 25%·은행 25%·자동차 25% |
| 3-5 | 최소분산 (`minimum-variance`) | ★`minimum-variance` — 최소분산 | 반도체 25%·은행 25%·화장품 25%·건설 25% |
| 3-6 | 관측 기간은 왜 고르는 항목일까? (`why-lookback-period`) | `lookback-period` — 관측 기간 | 반도체 33.4%·은행 33.3%·화장품 33.3% |
| 3-7 | 왜 최근 한 달을 빼고 볼까? (`skip-recent-month`) | `skip-recent-period` — 최근 구간 제외 기간 | 반도체 25%·2차전지 25%·은행 25%·자동차 25% |
| 3-8 | 상위 몇 개와 기준 수익률 이상은 어떻게 다를까? (`top-n-or-threshold`) | `selection-method` — 선정 방식 | 반도체 20%·2차전지 20%·은행 20%·증권 20%·자동차 20% |
| 3-9 | 비중 상한은 왜 둘까? (`why-weight-cap`) | `weight-cap` — 섹터당 최대 비중 | 반도체 33.4%·은행 33.3%·화장품 33.3% |

## 4단계 백테스트와 비용 (`backtest-and-costs`)

| 번호 | 글 (`articleId`) | 소유 용어 (`termId` — 이름) | 예시 |
|---|---|---|---|
| 4-1 | 백테스트 (`backtest`) | ★`backtest` — 백테스트<br>`look-ahead-bias` — 미래 참조 편향 | 없음 |
| 4-2 | 같은 조건에서 비교하기란 무엇일까? (`same-conditions`) | `warmup-period` — 준비 기간<br>`evaluation-period` — 실제 평가 기간 | 반도체 50%·은행 50% |
| 4-3 | 거래비용 (`transaction-cost`) | ★`transaction-cost` — 거래비용<br>`slippage` — 슬리피지 | 반도체 50%·은행 50% |
| 4-4 | 회전율이 높으면 왜 결과가 뒤집힐까? (`turnover`) | `turnover` — 회전율 | 반도체 25%·2차전지 25%·은행 25%·자동차 25% |
| 4-5 | 자료 기간이 짧으면 무엇을 알 수 없을까? (`short-history`) | `sample-period` — 자료 기간 | 없음 |

## 5단계 함정과 검증 (`pitfalls-and-validation`)

| 번호 | 글 (`articleId`) | 소유 용어 (`termId` — 이름) | 예시 |
|---|---|---|---|
| 5-1 | 과거가 미래를 보장하지 않는 이유 (`past-is-not-future`) | `overfitting` — 과최적화<br>`out-of-sample` — 검증 구간 | 없음 |
| 5-2 | 50개 설정 중 1등은 왜 믿기 어려울까? (`best-of-many-settings`) | `multiple-testing` — 다중 검정 | 없음 |
| 5-3 | 이웃 설정도 좋은가? (`neighboring-settings`) | `neighbor-stability` — 인접 설정 안정성 | 없음 |

## 6단계 경제환경과 국면 (`economy-and-regimes`)

| 번호 | 글 (`articleId`) | 소유 용어 (`termId` — 이름) | 예시 |
|---|---|---|---|
| 6-1 | 시장 흐름의 7개 분야는 무엇을 보여 줄까? (`market-flow-fields`) | `yoy-change` — 전년동월비<br>`normal-range` — 보통 범위 | 없음 |
| 6-2 | '비슷했던 때'는 어떻게 찾을까? (`similar-periods`) | `similar-period` — 비슷했던 때<br>`composite-comparison` — Thorp 종합 비교 | 없음 |

## 합계

- 글 27편, 용어 43개(중복 없음: 43개). 용어 배지 사다리의 전제인 25개 이상을 채운다(R39).
- 9월 판 열네 용어 가운데 소유 글이 없는 용어: 없음.
- 경험치 최대치: 글 27편 × 100 + 단계 6 × 50 = 3,000(FR-028·FR-029).
- 예시가 있는 글: 12편. 진단과 바로 이어지는 글에만 둔다(FR-003).
