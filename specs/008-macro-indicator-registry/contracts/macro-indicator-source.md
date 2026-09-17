# 내부 소비자 계약: 008 → 010/009

**상태**: 계획 계약. [010의 기존 포트 계약](../../010-economic-regime-search/contracts/macro-indicator-port.md)과 함께 구현 시 계약 테스트로 고정한다. [공개 REST 계약](openapi.yaml)은 008 소유다.

## 010 `data/` 계산 입력: `MacroIndicatorSource`

010은 Python 배치에서 008의 **공개 완료된** `(data_version, calculation_registry_version)` 조합 하나를 읽는다. Backend가 시계열·척도·거리 계산을 대신하지 않는다.

| 연산 | 반환/조건 |
|---|---|
| `registry(version)` | 17개 안정 ID, 원천/단위/변환/공표 정밀도, 지표→7개 분야 매핑과 명목 가중. 표시 분류 버전은 별도 |
| `raw_series(indicator_id, data_version)` | 대상월→원값, 결측은 null/없음으로 구분; 전 가용 구간 |
| `monthly_series(indicator_id, data_version)` | 대상월→변환값, 유효값 없는 월은 값 없음; 임의 보간 금지 |
| `publication(indicator_id, month, data_version)` | 원천 발표일(없으면 unknown), 수신 시각, 원천 개정/자체 개정 식별자, 원본 근거 |
| `baseline_months(data_version, registry_version)` | 분야별 `T_g`, 전체 `T_all` (null 가능). 자료 확보월이며 검색 계산 완료월이 아님 |
| `changed_since(previous_data_version, target_data_version)` | 고정한 두 버전 사이에 값·사용 가능성 변경이 발생한 **가장 오래된 대상월**. 정확히 판정 불가 시 `None`으로 전 구간 재계산 요구 |
| `is_sample_data(data_version)` | 예시 데이터 여부. 운영 검색에 fixture 결과를 조용히 섞지 않음 |

호출 시작 시 current 포인터를 한 번 읽어 자료·계산·표시 버전을 고정한다. 배치 도중 새 발행이 생겨도 같은 target 버전을 끝까지 읽는다. `changed_since`는 동일 값 규칙끼리만 비교하고 값 규칙이 바뀌면 전 구간 재계산을 요구한다. 매핑·가중 변경은 시계열 재처리 없이 010의 모델/검색 계산 재실행 대상이다. 010은 결과에 사용한 자료·등록표·모델 버전을 기록한다. `T_all=null`이면 종합 검색은 계산 불가이며, 유효 `T_g`를 가진 분야 검색은 유지한다. 010의 `regime_baseline_month`는 사전 계산이 끝난 검색 가능월의 근거로서 008 `T_g`와 별도다. 현 010 테이블의 `baseline_month NOT NULL`을 유지하려면 null 범위의 완료월 행을 만들지 않고, `baseline-months` 응답에서 `composite:null`/분야 `baselineMonth:null`을 명시하며 해당 검색을 `NOT_COMPUTABLE`로 처리하도록 010 계약을 갱신한다. 기존 010 계약의 인자 없는 `registry()`·`data_version()`과 현재 표시명 조회는 버전 고정 규칙에 맞춰 정합화해야 한다.

## 010 Backend 표시 등록표

010의 `MacroIndicatorPort`는 008의 표시/계산 등록표 조회 경계를 통해 `registryVersion()`, `fields()`, `indicator(id)`를 제공한다. 화면 이름·순서는 표시 분류 버전, 계산 매핑·가중은 계산 등록표 버전에서 읽는다. 010 Backend는 `macro_observation`을 직접 조회하거나 계산하지 않는다. 포트가 실패하면 fixture 이름으로 자동 대체하지 않는다.

## 009 Backend 화면 조립

009의 기존 `GET /market-flow/fields`와 `GET /market-flow/fields/{fieldId}`는 유지한다. 두 요청은 008 `GET /macro-indicators/snapshot`의 같은 응답 구조를 소비한다. 현재 합의의 기본 구현은 REST 호출이며, 009는 `macro_*` 저장소 직접 접근이 없다. 현재 009의 4회 분리 조회 포트는 포인터 교체 중 버전 혼합 위험이 있으므로 요청당 **한 번의 008 스냅샷 조회**로 교체한다. 변화 요약은 009가 소유하지만 숫자·단위·월·출처·작성기관·기준월 지연 원인 지표는 008 응답에서만 가져온다. 009의 검색 링크는 010의 계산 완료월을 별도 확인한다. 008의 `T_g`만으로 링크를 활성화하지 않는다.

연동 전 009 OpenAPI의 무자료 `datasetVersion`/`referenceMonth` nullable 및 빈 경로, 010의 field ID casing, 표시 버전별 009 서술 재빌드와 수집 상태의 요청별 반영, fixture 운영 격리 규칙을 갱신하고 양쪽 계약 테스트를 둔다. 008·009의 응답 봉투는 신규 `HttpApiResponse`로 동일하다. 이는 008 단독 문서가 009·010을 이미 변경했다는 뜻이 아니다.
