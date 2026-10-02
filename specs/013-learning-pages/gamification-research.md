# 배우기 경험치·레벨·배지 조사 (2026-10-02)

**상태**: spec 013 2026-10-02 명료화의 근거 메모다. FR-024·FR-028~FR-030의 수치와 규칙이 왜 그렇게
정해졌는지 남긴다. 코드와 원고는 바꾸지 않았다.

**요청**: 담당자는 확인 문제를 글마다 두세 개 두고 하나만 맞혀도 익힘으로 하되 맞힌 개수가 적으면
경험치를 덜 주라고 정했다. 배지는 Garmin처럼 경험치·읽은 글 수·확인 문제 정답 수 등 여러 갈래로 나누라고
했고, 구체 규칙은 넓게 조사해 정하라고 맡겼다.

**조사 범위**: 세 갈래로 나눠 조사했다.

1. 운동 기록 앱의 배지 구조: Garmin Connect, Apple Watch, Fitbit, Strava, Nike Run Club.
2. 학습 앱의 경험치 규칙: Duolingo, Khan Academy, Microsoft Learn, DataCamp, Codecademy, Sololearn, Brilliant.
3. 교육 게임화의 학술 근거와 금융 서비스 게임화 규제: 메타분석, 배지 연구, 자기결정성 이론, 미국 SEC·FINRA,
   매사추세츠 주 Robinhood 동의 명령, 영국 FCA, ESMA, 국내 금융위원회·금융감독원 자료.

공식 문서를 열지 못해 검색 요약이나 커뮤니티 글로만 확인한 내용은 '미확인'으로 적었다.

## 1. 조사에서 확인한 것

### 1.1 운동 기록 앱

- Garmin은 배지마다 1·2·4·8점을 주고, 점수 합계로 레벨 1~10을 정한다. 레벨 문턱은 공식 발표가 없고,
  커뮤니티에 올라온 값은 두 배씩 커져 레벨 10에 10,220점이 필요하다(미확인). 처음에는 레벨 5까지만
  있어서 상한에 닿은 사용자들이 항의했고, 그 뒤 레벨 10까지 늘었다.
- 사용자 반발이 가장 컸던 일은 이미 얻은 것을 없앤 경우(Fitbit의 챌린지 폐지, Google Health 전환 때
  배지 삭제)와 점수를 돈으로 팔게 한 경우(Garmin 유료 등급의 두 배 점수)였다.
- Apple Watch와 Fitbit은 누적형 배지의 단계 간격을 점점 넓히고, 아직 얻지 못한 배지를 회색 윤곽으로
  보인다. Apple은 1,000일 뒤로는 250일마다 같은 간격으로 이어 가서 배지가 끝나지 않게 한다.
- Strava의 왕관·지역 전설처럼 다른 사람과 견주는 배지는 순위 기능이다. Thorp는 사용자 간 순위를 두지
  않으므로(constitution "하지 않는 것") 이 갈래는 쓰지 않는다.

### 1.2 학습 앱

- **같은 일로는 한 번만 준다.** DataCamp와 Codecademy는 끝낸 내용을 반복해도 경험치를 다시 주지 않는다.
  Duolingo는 끝낸 내용을 복습하면 새 학습보다 훨씬 적게 준다.
- **첫 시도로 점수를 매긴다.** Khan Academy의 공개 코드는 힌트 없이 첫 시도에 맞혔을 때만 점수를 줬다.
  Microsoft Learn은 확인 문제를 처음에 모두 맞히면 경험치를 더 준다.
- **글 단위 학습의 가장 가까운 사례는 Microsoft Learn이다.** 단원·모듈·학습 경로마다 경험치를 주고,
  모듈마다 배지, 경로마다 트로피를 준다.
- **진도를 초기화해도 경험치는 남기는 쪽이 늘었다.** DataCamp, Khan Academy, 2022년 이후 Sololearn이
  그렇다. Sololearn은 2018년에 초기화하면 경험치를 빼앗았다가 바꿨다.
- **레벨 곡선**: 일정 비율로 커지는 곡선(예: 레벨마다 1.4배)은 뒤쪽 레벨에 닿지 못하게 만든다. 레벨
  사이 간격을 일정하게 조금씩 늘리는 곡선이 중간 해법으로 권해진다.

### 1.3 학술 근거

- 교육 게임화의 효과는 양(+)이지만 크지 않다. 메타분석 세 편의 효과 크기는 0.25~0.50 사이다
  (Sailer & Homner 2020, Bai·Hew·Huang 2020, Huang 외 2020).
- 배지는 자기가 세는 행동을 늘린다. 문제 풀이에 배지를 붙인 1,000명 넘는 실험에서 답한 문제 수가 22%
  늘었고 정확도는 떨어지지 않았다(Denny 2013). Stack Overflow 연구에서는 사용자가 배지 직전에 그 행동을
  늘렸다가 받은 뒤 원래대로 돌아갔다(Anderson 외 2013).
