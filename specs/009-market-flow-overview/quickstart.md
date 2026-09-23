# Quickstart: 시장 흐름 개요 검증

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Contracts**: [contracts/openapi.yaml](contracts/openapi.yaml)

구현이 spec을 만족하는지 확인하는 절차다. 구현 코드는 여기에 적지 않는다.

## 사전 준비

- JDK 21, Docker(Testcontainers용), Node 20 이상
- 008 저장소가 아직 없으므로 기본값은 예시 자료 fixture다. 실제 자료 전환은 `MacroIndicatorSnapshotPort`
  구현체를 바꾸는 것으로 끝난다([research.md](research.md) R2).

## 실행

```bash
# backend (repo root)
./gradlew spotlessApply build
./gradlew bootRun

# frontend
cd frontend
npm run lint && npm run build && npm test
npm run dev
```

`npm test`는 `test:dom`(Vitest)과 `test:legacy`를 차례로 돌린다. **결과의 `skipped` 수를 확인한다.**
새 테스트 파일이 `vite.config.ts`의 `test.include`에 걸리는지 반드시 본다(frontend/CLAUDE.md).

## 계약 확인

```bash
curl -s localhost:8080/market-flow/fields | jq .
curl -s localhost:8080/market-flow/fields/labor | jq .
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/market-flow/fields/unknown   # 400
```

응답이 [contracts/examples/](contracts/examples/)의 예시와 같은 모양인지 본다.

## 시나리오별 검증

### S1. 기본 화면 (US1 / FR-001~FR-006, SC-001, SC-002)

1. 로그인하지 않은 상태로 `/market-flow`에 들어간다.
2. 7개 분야 이름과 분야별 변화 요약이 보인다. 설문·숙련도 분류·전략 선택을 거치지 않는다.
3. **경제 전체를 하나의 점수·등급·신호등으로 요약한 요소가 없다.** 기존 `/home`의
   `MarketRegimeHero`가 남아 있으면 이 항목은 실패다.
4. 변화 요약 문장에 좋다·나쁘다 판정, 전망, 투자 시사점 표현이 없다.
5. 자료 공급원·API 이름을 고르는 입력이 없다.
6. 가장 중요한 행동 버튼이 하나다(분야 열기).

> SC-002의 "0건"은 사람 눈으로만 세지 않는다. 서술 formatter의 금지 어휘 검사 단위 테스트가
> 같은 것을 코드로 막는다([research.md](research.md) R8).

### S2. 분야 상세 (US2 / FR-007~FR-009, SC-003)

1. 아무 분야나 연다.
2. 지표마다 실제 값·단위·최근 3개월 경로(t-2·t-1·t)·대상월·발표일·출처·작성기관이 보인다.
3. 표준화 점수나 거리로만 표시된 지표가 없다.
4. 분야 기준월 T_g가 보이고, 분야마다 다를 수 있다는 안내가 한 문장 있다.
5. `field-detail-ready.json`의 `unemployment_rate`처럼 T_g 이후 발표가 있는 지표는
   **지표 행 바로 아래 한 줄**로 보이고, t-2·t-1·t 경로 안에 들어가지 않는다.
6. '비슷했던 때 보기'를 누르면 분야 g와 T_g가 유지된 채 010 화면으로 넘어간다.
   (010 미구현 동안에는 넘기는 값이 무엇인지까지만 확인한다.)

### S3. 자료 시점과 상태 (US3 / FR-011~FR-015, SC-005)

1. fixture를 `fields-collection-failed.json` 모양으로 바꾼다.
2. `production`은 '수집 실패', `overseas`는 '자료 없음' 상태로 보이고 **오류 화면이 되지 않는다.**
3. 나머지 분야는 정상 표시된다. 한 분야의 실패가 화면 전체로 번지지 않는다.
4. 모든 지표 영역에 대상월과 마지막 갱신 시각이 함께 보인다.
5. **'지연'으로 판정한 표시가 어디에도 없다.** '실시간'·'현재' 표현도 없다.
6. 화면에 '최신 개정자료를 이용한 역사 비교' 표시가 있다.
7. fixture를 `fields-example-data.json`으로 바꾸면 모든 분야에 예시 자료 표시가 붙는다.

### S4. 섹터 연결 (US4 / FR-016~FR-018, SC-004)

1. 섹터를 고르기 전 분야 요약·지표 값·기준월을 기록한다.
2. 섹터를 고른다. 1에서 기록한 값이 하나도 바뀌지 않는다.
3. 연결되는 전략·대표 상품·뉴스 범위만 좁아진다.
4. **새로고침한다 → 선택이 남아 있다.** 다른 메뉴에 갔다 돌아와도 남아 있다.
5. **탭을 닫고 새 탭으로 연다 → 선택이 풀려 있다.**
6. 로그인하지 않은 상태에서 4·5가 모두 성립한다.
7. 브라우저 저장소를 막은 상태(시크릿 창의 사이트 데이터 차단 등)에서도 화면이 오류로 막히지 않고,
   그 화면 안에서는 섹터를 고를 수 있다.
8. 국내 시장 참고 정보(KOSPI)가 경제환경 지표와 구분된 영역에 있고, '비슷했던 때 보기' 입력으로
   쓰이지 않는다. 이 자료가 없으면 그 영역만 비고 분야 요약은 정상이다.

### S5. 성능 (SC-007)

1. 백엔드: Testcontainers PostgreSQL 16에 17개 지표 × 120개월 fixture를 넣고 30회 warm-up 후
   100회 측정한 `/market-flow/fields` p95를 기록한다.
2. 화면: 브라우저 개발자 도구 Network 패널을 열고 캐시를 비운 뒤 `/market-flow`에 들어가,
   7개 분야 요약이 모두 그려질 때까지의 시간을 잰다. **2초 이내여야 한다.**
3. 뉴스 묶음·국내 시장 참고 정보가 늦게 채워지는 동안에도 분야 요약이 먼저 보인다.

측정값은 원칙 IX에 따라 MR 본문에 남긴다. 목표를 못 맞추면 캐시를 더 얹기 전에 어디서 시간을
쓰는지부터 기록한다.

### S6. 재현성 (원칙 VIII)

1. 같은 `registryVersion`·`datasetVersion`으로 읽기 모델을 다시 만든다.
2. 분야 요약 문장·서술 대상 지표·기준월이 100% 같다.

## 사용성 확인 (SC-006)

기능 검증과 별도다. 전략을 검증하려는 개인 6~8명에게 진행자 설명 없이 시장 흐름을 보여주고,
분야 유사성을 경제 전체의 일치로 오해하는지와 관심 분야의 지표 값을 설명 없이 찾는지를 기록한다.
두 명 이상이 오해하면 화면을 고치고 같은 시나리오를 다시 관찰한다.
