# Research: 경제지표 등록·수집 (Spec 008)

**작성일**: 2026-09-17  
**범위**: [spec.md](spec.md)의 FR-001~023을 구현하기 위한 기술 결정. 실제 통계 항목·과거 범위·이용 조건(PD-11)과 최소 유효 척도(PD-10)는 미확정이다. 이 문서는 원천 응답을 검증한 증거가 아니다.

## R1. 원천과 실자료 검증

- **Decision**: ECOS·KOSIS를 기본으로, 수출·수입·무역수지는 관세청, G20 경기선행지수는 OECD 공식 자료로 연결한다. 지표마다 주 원천 하나를 등록한다. KDI 월간 경제동향은 후보·해석 문맥만 참고하고 수치 또는 교차검증 원천으로 쓰지 않는다. 17개 각각의 통계표/항목 코드, 단위, 작성기관, 이용 조건과 재배포 예외, 최초 관측월, 발표일·개정 식별 가능성, 개편 연결 규칙은 인증키와 실제 응답으로 PD-11 검증표에 기록한 뒤에만 실자료 완료로 표시한다. 관세청 무역수지는 공식 응답에 별도 값이 있는지 확인하고, 파생이 필요하면 수출·수입의 동일 단위/시점과 공식 값 일치 여부를 검증하기 전까지 확정하지 않는다.
- **Rationale**: 기획서와 FR-005/006/019/020의 원천 역할을 유지하면서 미확인 응답 구조를 사실로 포장하지 않는다. 기존 [시장 카드 API 조사](../../docs/specs/market-api.md)는 11개 최신값 조사이며 17개 월별 시계열의 증거가 아니다.
- **Alternatives considered**: KDI의 표를 수치로 병합하거나 ECOS·KOSIS 양쪽 값을 합산하는 방식은 출처 및 중복 문제로 기각. 개발 대역을 실자료로 간주하는 방식은 FR-022에 반한다.
- **검증 근거/남은 일**: [KOSIS API 소개](https://kosis.kr/openapi/introduce/introduce_01List.do), [KOSIS FAQ](https://kosis.kr/openapi/community/community_0401List.do), [OECD API](https://www.oecd.org/en/data/insights/data-explainers/2024/09/api.html), [OECD 이용 조건](https://www.oecd.org/en/about/terms-conditions.html), [관세청 월별 무역통계 API](https://www.data.go.kr/data/15102108/openapi.do), [ECOS API 포털](https://ecos.bok.or.kr/api/). KOSIS FAQ는 일반 사용과 달리 일부 국제·북한통계에 비상업/재배포 금지 예외를 명시한다. OECD는 계열별 제3자 권리도 확인해야 한다. ECOS 포털의 이용 세부는 이 조사에서 독립 검증되지 않았다. 이 문서들만으로 개별 계열의 항목 코드·전 구간·조건을 확정하지 않는다.

## R2. 수집과 재조회

- **Decision**: `data/`에 원천별 어댑터와 월별 정규화/변환 파이프라인을 둔다. 최초는 확인된 가용 전 구간, 정기는 새 발표분과 지표별 `N`개월을 재조회한다. `N`은 모든 과거 개정을 잡는 보장이 아니라 정기 탐지 구간이다. 원천의 개정 공지·메타데이터로 바깥 구간의 변경을 확인하면 해당 범위를 별도 재조회한다. 원천별 호출 한도에 맞춘 요청 분할, 속도 제한, 재시도/중단 상태를 기록한다. 기준연도 개편 또는 값 규칙 변경에는 전 구간 재처리한다. 일정·실행 방식은 배포 환경 작업 묶음에서 확정하되 사용자 GET 경로에서는 원천을 호출하지 않는다.
- **Rationale**: `data/CLAUDE.md`의 raw → processed(Parquet) → PostgreSQL 경계를 따른다. 거시 지표는 기존 자산/연도 파티션의 자산 축에 대응하는 지표/연도 파티션으로 두되, 파트 규칙 변경이 필요하면 구현 전에 합의한다. 최근분 재조회는 그 구간의 개정을 잡고 저장된 전 구간과 합쳐 전체 버전을 만든다. 원천 응답 실패 시 성공한 raw 파일은 재처리를 위해 남기되 공개 버전은 만들지 않는다.
- **Alternatives considered**: Backend 요청 시 호출은 지연·한도·재현성 문제로 기각. 정기 실행마다 전 구간 다운로드는 한도와 비용 측면에서 기각. 새 월만 append하는 방식은 개정을 놓친다.
- **검증 근거/남은 일**: KOSIS는 [API 소개](https://kosis.kr/openapi/introduce/introduce_01List.do)의 분당 200회·요청당 40,000셀 조건을 상한으로 둔다. [OECD API 권고](https://www.oecd.org/en/data/insights/data-explainers/2024/11/Api-best-practices-and-recommendations.html)의 다운로드·캐싱 조건을 적용한다. 관세청 API는 [공식 안내](https://www.data.go.kr/data/15102108/openapi.do)의 수정 시점·호출 한도를 확인한다. ECOS와 지표별 실제 개정 관행을 확인하기 전에는 `N`을 임의 고정하지 않는다.

## R3. 등록표·버전의 분리

- **Decision**: 계산 등록표 버전(17개 정의·값 규칙·분야 매핑·명목 가중)과 표시 분류 버전(7개 분야의 이름·순서·설명)을 따로 둔다. 계산 등록표 안에 값/유효 구간 규칙의 fingerprint를 저장한다. 화면 문구만 바뀌면 표시 버전만, 매핑·가중만 바뀌면 계산 버전만 새로 만든다. 원천·항목·단위·변환·연결·결측 규칙이 바뀌면 계산 버전과 전 구간 재처리 자료 버전을 함께 새로 만든다.
- **Rationale**: FR-003을 버전 관계로 집행한다. `dataVersion + calculationRegistryVersion`을 재현성 키로 사용하고, 자료 버전의 value-rule fingerprint와 계산 등록표 fingerprint가 맞아야 결합한다. 매핑·가중만 바뀐 경우 fingerprint는 같고 관측 시계열 재처리는 없다.
- **Alternatives considered**: 단일 버전은 화면 문구 수정만으로 검색 재계산을 유발한다. 자료 버전만 저장하면 계산 정의의 변화를 재현할 수 없다.

## R4. 불변 스냅샷과 원자적 발행

- **Decision**: 기존 ETF `dataset_version`을 재사용하지 않고 `macro_*` 전용 모델을 둔다. 원본 개정 이력·월별 변환에 기여한 여러 원본의 계보·정규화 관측값을 보존한다. 공개 자료 버전은 17개 지표의 전체 가용 구간을 담는 불변 스냅샷이다. raw/Parquet manifest를 먼저 봉인·해시한 다음, 지표별 유효 월별 **변환값**이 각각 최소 1건이고 17개 처리 모두 성공했을 때 한 PostgreSQL 트랜잭션에서 스냅샷·기준월을 기록하고 단일 current 포인터를 교체한다. 파일은 DB 트랜잭션 밖이므로 장애 시 미참조 파일 복구/정리와 공개 파일 보존을 별도 검증한다. `T_all = null`만으로 발행을 막지 않는다.
- **Rationale**: 부분 성공·버전 혼합 조회를 막는다. 001의 기존 KRX 데이터 버전 경로는 목적과 완결성 조건이 다르다. 직전 공개 버전은 실패 중에도 유지한다. 수집 시도 상태는 공개 포인터와 별도로 갱신한다.
- **Alternatives considered**: 지표별 최신 행을 조합한 조회는 한 시점의 17개 상태를 보장하지 않는다. 부분 발행은 FR-011 위반. 최근 `N`개월만 복사한 스냅샷은 과거 검색 이력을 소실한다.

## R5. 기준월과 시간 의미

- **Decision**: 월 키는 `YYYY-MM` 대상월, 발표일과 수신 시각은 별도 필드다. 사용 가능한 변환값의 월 집합 교집합으로 분야별 `T_g`와 17개 전체 `T_all`을 계산하고 최대 월을 저장한다. 교집합이 비면 null로 둔다. 조회용 최근 3개월 `t-2,t-1,t`는 달력상 연속 월로 제공하여 결측을 null로 명시한다. `T_g` 이후 새로 나온 지표별 최신값도 구분한다.
- **Rationale**: 같은 월의 서로 다른 발표 시차와 월별 결측을 숨기지 않는다. 010의 검색 완료월은 008의 자료 확보월과 별개이며 `regime_baseline_month` 등 010 결과에서 판정한다.
- **Alternatives considered**: 최대 관측월을 기준월로 대체하거나 결측을 이전 값으로 채우는 방식은 FR-008/013 위반이다.

## R6. 008·009·010 기술 경계

- **Decision**: 이전 clarifications의 합의대로 008은 사용자용 지표 REST 조회를 소유하고, 009의 두 화면 전용 REST API는 유지한다. 009는 008의 단일 스냅샷 응답 계약을 소비해 화면 정보와 변화 요약을 조립하며 `macro_*` DB를 직접 읽지 않는다. 동일 Backend 프로세스 안에서의 호출 구현은 **현재 합의 문구의 문자적 해석인 실제 HTTP 호출을 기본안**으로 둔다. 010의 시계열 계산 입력은 `data/` Python 경계로, Backend의 표시용 등록표는 008 조회 경계로 받는다. Backend에서 거리·척도 계산을 하지 않는다.
- **Rationale**: 사용자 합의 및 AGENTS의 Backend 비계산 원칙을 지키고, 009 응답을 한 자료 버전으로 고정한다. 009의 현재 `MacroIndicatorSnapshotPort`/예시 어댑터는 이 계약으로 교체해야 한다. 010의 최신 검색 가능월은 010 계산 결과에서 별도 제공한다.
- **Alternatives considered**: 009의 직접 DB 읽기는 명시적으로 기각. 같은 프로세스의 008 애플리케이션 조회 서비스 직접 호출은 네트워크 비용이 적고 응답 계약을 공유할 수 있으나, 기존의 '008 API만 사용' 표현을 완화하는 선택이어서 팀 확인 전 기본안으로 채택하지 않는다. 009가 별도 원천을 수집하는 방식도 기각.
- **연동 문서 보정**: 009 spec의 검색 진입 최신 `T_g` 표현은 010의 검색 완료월 기준으로 정정한다. 009 OpenAPI의 자료 버전/기준월 non-null 및 상세 경로 3개 필수와 실제 무자료/T_g=null 상태를 맞춘다. 신규 API 봉투는 Backend의 `HttpApiResponse<T>` 및 009 계약과 같은 `{httpStatus,isSuccess,message,code,result}`를 쓴다. 구형 `ApiResponse<T>`는 이 경계의 근거가 아니다. 현재 009의 4회 분리 조회 포트는 요청당 008 스냅샷 1회 조회로 바꿔 포인터 교체 중 버전 혼합을 막는다. 009 요약 read model은 `(dataVersion, calculationRegistryVersion, displayVersion, narrativeRuleVersion)`에 고정한 서술만 캐시하고, 변동 가능한 수집 상태/마지막 시도는 008 현재 응답에서 매 요청 덧씌운다. 009의 분야 ID(lower snake)와 010 예시의 대문자 ID는 canonical ID와 변환 책임을 확정한다. 009 계약 예시의 두바이유 ID `dubai_crude`와 현재 Backend fixture의 `dubai_oil`도 일치시킨다. fixture에 쓴 원천명·단위·수치는 PD-11 검증 근거가 아니다. 이는 009/010 계약 정합화 작업이며 008 문서만으로 완료했다고 보지 않는다.

## R7. 예시 자료와 운영 전환

- **Decision**: 예시 입력은 명시적 개발 프로필에서만 사용하고 자료 버전/응답에 `sampleData`를 남긴다. 운영에서는 무자료·수집 실패를 그대로 노출하고 자동 대체하지 않는다. 009의 `ExampleMacroIndicatorSnapshotAdapter`는 실 008 연결 시 운영 등록에서 빠지고, 010의 fixture 데이터로 계산된 결과도 운영 노출되지 않아야 한다.
- **Rationale**: FR-022와 원칙 VI/X를 지킨다. 기존 대역이 `@Component`로 남아 잘못 선택되는 위험을 차단한다.
- **Alternatives considered**: 연결 실패 시 조용히 fixture로 fallback하는 것은 실제 통계로 오인될 수 있어 기각.

## R8. 검증과 미확정 게이트

- **Decision**: Python은 `ruff check src scripts tests`와 `pytest`, Backend는 Spotless/Gradle build 및 PostgreSQL Testcontainers 통합 테스트로 검증한다. 공개 원자성·실패 후 이전 버전 유지·개정 감지·무자료·`T_all=null`·분류/계산 버전 독립·009/010 계약 테스트를 포함한다. Flyway 마이그레이션과 이를 사용하는 코드/검증은 같은 MR에 둔다. PD-11의 실제 17개 계열 검증 전에는 Spec 008을 완료 처리하지 않는다. PD-10 확정 전에는 최소 유효 척도를 꾸며내지 않는다.
- **Rationale**: constitution의 재현성·미확인 항목·스키마 변경 규칙과 파트별 검증 명령을 충족한다.
- **Alternatives considered**: fixture 통과만으로 완료 판정하거나 테스트에서 Docker 필수 검증을 조용히 skip하는 방식은 실제 통합 위험을 가린다.