- 점수·레벨·순위표는 양을 늘리지만 질이나 내적 동기를 늘리지는 않았다(Mekler 외 2017). 한 학기 동안
  배지와 순위표를 쓴 수업은 내적 동기와 시험 점수가 오히려 낮았다(Hanus & Fox 2015).
- 자기결정성 이론에 따르면 통제하는 보상과 압박은 내적 동기를 깎고, 유능감을 알려 주는 정보형
  피드백은 북돋운다(Deci·Koestner·Ryan 1999, Ryan & Deci 2000).
- 새로움의 효과는 시간이 지나면 줄어든다(Koivisto & Hamari 2014). 14주 연구에서는 4주쯤 효과가 떨어졌다가
  6~10주에 일부 돌아왔다(Rodrigues 외 2022).

### 1.4 금융 서비스 게임화 규제

- 미국 SEC는 2021년 의견 수렴에서 연속 기록, 점수·배지·순위표, 알림, 주문 때 폭죽 같은 축하 효과를
  들어, 잦은 거래와 위험한 전략을 부추길 수 있다고 물었다. 관련 규칙안은 2025년에 철회되어 지금은 규칙이 없다.
- 매사추세츠 주는 Robinhood와 2024년 1월 동의 명령을 맺었다. Robinhood는 거래 빈도와 바로 연결된 폭죽 같은
  축하 이미지를 영구히 쓰지 않기로 했고, 벌금은 750만 달러였다.
- 영국 FCA의 2024년 실험(9,000명 넘게 참여)에서는 알림이 거래 수를 11%, 점수와 추첨이 12% 늘렸다.
- ESMA는 2023년 토론 문서에서 축하는 개별 거래가 아니라 장기 목표 달성이나 분산 같은 일에 하라고 했고,
  게임화 기법은 중립적인 교육 내용에 쓰라고 권했다. 무작위 보상은 도박과 비슷한 기법으로 봤다.
- 국내에서는 금융위원회·금융감독원의 다크패턴 가이드라인(2025년 12월 발표, 2026년 4월 시행)이 반복 간섭,
  감정적 언어 사용 같은 유형을 정했다. 게임화만 따로 다룬 국내 감독 당국의 문서는 찾지 못했다(미확인).
  증권사가 '수익금 상위 1%' 같은 배지를 쓴 사례가 보도된 적이 있다.

규제가 문제로 보는 것은 거래와 그 빈도에 묶인 보상과 축하, 연속 출석과 매일 접속 보상, 무작위 보상,
성과 순위, 위험한 종목 목록으로 이끄는 알림이다. 사용자의 장기 이익을 위한 중립적인 교육은 받아들인다.

## 2. 정한 규칙과 근거

| 정한 것 | 근거 | spec |
|---|---|---|
| 경험치는 이해를 활동보다 크게 준다. 글 하나 최대 100 가운데 80이 확인 문제에서 나온다 | 점수는 양을 늘리고 질은 늘리지 않는다(1.3). 첫 시도 정답으로 점수를 매긴다(1.2) | FR-028 |
| 같은 글·같은 문제로는 한 번만 주고, 다시 풀기는 경험치를 주지 않는다 | 학습 앱의 반복 보상 차단(1.2) | FR-028 |
| 경험치·레벨·배지는 줄지 않는다. 되돌리기는 읽기 상태와 익힘만 바꾼다 | 얻은 것을 없앨 때 반발이 가장 컸다(1.1). 초기화해도 경험치를 남기는 쪽이 늘었다(1.2) | FR-028, FR-029 |
| 레벨 문턱은 레벨 사이 간격을 일정하게 늘리고(100·200·300…), 10 뒤로는 같은 간격으로 잇는다 | 배수 곡선은 뒤쪽 레벨에 닿지 못한다(1.1, 1.2) | FR-029 |
| 지금 게시된 글로 닿을 수 있는 레벨까지만 보이고 '최고 레벨' 끝을 두지 않는다 | Garmin의 레벨 상한 항의, Apple의 끝나지 않는 사다리(1.1) | FR-029 |
| 배지는 일곱 갈래 사다리로 두고 첫 단계는 쉽게 닿게 한다 | 운동 앱의 갈래·단계 구조(1.1), 배지 직전에 행동이 는다(1.3) | FR-024 |
| 배지는 점수를 주지 않고, 레벨은 경험치로만 정한다 | 레벨의 원천을 하나로 두라는 권고(1.1) | FR-024, FR-029 |
| 배지 기준을 모두 공개하고 숨긴 배지·무작위 보상·기간 한정 배지를 두지 않는다 | 무작위 보상은 도박과 비슷한 기법(1.4) | FR-024 |
| 배지·레벨 이름은 무엇을 익혔는지만 말하고 투자 실력·수익·순위를 뜻하는 낱말을 쓰지 않는다 | '수익금 상위 1%' 같은 배지 사례와 중립성 요구(1.4) | FR-024, FR-029 |
| 진단·백테스트·결과 보기·'이 예시로 전략 진단하기'에는 경험치와 배지를 주지 않는다 | 보상은 그 행동을 늘린다(1.3). 규제는 거래와 묶인 보상을 문제로 본다(1.4). 설정 조합을 많이 시도하게 만들면 과최적화를 부추긴다 | FR-028 |
| 새 배지와 레벨은 배우기 안에서만 조용히 알린다. 폭죽·소리를 쓰지 않고 움직임 줄이기 설정을 따른다 | 축하 효과에 대한 규제(1.4), WCAG 2.2의 2.3.3·4.1.3 | FR-030, FR-019 |

## 3. 쓰지 않기로 한 것

| 쓰지 않은 것 | 이유 |
|---|---|
| 리그·순위표, 다른 사용자와 견주는 배지 | constitution "하지 않는 것"(사용자 간 순위). 순위표는 내적 동기와 성적을 떨어뜨린 연구가 있다 |
| 연속 출석, 매일 과제, 경험치 두 배 | spec 013 Out of Scope(연속 출석·학습 알림). 이해가 아니라 속도와 빈도를 보상한다 |
| 경험치를 사거나 돈·혜택으로 바꾸는 것 | Garmin 유료 등급의 '돈으로 사는 점수' 반발(1.1). 기대된 실물 보상은 내적 동기를 깎는다(1.3) |
| Garmin식 두 배 레벨 곡선 | 뒤쪽 레벨에 사실상 닿지 못한다는 불만(1.1) |
| 배지마다 점수를 주어 레벨을 정하는 방식 | 경험치와 겹쳐 같은 활동을 두 번 세게 된다 |

## 4. 남은 판단

- 되돌리기 때 배지를 다시 판정할지는 담당자의 첫 결정과 조사 권고가 달랐다. 담당자가 조사 권고를 받아들여
  한 번 얻은 배지는 지우지 않기로 정했다(FR-024).
- 경험치를 사용자가 숨길 수 있게 하자는 권고(새로움 효과 대응)는 이번에 넣지 않았다.
- 도입 효과는 클릭 수가 아니라 확인 문제 첫 답 정답률과 단계 완주율로 보는 것이 맞다는 권고가 있다. 측정
  방법은 plan에서 정한다.

## 출처

- Garmin 배지와 레벨: https://www.garmin.com/en-US/blog/?p=33716, https://the5krunner.com/2023/09/04/garwin-the-garmin-badges-calendar-and-database/,
  https://forums.garmin.com/apps-software/mobile-apps-web/f/garmin-connect-web/287826/what-are-the-number-of-points-needed-for-levels-from-challenges-badges/1616289 (커뮤니티),
  https://gadgetsandwearables.com/2025/03/28/garmin-badge-pay/
- Apple Watch: https://support.apple.com/108359
- Fitbit·Google Health: https://9to5google.com/2023/03/27/fitbit-challenges-groups-removed/, https://support.google.com/googlehealth/answer/17068213
- Strava: https://support.strava.com/hc/en-us/articles/216917137
- Duolingo: https://blog.duolingo.com/achievement-badges, https://blog.duolingo.com/ways-to-practice-in-duolingo
- Khan Academy: https://support.khanacademy.org/hc/en-us/articles/202487710, https://raw.githubusercontent.com/avh4/khan-academy/master/exercises.py (과거 공개 코드)
- Microsoft Learn: https://learn.microsoft.com/en-us/training/support/org-reporting-developer-reference
- DataCamp: https://support.datacamp.com/hc/en-us/articles/360005357414
- Codecademy: https://www.codecademy.com/resources/blog/introducing-codecademy-skill-xp
- Sololearn: https://www.sololearn.com/en/Discuss/3045063/about-the-new-xp-table/
- 레벨 곡선: https://www.gamedeveloper.com/design/quantitative-design---how-to-define-xp-thresholds-
- Sailer & Homner (2020): https://link.springer.com/article/10.1007/s10648-019-09498-w
- Denny (2013): https://peerwise.cs.auckland.ac.nz/docs/community/do_badges_work
- Anderson 외 (2013): https://archives.iw3c2.org/www2013/proceedings/p95.pdf
- Mekler 외 (2017): https://glassmanlab.seas.harvard.edu/annotated_works/mekler17.pdf
- Ryan & Deci (2000): https://selfdeterminationtheory.org/SDT/documents/2000_RyanDeci_SDT.pdf
- Rodrigues 외 (2022): https://doi.org/10.1186/s41239-021-00314-6
- SEC Release 34-92766 (2021): https://www.sec.gov/rules/other/2021/34-92766.pdf
- 매사추세츠 주 Robinhood 동의 명령: https://www.sec.state.ma.us/divisions/securities/download/RH-Consent-Order.pdf
- FCA (2024): https://www.fca.org.uk/news/press-releases/fca-keeps-trading-apps-under-review-over-gaming-concerns
- ESMA35-43-3682 (2023): https://www.esma.europa.eu/sites/default/files/2023-12/ESMA35-43-3682_Discussion_Paper_on_MiFID_II_investor_protection_topics_linked_to_digitalisation.pdf
- 금융위원회 다크패턴 가이드라인: https://fsc.go.kr/no010101/85942
- WCAG 2.2: https://www.w3.org/TR/WCAG22/
