# Tasks: 결과 이해·비교

**Input**: Design documents from `specs/005-result-comparison/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md)(Clarifications 13건), [research.md](research.md)(R-01~R-13), [data-model.md](data-model.md), [contracts/result-api.md](contracts/result-api.md), [quickstart.md](quickstart.md)(확인 1~10), [checklists/review.md](checklists/review.md)(CHK001~CHK032 반영 완료)

**선행 작업**: clarify [S15P21E205-262](https://ssafy.atlassian.net/browse/S15P21E205-262) · plan [S15P21E205-265](https://ssafy.atlassian.net/browse/S15P21E205-265) · 요구사항 품질 점검 [S15P21E205-269](https://ssafy.atlassian.net/browse/S15P21E205-269). 셋 다 dev에 병합됐다.

**이 문서의 작업**: [S15P21E205-266](https://ssafy.atlassian.net/browse/S15P21E205-266) · branch `docs(fe)/266-spec-005-tasks`.

**Tests**: 포함한다. constitution "완료의 정의" 조건 2가 "테스트 또는 동작 확인 완료"를 Task 완료 조건으로 두고, plan.md "Testing"이 검증 명령과 계약 픽스처 대조 방식을 정했다. 각 테스트 태스크는 [quickstart.md](quickstart.md)의 확인 번호(1~10)를 적는다. SC-002·SC-007·SC-010은 자동 시험이 아니라 사용성 관찰(PD-14)로 확인하므로 T083a에 절차로 둔다.

**Organization**: 사용자 스토리별로 묶고, 각 단계 안에서 파트별로 다시 나눈다.

## 착수 전에 알아둘 것 (Pre-Implementation Gate)

구현을 막거나 방향을 바꿀 수 있는 사실이다. 티켓으로 만들지 않고 여기와 MR 본문에 적는다(AGENTS.md "곁가지는 티켓으로 만들지 않는다").

① **004에 요청하는 변경 다섯 가지**([contracts/result-api.md](contracts/result-api.md) "004에 요청하는 변경")를 이 tasks는 아래처럼 다룬다.

| 항목 | 이 문서의 처리 |
|---|---|
| `backtest_request.requester_id` nullable 완화 | **B01이 004 패키지 안에서 최소로 수행한다**(T008·T010). 004 research R-12가 열어 둔 경로다. MR 리뷰어에 004 담당을 지정한다 |
| 접수 응답에 `publicToken` 추가 | **B01이 수행한다**(T011·T016). 재시도 응답도 같다 |
| `summary.maxDrawdownWindow` 추가 | 004 몫. 그때까지 화면이 `drawdown` 계열에서 구간을 찾고, `summary.maxDrawdown`과 어긋나면 음영을 생략한다(T040, R-05). 004가 주면 T040의 함수를 지운다 |
| `analytics`의 상관관계·위험 기여·손실 기여 노출 | 004 몫. 그때까지 상세 패널의 세 항목은 "제공되지 않음"이다(T064) |
| 참조하는 요청이 없는 계산·결과의 정리 | 004 몫. 005의 정리 작업은 만료된 요청 행과 토큰만 지운다(T022). 재사용·합류 기록(`backtest_reuse_event`)은 지우지 않고 `request_id`만 NULL로 끊는다 — `backtest_run_metric` 집계의 `reuse_count`가 이 행을 세며 원칙 IX가 동일 결과 재조회 기록을 요구한다. 컬럼 완화는 T022가 한다. **B02(318)는 재사용 기록을 지우는 방식으로 병합됐으므로 T022·T026을 다시 열었고, 후속 fix 묶음에서 고친다** |

이 tasks가 코드와 대조하다 찾은 004 접점이 둘 더 있다. 둘 다 004 소유이므로 B01·B02의 MR 리뷰어에 004 담당을 지정하고 MR 본문에 근거를 적는다.

- `GET /backtests/{id}/trades`에 기간 필터가 없다(`backtest/presentation/request/TradePageQuery.java`는 `page`·`size`·`instrumentId`·`status`뿐이다). FR-010의 '손실이 커진 때' 구간 거래와 Edge Case "기간·종목으로 좁혀 보기"에 필요하다 → T015.
- 비로그인 요청은 `requester_id`가 없어 `ExecutionLimitPolicy`의 사용자별 대기열 상한을 셀 수 없다. 전체 상한만 적용하고 그 사실을 PD-06 담당(004)에게 남긴다 → T012.

② **007과의 연결(FR-011, SC-011)**: 007의 `frontend/src/components/paramsearch/CompareHandoff.tsx`는 `onOpenCompare(candidateIds)`가 연결되지 않아 진입 지점만 보이고, 후보 응답(`frontend/src/types/paramSearch.ts`의 `SearchCandidateItem`)에는 `backtestId`만 있고 공개 토큰이 없다. 005는 주소 규약 `/results/{baseToken}?compare={candidateToken}&search={searchId}`와 그 표시만 소유한다(T045·T053). 후보 응답에 `publicToken`을 더하고 `ParamSearchPage`에서 `onOpenCompare`를 연결하는 일은 007(FR-019 소유) 몫이며, 007 담당에게 말로 전한다.

③ **003과 같은 파일**: `frontend/src/api/backtest.ts`(`createBacktest`)는 003 B06 T039 몫인데 아직 없다(`frontend/src/components/strategy/analysisSupport.ts`의 `notConnectedSupport`가 자리만 잡고 있다). B05가 먼저 가면 005가 그 파일을 만들고(T047) 003은 호출부만 붙인다. 003이 먼저면 재사용하고 접수 응답의 `publicToken`만 더한다.

④ **001 대표 상품 정보의 프론트엔드 컴포넌트가 없다**(`instrument-profile`을 읽는 프론트 파일은 `frontend/src/diagnosis/attribution.ts`뿐이다). R-10대로 005가 공용 컴포넌트를 `components/instrument/`에 만들고 001이 재사용한다(T062·T063). `frontend/src/api/sectors.ts`에 조회 함수를 더하므로 001 담당을 리뷰어로 지정한다.

⑤ **미확정 결정과 유보**: PD-22(AI 해설)는 만들지 않는다. PD-23(유효 시간)은 값을 설정 파일에만 두고 문서에 수치를 적지 않는다(T004). PD-12 확정 전 `validationTier`는 항상 `UNVALIDATED`다(T020·T073). 응답 시간 목표와 거래 내역 페이지 크기는 유보하되 임시 상한을 설정으로 둔다(R-11, T004·T021).

⑥ **plan과 다르게 정한 것 셋**: (a) 프론트엔드 위치는 plan의 `features/result/`가 아니라 `components/result/`다. `frontend/CLAUDE.md` 폴더 규칙에 `features/`가 없고, 003(`components/strategy/`)·007(`components/paramsearch/`)이 같은 상황을 그렇게 풀었다. (b) `PublicResultToken` 값 타입은 plan의 `result/domain`이 아니라 `backtest/domain/model`에 둔다. 발급 시점이 004의 접수(`BacktestService.create`)라 `result/domain`에 두면 `backtest → result` 의존이 생겨 `result → backtest`와 순환한다. (c) 공개 토큰 유효 시간은 `thorp.result.public-token-ttl` 키에 두되, 발급하는 `BacktestService`는 `ResultProperties` 클래스를 주입받지 않고 `@Value`로 키만 읽는다. 클래스를 주입하면 `backtest → result` 의존이 생겨 ArchUnit 규칙 8(T017)을 어기기 때문이다(B01 구현을 따른다). (a)·(b)는 T081이 plan·research·data-model·quickstart에 반영한다.

⑦ 기존 `frontend/src/pages/BacktestPage.tsx`(목 데이터 DEMO)는 대체하지 않는다(plan Summary).

⑧ **결과 화면의 가장 중요한 행동 버튼은 '비교 대상 고르기' 하나다**(spec FR-004, 원칙 II). '자세히 보기'·'손실이 커진 때 보기'·'다른 조건으로 비교'·보조 보기·다음 탐색 진입점은 보조 모양으로 둔다. 기존 공용 버튼의 주·보조 변형을 쓰고 새 변형을 만들지 않는다(T034·T050·T061).

## Format: `[ID] [P?] [Story] (파트) Description`

- **[P]**: 다른 파일을 만지고 선행 태스크가 없어 병렬 실행 가능
- **문자 접미사 ID**(`T083a`): 코드 구현이 아닌 절차를 실행 순서 안에 끼운 태스크다(003 `T074a`와 같은 표기)
- **[US1]~[US5]**: spec.md의 사용자 스토리. Setup·Foundational·Polish에는 붙지 않는다
- **(파트)**: 아래 표의 파트. 작업 종류 표시이며 브랜치 단위가 아니다

| 파트 태그 | 대상 | 묶음의 브랜치 position |
|---|---|---|
| `(BE)` | 백엔드 Java 구현 | `be` |
| `(BE 테스트)` | 백엔드 JUnit·ArchUnit·Testcontainers | `be` |
| `(FE)` | 프론트엔드 React·TS | `fe` |
| `(FE 테스트)` | 프론트엔드 Vitest | `fe` |
| `(INFRA)` | 자원 파일(YAML)·설정·환경변수 예시 | `be` (설정은 백엔드 자원이다) |
| `(DOCS)` | 계약·픽스처·CLAUDE.md·수동 확인 기록 | `docs` |

Worker(`worker/`)와 `data/`는 이번 범위에서 **변경하지 않는다**. 스키마 변경은 **한 건**(T008)이며 constitution "스키마 변경 규칙"대로 마이그레이션·엔티티 매핑·검증을 같은 MR(B01)에 담는다.

**`T###`는 티켓·브랜치 단위가 아니다.** constitution "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로 정한다. 아래 89개는 실행 지시의 최소 단위이고, **티켓·브랜치·MR의 단위는 묶음 B01~B09 아홉이다** — "Dependencies & Execution Order"의 "작업 묶음" 표에 있다. [plan.md](plan.md) "작업 묶음 구성"의 A~E를 아홉으로 나눈 근거는 그 표 아래에 적었다.

## Path Conventions

- 백엔드 005 소유 신규: `backend/src/main/java/com/ssafy/thorp/result/{presentation/{controller,response},application/{service,result},infrastructure/config}` — 4계층 규칙(`backend/CLAUDE.md`)을 따르되 `domain`·`application/repository`는 두지 않는다(값 타입과 저장은 `backtest`가 소유한다). 테스트는 `backend/src/test/java/com/ssafy/thorp/result/...`
- 백엔드 004 소유 접점(최소로 손댄다): `backtest/domain/model/{BacktestRequest,PublicResultToken}.java`, `backtest/infrastructure/persistence/{BacktestRequestJpaEntity,BacktestRequestMapper,BacktestRequestRepositoryImpl}.java`, `backtest/application/service/{BacktestService,BacktestRetryService,BacktestResultQueryService,ExecutionLimitPolicy,ExecutionMetricRecorder}.java`, `backtest/application/repository/BacktestRequestRepository.java`, `backtest/presentation/controller/BacktestController.java`, `backtest/presentation/request/TradePageQuery.java`, `backtest/presentation/response/{BacktestJobResponse,BacktestRetryResponse}.java` (모두 `backend/src/main/java/com/ssafy/thorp/` 아래)
- 마이그레이션: `backend/src/main/resources/db/migration/V{yyyyMMddHHmmss}__public_result_token.sql` (작성 시각 타임스탬프. 현재 마지막은 `V20260916163000__widen_instrument_id_format.sql`)
- 계약 문서·픽스처: `docs/specs/result-api/{README.md,examples/*.json}`(신규), `docs/specs/backtest-api/{README.md,examples/backtest-accepted.json}`(004 접수 응답 갱신)
- 프론트엔드: 화면 `frontend/src/pages/ResultPage.tsx`, 컴포넌트·문장 틀·순수 함수 `frontend/src/components/result/`, 공용 `frontend/src/components/instrument/`, 훅 `frontend/src/hooks/{useResult,useCompareTarget}.ts`, API `frontend/src/api/{result,backtest}.ts`, 타입 `frontend/src/types/result.ts`, 픽스처 `frontend/src/test/resultFixtures.ts`, 차트 `frontend/src/components/charts/`(재사용)
- 커밋 전 `cd backend && ./gradlew spotlessApply` 필수. 프론트엔드는 `cd frontend && npm run lint && npm run build && npm test`(`skipped` 0 확인)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 계약 픽스처, 오류 코드, 설정값 자리, 프론트엔드 타입·픽스처·테스트 수집 목록

- [X] T001 (DOCS) 계약을 마무리하고 요약본을 만든다 — `specs/005-result-comparison/contracts/result-api.md`에 이 tasks가 정한 추가 항목을 적는다: 공개 상세 응답의 `strategyName`(서버가 003 등록표에서 채운다. 첫 화면이 등록표를 따로 부르면 SC-001의 요청 수를 넘긴다), `weightsSource`(`USER`|`COMPUTED`, FR-014를 첫 화면에서 보이기 위해 `/weights` 없이 담는다), `exceptionKinds`(예외 `kind`의 중복 없는 목록. 결과 옆 한 문장을 `/exceptions` 없이 만들기 위해서다, R-12), 비교 주소의 `?search={searchId}` 인자(SC-011), `/trades`의 `from`·`to` 질의(T015), `POST /results/{token}/retry` 응답 `{publicToken, status, resultSource, retryOfToken}`, `GET /results/{token}/index-comparison`(지수를 결과와 같은 기간·시작 금액으로 환산한 계열. 기간 자료가 없으면 `available: false`와 사유, T087). 그다음 `docs/specs/result-api/README.md`를 만든다 — `docs/specs/backtest-api/README.md`와 같은 역할(계약 요약·경로 대응표·오류표·"공개 응답에서 버리는 필드"·원본 위치 링크)
- [X] T002 [P] (DOCS) `docs/specs/result-api/examples/{result-detail-completed,result-detail-running,result-detail-cancelled-no-summary,result-expired,result-timeseries}.json` — contracts §GET 예시를 바탕으로 한 **완전한** 200/410 응답 봉투(`ApiResponse` 규약). `backtestId`·`runId`·`worker`·요청자 필드가 없고 어디에도 `BT-` 문자열이 없다. `result-timeseries.json`은 고점·저점이 정해진 짧은 계열(음영 구간 테스트에 쓴다). T025(BE 계약 IT)와 T039(FE 픽스처 대조)가 이 파일을 읽는다
- [X] T003 [P] (BE) `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java`에 spec 005 블록을 열고 `RESULT_EXPIRED(HttpStatus.GONE, "결과가 더 이상 남아 있지 않습니다. 같은 조건으로 다시 분석할 수 있습니다.")`를 추가한다. 주석에 계약 위치(`specs/005-result-comparison/contracts/result-api.md` "오류")를 적는다. 나머지 코드(`INVALID_INPUT`·`RESOURCE_NOT_FOUND`·`RESULT_NOT_READY`·`BACKTEST_FAILED`·`NO_PARTIAL_RESULT`·`NOT_RETRYABLE`)는 004 블록을 재사용한다
- [X] T004 [P] (INFRA) 설정값 자리 — `backend/src/main/resources/application.yml`의 `thorp:` 블록에 `result: {public-token-ttl, expiry-sweep-interval, expiry-sweep-batch-size, trades-page-size}`를 두고, `application-local.yml`에 로컬 값, `application-prod.yml`에는 `${THORP_RESULT_PUBLIC_TOKEN_TTL}`·`${THORP_RESULT_TRADES_PAGE_SIZE}`처럼 **기본값 없는** 환경변수 참조(`application-prod.yml` 63행부터의 관례), `infra/.env.prod.example`과 루트 `.env.example`에 항목 추가. 문서에 수치를 적지 않는다(PD-23). `backend/src/main/java/com/ssafy/thorp/result/infrastructure/config/ResultProperties.java`(`@ConfigurationProperties(prefix = "thorp.result")`, 네 값 모두 `@NotNull`이라 하나라도 비면 기동 실패)와 `ResultConfig.java`(`backtest/infrastructure/config/BacktestLimitConfig.java`와 같은 등록 방식). `backtest`와 `result/application`은 이 클래스를 주입받지 않고 `@Value`로 키만 읽는다(착수 전에 알아둘 것 ⑥(c), T017)
- [X] T005 [P] (FE) `frontend/src/types/result.ts` — contracts와 1:1 타입: `PublicResultDetail`(`publicToken`·`status`·`partial`·`expiresAt`·`progress`·`queueWaitSeconds`·`config`·`versions`·`summary`·`summaryUnavailable`·`failure`·`validationTier`·`strategyName`·`weightsSource`·`exceptionKinds`), `ResultProgress`, `ResultSummary`(`netTotalReturn`·`maxDrawdown`·`totalTradingCost`·`liquidation`·`analytics`·`exceptionCount`), `ResultTimeseriesPoint`(`date`·`equity`·`drawdown`·`cashFlows`), `ResultTradePage`, `ResultPositions`, `ResultWeights`, `ResultExceptions`, `ResultRetryAccepted`, `ResultErrorCode`, `ValidationTier`. 금액·비율은 문자열(004 계약과 같다. `frontend/src/types/backtestRequest.ts` 머리 주석 방식). 머리 주석에 "`backtestId`를 이 타입에 두지 않는다(SC-009)"
- [X] T006 [P] (FE) `frontend/src/test/resultFixtures.ts` — 화면 테스트용 픽스처 5종(T002와 같은 내용)과 시계열 생성 함수 `makeEquitySeries({points, peakIndex, troughIndex})`(고점·저점이 정해진 합성 계열. 1,900점짜리도 만든다). 전략 이름은 등록표에 없는 합성값을 쓴다(`frontend/src/test/strategyRegistryFixtures.ts`와 같은 이유: 응답만 보고 그린다는 것을 증명한다)
- [X] T007 [P] (FE) `frontend/src/components/result/` 디렉터리와 `frontend/src/api/result.ts` 빈 모듈을 만들고, `frontend/vite.config.ts`의 `test.include`에 `'src/components/result/*.test.ts'`·`'src/api/result.test.ts'`·`'src/test/resultFixtures.test.ts'`를 더한다. `include`는 허용 목록이라 빠지면 조용히 수집되지 않는다(`frontend/CLAUDE.md`). `*.test.tsx`는 이미 걸린다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 비로그인 요청이 접수되고 요청마다 공개 토큰이 발급된다. 이것이 없으면 결과 화면은 어떤 경로로도 뜨지 않는다(plan Summary, quickstart "전제").

**⚠️ CRITICAL**: 스키마 변경·엔티티 매핑·검증(T008·T010·T018)은 한 MR이다. 운영이 `ddl-auto: validate`라 한쪽만 병합되면 기동이 실패한다(constitution "스키마 변경 규칙"). 이 단계가 끝나기 전에는 어떤 사용자 스토리도 서버와 붙여 볼 수 없다.

- [X] T008 (BE) 마이그레이션 `backend/src/main/resources/db/migration/V{yyyyMMddHHmmss}__public_result_token.sql` — ① `ALTER TABLE backtest_request ALTER COLUMN requester_id DROP NOT NULL` ② `ADD COLUMN public_token CHAR(32)` → 기존 행 채움: 내장 `gen_random_uuid()` 두 개를 이어 `sha256`으로 섞고 앞 32자를 쓴다(UUID 하나는 122비트라 FR-019의 최소 128비트에 못 미친다. pgcrypto 확장은 운영 DB 계정 권한에 기대야 해서 쓰지 않는다). 새 요청의 토큰은 T009가 `SecureRandom`으로 발급한다 → `ALTER COLUMN public_token SET NOT NULL` → `CREATE UNIQUE INDEX uk_backtest_request_public_token ON backtest_request (public_token)` ③ `ADD COLUMN public_token_expires_at TIMESTAMP WITH TIME ZONE` + 정리 작업용 부분 인덱스 `idx_backtest_request_public_expiry ON backtest_request (public_token_expires_at) WHERE public_token_expires_at IS NOT NULL` ④ `CONSTRAINT ck_backtest_request_public_expiry CHECK (requester_id IS NOT NULL OR public_token_expires_at IS NOT NULL)`(비로그인 요청은 만료 시각이 반드시 있다, data-model §1). 파일명은 `backend/src/test/java/com/ssafy/thorp/support/MigrationFiles.java`의 정규식을 따른다. 제약 완화·컬럼 추가라 되돌릴 수 없는 변경은 아니지만 기존 행에 토큰을 채우는 UPDATE가 있으므로 MR 본문에 영향 범위를 적는다
- [X] T009 (BE) `backend/src/main/java/com/ssafy/thorp/backtest/domain/model/PublicResultToken.java` 값 타입 — `generate()`는 `SecureRandom` 16바이트 → 32자 소문자 16진, `of(String)`은 `^[0-9a-f]{32}$`가 아니면 `IllegalArgumentException`, `value()`·`equals`·`toString`(값 그대로). plan은 `result/domain`에 두자고 했으나 발급 시점이 004 접수라 여기 둔다(착수 전에 알아둘 것 ⑥). 테스트 `backend/src/test/java/com/ssafy/thorp/backtest/domain/model/PublicResultTokenTest.java`: 형식·길이, 두 번 생성한 값이 다름, 대문자·31자·33자 거부
- [X] T010 (BE) `backtest/domain/model/BacktestRequest.java` — `requesterId`를 nullable로(`:46`의 `Objects.requireNonNull` 제거), `PublicResultToken publicToken`(필수)·`Instant publicTokenExpiresAt`(nullable) 컴포넌트 추가, 불변식 "`requesterId == null`이면 `publicTokenExpiresAt != null`", `isAnonymous()`·`isPublicExpired(Instant now)`. `create(...)`·`restore(...)`·`cancel(...)`과 그 호출부를 전수 수정한다(record가 아니라 클래스지만 생성자 시그니처가 바뀌므로 호환용 팩터리를 남기지 않는다 — 003 T013과 같은 이유). `backtest/infrastructure/persistence/BacktestRequestJpaEntity.java`(`:46-50` `requesterId`를 `nullable = true`, `publicToken` `@Column(length = 32, nullable = false, unique = true)`, `publicTokenExpiresAt`), `BacktestRequestMapper.java`. T008과 같은 MR
- [X] T011 (BE) 발급 — `backtest/application/service/BacktestService.java`의 요청 행 생성(NEW·JOINED·REUSED 세 경로 모두)과 `BacktestRetryService.java`의 재시도 요청 생성에서 `PublicResultToken.generate()`로 토큰을 발급하고, 요청자가 없으면 `publicTokenExpiresAt = now + ${thorp.result.public-token-ttl}`(`@Value`로 읽는다)(임시 기준은 생성 시각. PD-23이 정하면 바꾼다, R-03). `backtest/application/command/CreateBacktestCommand.java`의 `requesterId`를 `Long`(nullable)로, `RequestSpecCodec.toCommand`의 인자도 같다. `BacktestJobResult`에 `publicToken` 추가, `backtest/presentation/response/BacktestJobResponse.java`·`BacktestRetryResponse.java`에 `publicToken` 노출(`retryOf`는 유지 — 004 인증 경로의 응답이다)
- [X] T012 (BE) 익명 접수 허용 — `backtest/presentation/controller/BacktestController.java:103`의 `requireRequesterId()`를 `findRequesterId().orElse(null)`로 바꾼다(`CurrentRequester.findRequesterId`는 이미 있다). 취소·재시도·조회(`/backtests/**`의 나머지)는 그대로 소유자 인증이다 — 비로그인은 `/results/{token}`으로 본다. `backtest/application/service/ExecutionLimitPolicy.java`(`:64-106`): 요청자가 없으면 사용자별 상한을 건너뛰고 전체 상한만 검사한다. `LimitHitRecord`·`ExecutionMetricRecorder.java`(`:86-92`)·`BacktestRunRepositoryImpl.java`(`:191-202`)·`ExecutionMetricRepositoryImpl.java`(`:104-110`)가 null 요청자를 받는다. 이 판단(익명은 전체 상한만)을 MR 본문과 004 PD-06 담당에게 남긴다(원칙 IX: 상한 장치 없이 열지 않는다)
- [X] T013 (BE) `backtest/application/repository/BacktestRequestRepository.java`에 `findByPublicToken(PublicResultToken)`, `findExpiredAnonymous(Instant now, int limit)`, `deleteById(long)`를 더하고 `backtest/infrastructure/persistence/BacktestRequestRepositoryImpl.java`·`BacktestRequestJpaRepository.java`에 구현한다(만료 조회는 `requester_id IS NULL AND public_token_expires_at < :now`, T008의 부분 인덱스를 탄다)
- [X] T014 (BE) `backtest/application/service/BacktestResultQueryService.java`에 요청 기준 오버로드 — `getDetail(BacktestRequest)`, `getTimeseries(BacktestRequest)`, `getWeights(BacktestRequest)`, `getExceptions(BacktestRequest)`, `getTrades(BacktestRequest, FindBacktestTradesQuery)`, `getPositions(BacktestRequest, LocalDate)`. 기존 `(String backtestId, long requesterId)` 메서드(`:86`·`:119`·`:142`·`:153`·`:173`·`:202`)는 `requireOwned` 뒤 이 오버로드로 위임한다. Javadoc: "접근 판정은 호출자가 끝냈다. 공개 토큰 경로(`result`)와 소유자 경로가 같은 읽기를 쓴다"
- [X] T015 (BE) `/trades` 기간 필터 — `backtest/presentation/request/TradePageQuery.java`에 `LocalDate from`·`LocalDate to`(선택, `from ≤ to` 아니면 400)를 더하고 `FindBacktestTradesQuery`·거래 리포지토리 질의에 반영한다. `docs/specs/backtest-api/README.md` §2.3과 `specs/004-backtest-execution/contracts/rest-api.md`의 같은 자리를 갱신한다. 004 소유 영역이라 리뷰어에 004 담당을 지정한다(착수 전에 알아둘 것 ①)
- [X] T016 (DOCS) 004 계약 갱신 — `docs/specs/backtest-api/README.md`의 접수·재시도 응답에 `publicToken`, `docs/specs/backtest-api/examples/backtest-accepted.json`에 값 추가, `specs/004-backtest-execution/contracts/rest-api.md`의 `POST /backtests`·`/retry` 절에 같은 내용. `backend/src/test/java/com/ssafy/thorp/backtest/presentation/controller/BacktestResultApiIT.java`가 픽스처와 계속 맞는지 확인한다
- [X] T017 (BE 테스트) `backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java`에 규칙 8 추가 — `noClasses().that().resideInAPackage("com.ssafy.thorp.backtest..").should().dependOnClassesThat().resideInAPackage("com.ssafy.thorp.result..")`. Javadoc에 "공개 열람 경계 `result`는 `backtest`를 읽고, 방향은 `result → backtest` 하나다"를 적는다. **규칙이 동작하는지 일부러 어겨 확인한다**(003 T012 방식): `backtest` 안 클래스에 `result` 타입 import 한 줄을 넣어 실패를 본 뒤 지우고, 그 결과를 MR 본문 "테스트" 절에 적는다
- [X] T018 (BE 테스트) B01 검증 — `backend/src/test/java/com/ssafy/thorp/backtest/domain/model/BacktestRequestTest.java`(익명이면 만료 시각 필수 불변식, `isPublicExpired` 경계), `backtest/application/service/BacktestServiceTest.java` 확장(익명 접수 202·`publicToken` 32자·`expiresAt` = 생성 시각 + TTL, 로그인 요청은 `expiresAt` null, NEW·JOINED·REUSED 모두 토큰이 있음, 재시도도 새 토큰), `backtest/presentation/controller/BacktestSessionAuthIT.java` 확장(익명 `POST /backtests` 202, 익명 `GET /backtests/{id}` 401 유지), `backtest/application/service/ExecutionLimitApiIT.java` 확장(익명은 전체 상한만 세고 사용자별 상한에 걸리지 않는다), `common/persistence/FlywayMigrationTest.java`·`MigrationFileConventionTest.java` 통과. quickstart "전제"의 "공개 열람 경계가 병합되기 전에는 결과 화면이 뜨지 않는다"가 이 묶음으로 풀린다

**Checkpoint**: 비로그인으로도 `POST /backtests`가 202와 `publicToken`을 돌려주고, 로그인 요청은 만료가 없다. `backtest`는 `result`를 모른다.

---

## Phase 3: User Story 1 - 결과 첫 화면에서 핵심을 바로 이해한다 (Priority: P1) 🎯 MVP

**Goal**: 결과 주소를 열면 핵심 숫자 3개·그래프 1개(곡선 + 최대 하락 구간 음영)·해석 요약 1문장·조건 한 줄만 보이고 상세는 접혀 있다. 만료된 주소는 결과를 열지 않는다.

**Independent Test**: quickstart 1·2·7·8·10. 완료된 분석 하나를 `/results/{token}`으로 열어 네 가지만 보이고, 네트워크 탭의 요청이 `GET /results/{token}`·`/timeseries` 둘뿐이며, 토큰 뒷자리를 바꾸면 404, 만료되면 410 안내가 보인다. 폭 360px에서 세로로 쌓여 읽힌다. 그래프 자체는 US2의 B04가 만들지만 US1의 완성 판정에 들어간다(Implementation Strategy 참고).

### Backend — 공개 열람 경로

- [X] T019 [US1] (BE) `backend/src/main/java/com/ssafy/thorp/result/package-info.java` — 계층 규약(ADR-0001)과 "004의 결과를 공개 토큰으로 읽는 경계다. 값을 계산하지 않고 필드를 고른다. `backtest`를 읽되 `backtest`는 `result`를 모른다(ArchUnit 규칙 8)"를 명시. 하위 패키지 `presentation/{controller,response}`, `application/{service,result}`, `infrastructure/config`(T004)
- [X] T020 [US1] (BE) `result/application/service/PublicResultService.java` — `resolve(String token)`: `PublicResultToken.of` 실패 → `INVALID_INPUT`(400), `findByPublicToken` 없음 → `RESOURCE_NOT_FOUND`(404), 익명이고 `isPublicExpired(now)` → `RESULT_EXPIRED`(410), 아니면 `BacktestRequest`. `getDetail`·`getTimeseries`·`getTrades`·`getPositions`·`getWeights`·`getExceptions`는 T014 오버로드에, `retry(token)`은 `BacktestRetryService`에 위임한다. 상세는 `result/application/result/PublicResultDetail.java`로 옮겨 담으며 `publicToken`·`expiresAt`(로그인 결과는 null)·`validationTier = UNVALIDATED`(PD-12 확정 전 고정)·`strategyName`(`strategy/application/port/ParameterRegistryPort`에서 읽고 없으면 null)·`weightsSource`(저장 결과에서)·`exceptionKinds`(종결 상태일 때 `getExceptions`의 `kind` 중복 제거)를 더하고 `backtestId`를 담지 않는다. 재시도 결과의 `retryOf`는 원 요청의 공개 토큰(`retryOfToken`)으로 바꿔 담는다
- [X] T021 [US1] (BE) `result/presentation/controller/ResultController.java` `@RequestMapping("/results")` — `GET /{token}`, `/{token}/timeseries`, `/{token}/trades`(T015의 질의 + `size` 기본값과 상한을 `ResultProperties.tradesPageSize()`로 고정. 원칙 IX의 임시 상한, R-11), `/{token}/positions?date=`, `/{token}/weights`, `/{token}/exceptions`, `POST /{token}/retry`. `result/presentation/response/PublicResultDetailResponse.java`(`backtestId`·`runId`·`worker` 없음. `config` 사본에는 요청자가 원래 없다 — `RequestSpecCodec` 주석), `PublicResultRetryResponse{publicToken, status, resultSource, retryOfToken}`. 시계열·거래·보유·비중·예외는 004 response record에 `backtestId` 필드가 없으면 재사용하고, 있으면 공개용 record로 감싼다(contracts "공개 응답에서 버리는 필드"). SecurityConfig는 `/auth/me` 외 전부 `permitAll`이라 고치지 않는다. CSRF 쿠키 규약은 그대로 적용된다
- [ ] T022 [US1] (BE) 만료 정리 `result/application/service/PublicResultExpirySweeper.java` — `@Scheduled(fixedDelayString = "${thorp.result.expiry-sweep-interval:PT10M}")`(`news/infrastructure/scheduling/NewsRetentionScheduler.java` 선례, `common/config/SchedulingConfig.java`로 이미 활성). `findExpiredAnonymous(now, batchSize)`로 찾아 건마다 트랜잭션(`RunLivenessSweeper` 방식)으로 `backtest_reuse_event`의 참조 행은 지우지 않고 `request_id`만 NULL로 끊음(새 마이그레이션 `ALTER TABLE backtest_reuse_event ALTER COLUMN request_id DROP NOT NULL`과 `BacktestReuseEvent`·`BacktestReuseEventJpaEntity`의 `requestId` nullable을 같은 MR에 담는다. `uk_backtest_reuse_event_request`는 NULL 여러 개를 허용하므로 그대로 둔다. 원칙 IX의 동일 결과 재조회 기록 보존, `backtest_run_metric`의 `reuse_count`) → 자식 요청의 `retry_of_request_id`를 NULL → 요청 행 삭제. `backtest_run`·결과는 지우지 않는다(착수 전에 알아둘 것 ① 다섯째). 로그에는 건수만 남긴다(개인 식별 정보·토큰을 적지 않는다)
- [X] T023 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/result/application/service/PublicResultServiceTest.java` — 형식 오류 400, 없음 404, 익명 만료 410, 로그인 결과는 `expiresAt`이 null이고 만료되지 않음, 상세에 `backtestId` 없음, `validationTier`가 `UNVALIDATED`, `exceptionKinds` 중복 제거, 재시도의 `retryOfToken` 변환
- [X] T024 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/result/presentation/controller/ResultControllerTest.java`(MockMvc, 서비스 mock) — 일곱 경로의 상태 코드, `size` 상한 초과 시 상한으로 잘림, `from > to` 400, 익명 요청이 인증 없이 통과
- [X] T025 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/result/presentation/controller/ResultApiIT.java`(`BacktestResultApiIT` 방식, Testcontainers, `docs/specs/result-api/examples/`를 `Path.of("..", "docs", ...)`로 읽는다) — 익명 `POST /backtests` → 토큰으로 `GET /results/{token}`·`/timeseries` 200이고 응답이 `result-detail-completed.json`·`result-timeseries.json`과 필드 단위로 같다(quickstart 1); 응답 본문 어디에도 `BT-` 문자열 없음(quickstart 8, SC-009); 토큰 뒷자리를 바꾸면 404(SC-009); `public_token_expires_at`을 DB에서 과거로 바꾼 뒤 410이고 본문이 `result-expired.json`과 같다(quickstart 7, SC-008); 로그인 사용자의 결과는 같은 토큰으로 열리고 `expiresAt`이 null
- [ ] T026 [US1] (BE 테스트) `backend/src/test/java/com/ssafy/thorp/result/application/service/PublicResultExpirySweeperIT.java`(`news/infrastructure/persistence/NewsRetentionIT.java` 방식) — 만료된 익명 요청이 지워지고, 같은 `backtest_run`을 참조하는 로그인 요청과 run·결과는 남는다(004 FR-003); 재시도 자식의 참조가 NULL로 정리된다; 만료된 요청의 `backtest_reuse_event` 행은 남고 `request_id`만 NULL이며 `backtest_run_metric`의 `reuse_count`가 정리 전후로 같다; 만료 전 요청·로그인 요청은 그대로다; 배치 크기만큼만 처리한다

### Frontend — 첫 화면

- [X] T027 [US1] (FE) `frontend/src/api/result.ts` — `fetchResult(token, signal?)`, `fetchResultTimeseries`, `fetchResultTrades(token, {page, size, instrumentId, status, from, to})`, `fetchResultPositions(token, date)`, `fetchResultWeights`, `fetchResultExceptions`, `retryResult(token)`(`runCsrfProtectedMutation` 경유). `ResultError`·`normalizeResultError`는 `frontend/src/api/paramSearch.ts`의 `ParamSearchError` 방식으로, 코드 `RESULT_EXPIRED`·`RESOURCE_NOT_FOUND`·`INVALID_INPUT`·`RESULT_NOT_READY`·`BACKTEST_FAILED`·`NO_PARTIAL_RESULT`. 모든 호출은 `frontend/src/api/client.ts`의 `apiClient`를 거친다
- [X] T028 [US1] (FE) `frontend/src/hooks/useResult.ts` — 토큰으로 상세를 받고 `QUEUED`·`RUNNING`이면 `frontend/src/components/result/constants.ts`의 `RESULT_POLL_INTERVAL_MS`로 폴링, 종결 상태면 시계열을 받는다. 상태는 `loading | ready | expired | notFound | error`. **첫 화면에서 부르는 요청은 상세·시계열 둘뿐이다**(SC-001). 언마운트 시 `AbortSignal`로 취소
- [X] T029 [US1] (FE) `frontend/src/App.tsx`에 `/results/:token` 라우트 — 공개 경로(`ProtectedRoute`·`AuthEntryGuard` 밖), 다른 페이지와 같은 `lazy(() => import('@/pages/ResultPage').then(...))` 패턴. `frontend/src/pages/ResultPage.tsx` + `ResultPage.module.css` 골격: 조건 한 줄, 요약 카드 자리, 그래프 자리, 상태 문장 자리, '자세히 보기' 자리, 출처 줄. 정보 세 층(FR-020): 바로 보이는 것과 펼치는 것만 DOM에 두고 내부 계산 항목을 나열하지 않는다
- [X] T030 [US1] (FE) `frontend/src/components/result/conditionLine.ts` — `config`·`versions`·`strategyName`·`weightsSource`에서 분석 기간(`effectiveStart`~`effectiveEnd`)·비교 대상(기준 전략 이름과 비교 대상 이름 또는 '없음')·비용 적용 여부(`versions.costProfile`의 버전과 `provisional` 표시)를 **한 묶음**으로 만드는 순수 함수(FR-003). 세 항목을 어느 폭에서도 생략하지 않고 좁은 화면에서는 줄바꿈만 허용한다. 비중 출처: `COMPUTED`면 "비중은 선택한 규칙의 계산 결과이며 입력한 비중과 다르다"(FR-014). `strategyName`이 없으면 `strategyId`를 그대로 보이고 지어내지 않는다
- [X] T031 [US1] (FE) `frontend/src/components/result/ResultSummaryCard.tsx` — 핵심 숫자 3개(비용 차감 후 전체 수익률·가장 크게 줄었던 폭·거래비용)를 텍스트로, 각 숫자 옆 정의 한 줄(FR-002: 운용 중 고점 대비 최대 하락이며 시작 금액 대비 손실과 다르다), 조건 한 줄(T030), 종료 시 시가평가와 청산 비용 포함의 구분(FR-015, `summary.liquidation{markToMarket, liquidated}`). `summaryUnavailable`이면 숫자 자리에 0이나 대체값 없이 "취소 시점까지의 지표는 계산되지 않았습니다"와 완료 범위(`progress.lastTradingDate`)를 보인다(FR-013). 수익률과 최대 하락 폭이 모두 0이면 그 사실을 한 문장으로 설명하고 좋은 결과로 표시하지 않는다(Edge Case). 문자열 값은 자릿수만 다듬고 다시 계산하지 않는다
- [X] T032 [US1] (FE) `frontend/src/components/result/summarySentences.ts` — 해석 요약 1문장의 고정 문장 틀(FR-006, R-07, data-model §3-5). 틀: 비교 대상 없음(세 값), 비교 대상 있음(수익률·최대 하락 폭의 차이 방향별), 차이 없음(둘 다 같음), 거래비용만 다름, 부분 결과 접두("완료 범위까지의 값이다"). `selectSummarySentence({base, target, partial})`가 조건에 맞는 틀을 골라 값 자리만 채운다. 머리 주석: "이 파일은 SC-004의 검수 대상이다. 화면이 문장을 새로 짓지 않는다. 금지 어휘는 `summarySentences.test.ts`가 막는다"
- [X] T033 [US1] (FE) `frontend/src/components/result/ResultUnavailableNotice.tsx` — 410: "결과가 더 이상 남아 있지 않습니다"와 같은 조건으로 다시 분석할 진입점(조건이 남아 있지 않으므로 `/strategy`로 가는 연결). 404·400: 잘못된 주소 안내(만료와 다른 문구, contracts "오류"). 어느 쪽도 숫자 자리·그래프 자리를 그리지 않는다(SC-008: 빈 값이나 0으로 채운 결과 화면 금지)
- [X] T034 [US1] (FE) `frontend/src/pages/ResultPage.tsx` 조립 — `useResult` + 카드 + 요약 문장 + 안내. 좁은 화면(360px)에서 요소가 세로로 쌓이고 가로 스크롤이 없다(`ResultPage.module.css`, spec Assumptions). KRX 출처 줄 '한국거래소 통계정보'(FR-020). 핵심 숫자 3개와 조건 한 줄은 텍스트로 존재해 스크린 리더가 읽는다(FR-022). 주 모양 버튼 자리는 비교 대상 고르기(T050) 하나에만 남기고, 이 태스크가 두는 '자세히 보기' 자리는 보조 모양이다(착수 전에 알아둘 것 ⑧). 그래프(T044)·상태 문장(T075)·상세(T064)·비교(T050)는 뒤 태스크가 끼운다

### Frontend 테스트

- [X] T035 [US1] (FE 테스트) `frontend/src/components/result/summarySentences.test.ts` — 틀 전체 문자열에 금지 어휘('추천', '최적', '승자', '우승', '유리', '좋다', '할 것', '유사', '확률') 0건(SC-004, quickstart 4); 조건 분기별로 다른 틀이 선택된다; 값 자리가 채워진 문장에 `undefined`·`NaN`이 없다; 부분 결과 접두
- [X] T036 [US1] (FE 테스트) `frontend/src/components/result/ResultSummaryCard.test.tsx` — 숫자 3개·정의·조건 한 줄·청산 구분이 렌더된다; `summaryUnavailable`이면 텍스트 '0'이 없고 "계산되지 않았습니다"와 완료 범위가 있다; 0·0 결과 문장; `COMPUTED`면 비중 출처 문구, `USER`면 없음
- [X] T037 [US1] (FE 테스트) `frontend/src/pages/ResultPage.test.tsx` — 완료 결과에서 `vi.mock('@/api/result')`의 호출이 `fetchResult`·`fetchResultTimeseries` 각 1회뿐이고 `fetchResultTrades`·`fetchResultWeights`·`fetchResultExceptions`와 `fetchStrategyRegistry`(`vi.mock('@/api/strategyRegistry')`)는 0회(SC-001, quickstart 1); 410이면 안내와 다시 분석 진입점이 있고 숫자 텍스트가 없다(quickstart 7); 404 문구가 410과 다르다(quickstart 8); DOM 전체에 `BT-` 문자열 0건(SC-009); `RUNNING`이면 폴링 뒤 `COMPLETED`로 바뀐다
- [X] T038 [US1] (FE 테스트) `frontend/src/api/result.test.ts` — 경로 조립(토큰을 그대로 쓰고 인코딩하지 않는다), 오류 정규화 6종, `retryResult`가 CSRF 보호 mutation을 탄다, `size`·`from`·`to`가 질의 문자열에 실린다
- [X] T039 [US1] (FE 테스트) `frontend/src/test/resultFixtures.test.ts` — TS 픽스처와 `docs/specs/result-api/examples/*.json`을 `node:fs`로 읽어 키 집합과 `status`·`validationTier` 값이 같음을 확인한다. 프론트에서 계약 픽스처를 대조하는 첫 관례이므로 MR 본문에 그 사실을 적는다(지금까지 프론트는 `docs/specs`를 주석으로만 참조했다)

**Checkpoint**: 완료된 결과가 주소로 열리고 네 요소만 보이며, 만료·없음이 서로 다르게 안내된다. 그래프는 B04가 끼운다.

---

## Phase 4: User Story 2 - 같은 조건에서 두 결과를 비교한다 (Priority: P1)

**Goal**: 자산가치 곡선 위에 최대 하락 구간 음영을 겹쳐 그리고, 비교 대상을 고르면 기준과 같은 조건으로 004에 실행을 요청해 완료되면 두 곡선을 색에 기대지 않고 구분해 그린다. 기간이 다르면 겹쳐 그리지 않는다.

**Independent Test**: quickstart 3·9. 기준 결과에서 다른 전략을 고르면 요청이 나가고 완료 전에는 기준만 그려지며 비교 자리에 진행 상태가 보인다. 완료 뒤 두 곡선의 기간·금액·비용이 조건 한 줄에서 같다. 워밍업이 긴 전략은 고르기 전에 "비교 기간이 짧아진다"가 보이고, 고르면 재계산 확인이 뜬다. 회색조로 봐도 실선·파선·이름표로 구분된다.

### Frontend — 그래프

- [X] T040 [US2] (FE) `frontend/src/components/result/drawdownWindow.ts` — `findDrawdownWindow(points, summaryMaxDrawdown)`: `drawdown` 계열의 최솟값 지점(저점)과 그 직전에 `equity`가 가장 높았던 날(고점)을 `{peakDate, troughDate}`로 돌려준다. 저점의 `drawdown`이 `summary.maxDrawdown`과 다르면 `null`(R-05, 원칙 VI). 회복 시점은 구간에 넣지 않는다(FR-002). 머리 주석: "004가 `summary.maxDrawdownWindow`를 주면 그 값을 쓰고 이 함수를 지운다(spec Assumptions의 유일한 계산 예외)"
- [ ] T041 [US2] (FE) `frontend/src/components/result/EquityChart.tsx`(plan의 `ComparisonChart`) — `frontend/src/components/charts/line-chart.tsx`의 `LineChart`·`Line`으로 세로축 원화 자산가치(사용자가 입력한 금액에서 출발), 기준 실선·비교 대상 파선(`Line`의 `dashArray`), 시작 금액 수준의 기준선(`Grid`의 `highlightRow*`). **점 수를 줄이지 않는다** — `decimate-time-series.ts`를 import하지 않는다(R-08, FR-001). 두 계열의 날짜 축을 병합한다. `equity`가 없는 날은 선을 끊어 빈 채로 두고 앞뒤 값을 잇거나 보간하지 않는다(spec Edge Case, 원칙 VI. `Line`에 끊는 수단이 없으면 T042와 같은 한 곳에서 최소로 더한다). `role="img"`와 대안 텍스트(세 숫자와 비교 대상 유무를 문장으로, FR-022)
- [ ] T042 [US2] (FE) `frontend/src/components/result/DrawdownShade.tsx` — 고점~저점 구간 음영: 색 채움과 **상단 점선 경계**를 함께 그려 회색조에서도 경계가 보인다(R-09). `LineChart`의 자식으로 x 스케일을 읽어 `<rect>`로 그린다. 자식이 스케일을 읽을 수단이 없으면 `frontend/src/components/charts/line-chart.tsx`에 자식용 스케일 컨텍스트를 최소로 추가한다(차트 시스템 변경은 이 한 곳으로 제한하고 MR 본문에 적는다). 비교 대상의 음영은 빗금 패턴으로 기준과 구분한다
- [ ] T043 [US2] (FE) `frontend/src/components/result/SeriesEndLabel.tsx` — 곡선 오른쪽 끝에 직접 붙는 이름표(기준·비교 대상의 전략 이름). 두 이름표가 겹치면 위아래로 민다. 범례만으로 구분하지 않는다(SC-010). 차트 시스템에는 이름표 컴포넌트가 없어 새로 만든다(`line-series-terminal-marker.tsx`는 점만 그린다)
- [ ] T044 [US2] (FE) `frontend/src/pages/ResultPage.tsx`에 `EquityChart`를 끼운다 — 기준만 있으면 곡선 1개와 음영, `targetState === 'READY'`일 때만 두 곡선. 음영 구간이 `null`이면 숫자와 정의만 보이고 음영은 없다(quickstart 2의 정상 동작). 좁은 화면에서 `aspectRatio`를 조정한다. 구간은 `ResultPage`가 이미 계산해 둔 `lossWindow`를 쓰고, '손실이 커진 때 보기'가 열려 있으면(`lossView.open`) 그 구간을 그래프에서 강조한다(FR-010. B07이 보기만 만들고 강조는 그래프를 그리는 이 태스크로 넘겼다)

### Frontend — 비교 대상 선택과 실행 요청

- [ ] T045 [US2] (FE) `frontend/src/components/result/compareQuery.ts` — `parseCompareQuery(search)` → `{compareToken, searchId}`(토큰이 32자 16진이 아니면 무시), `buildResultPath(baseToken, {compareToken?, searchId?})`. 비교를 위한 자원을 서버에 만들지 않는다(R-04). 비교 대상 토큰만 떼어 단독으로 여는 것은 의도된 동작이다(FR-019)
- [ ] T046 [US2] (FE) `frontend/src/hooks/useCompareTarget.ts` — `?compare=`의 상세·시계열을 받아 `targetState`(`NONE | REQUESTED | RUNNING | READY | FAILED | EXPIRED`)를 만든다. 410이면 `EXPIRED`로 두고 기준은 그대로(Edge Case "비교 대상 쪽만 만료"). 진행 중이면 폴링. `comparisonKind`는 `searchId`가 있거나 대상 `config.executionType === 'SEARCH_BUNDLE'`이면 `AUTO_SEARCH`, 아니면 `BASIC`(FR-011)
- [ ] T047 [US2] (FE) `frontend/src/api/backtest.ts` — `createBacktest(request: BacktestCreateRequest): Promise<BacktestAccepted>`(`frontend/src/types/backtestRequest.ts`의 `BacktestAccepted`에 `publicToken` 추가), 오류 정규화: `EXECUTION_LIMIT`(본문 `retryAfterSeconds`), `INSUFFICIENT_HISTORY`(본문의 필요 값), `REGISTRY_VERSION_CHANGED`, `RULES_NOT_REGISTERED`, `INVALID_INPUT`. `runCsrfProtectedMutation` 경유. 003 B06 T039와 같은 파일이다 — 먼저 병합되는 쪽이 만든다(착수 전에 알아둘 것 ③). 접수 응답의 `backtestId`는 화면이 보관하지 않는다
- [ ] T048 [US2] (FE) `frontend/src/components/result/compareRequest.ts` — `buildCompareRequest(baseConfig, strategyId, registryVersion, warmupTradingDays?)`: 기준 `config`(요청 원문)에서 섹터 후보 집합·평가 날짜·금액·비용·정보 이용 기준·`warmupTradingDays`를 그대로 옮기고 전략만 바꾼다(contracts "비교 요청", FR-004). `executionType: 'SINGLE'`. `config`의 키 이름이 `BacktestCreateRequest`와 다르면 대응표를 이 파일 한 곳에 둔다. 화면은 값을 계산하지 않는다
- [ ] T049 [US2] (FE) `frontend/src/components/result/CompareTargetPicker.tsx` — 사용자가 선택 목록을 열 때 `fetchStrategyRegistry`(`frontend/src/api/strategyRegistry.ts`)를 처음 부른다 — 첫 화면을 그리는 동안에는 부르지 않는다(SC-001). 다섯 전략 중 기준 외 후보를 보이고, 후보의 `warmupTradingDays`가 기준 `config`의 워밍업보다 길면 **고르기 전에** "이 전략을 고르면 비교 기간이 N일 짧아집니다"를 보인다(R-13, FR-004. 값은 등록표에서 읽기만 한다). `selectable: false`는 `unselectableReason`과 함께 비활성. 자동 탐색 후보는 007이 주소로 넘기므로 이 목록에 두지 않는다. 키보드만으로 고를 수 있다(FR-022). 고르면 `onSelect(strategyId)`
- [ ] T050 [US2] (FE) 실행 요청 흐름(`frontend/src/hooks/useCompareTarget.ts` 확장) — 고르면 `createBacktest(buildCompareRequest(...))` → 응답 `publicToken`으로 `?compare=`를 갱신(`navigate`, 히스토리 대체). `resultSource`가 `REUSED`면 즉시 READY, `NEW`·`JOINED`면 진행 상태(결과가 이미 있는지 화면이 따로 찾지 않는다 — 004의 동일 버전 재사용이 판정한다). 429면 `EXECUTION_LIMIT` 상태와 `retryAfterSeconds`를 보이고 **사용자가 누를 때만** 다시 요청한다(원칙 IX). 비교 대상을 바꿔도 앞선 실행을 취소하지 않는다(FR-004). `ResultPage`에 `CompareTargetPicker`를 끼우고, 그 여는 버튼을 화면의 유일한 주 모양 버튼으로 둔다(착수 전에 알아둘 것 ⑧)
- [ ] T051 [US2] (FE) `frontend/src/components/result/CompareTargetSlot.tsx` — 비교 대상 자리: 진행 상태(`progress.stage`의 단계 이름, `completedTradingDays/totalTradingDays` 비율, `QUEUED`면 `queueWaitSeconds`로 지금까지 기다린 시간), 한도(429) 안내와 '다시 요청' 버튼, 실패(`failure.code`·`message` 한 문장, 낮은 수익으로 표현하지 않는다)와 '다시 시도'(`retryResult(compareToken)`), 만료 안내와 '다시 고르기', READY면 대상 이름과 '비교 해제'. 어느 경우도 자동으로 다시 실행하지 않는다(Edge Cases). 버튼은 키보드로 조작한다(FR-022)
- [ ] T052 [US2] (FE) 기간 불일치 — `frontend/src/components/result/PeriodMismatchDialog.tsx`와 흐름: 대상이 READY인데 `config.effectiveStart/End`가 기준과 다르면 겹쳐 그리지 않고(FR-005) 공통 구간과 "두 결과를 모두 다시 계산해야 합니다"를 보인다. 확인하면 두 건을 공통 `warmupTradingDays`(둘 중 큰 값)로 `createBacktest` 두 번 → `navigate(buildResultPath(newBase, {compareToken: newTarget}), {state: {returnTo: 현재 주소}})`. 화면은 `state.returnTo`가 있으면 '원래 결과로 돌아가기' 연결을 보인다(FR-005 후단: 원본과 그 주소·기록은 남는다). 004의 `422 INSUFFICIENT_HISTORY` 본문의 필요 값을 같은 대화상자에 넣어 잇는다(contracts, 안전장치). 확인 전에는 기준만 그린다. 값을 조용히 올려 재요청하지 않는다
- [ ] T053 [US2] (FE) 비교 종류 표시 `frontend/src/components/result/ComparisonKindBadge.tsx`(`ResultPage.tsx`의 비교 대상 자리 위에 끼운다) — `comparisonKind === 'AUTO_SEARCH'`면 '자동 탐색 후보 비교' 표지와 `/param-search?searchId={searchId}`(`ParamSearchPage`가 읽는 질의 인자)로 가는 연결을 보이고, `BASIC`이면 둘 다 없다(SC-011, FR-011). 007의 `CompareHandoff.onOpenCompare` 연결은 007 몫이다(착수 전에 알아둘 것 ②)
- [ ] T054 [US2] (FE) 비교 문장·조건 한 줄·대안 텍스트 갱신(`frontend/src/pages/ResultPage.tsx`, `frontend/src/components/result/conditionLine.ts`) — `selectSummarySentence`의 비교 틀(방향·차이 없음·거래비용만) 사용, 조건 한 줄에 비교 대상 이름, 그래프 대안 텍스트에 비교 대상 유무(FR-022). 자동 배분 전략을 비교 대상으로 두면 비중 출처 구분을 유지한다(FR-014)

### Frontend 테스트

- [X] T055 [US2] (FE 테스트) `frontend/src/components/result/drawdownWindow.test.ts` — 합성 계열에서 고점·저점 날짜; `summary.maxDrawdown`과 어긋나면 `null`; 저점이 여러 날이면 처음 것; 1,900점 입력에서도 같은 결과; 회복일이 구간에 없다
- [ ] T056 [US2] (FE 테스트) `frontend/src/components/result/EquityChart.test.tsx` — 두 계열이면 `stroke-dasharray`가 있는 경로와 이름표 텍스트 둘이 DOM에 있다(SC-010의 자동 확인 부분, R-09); 음영 `<rect>`와 점선 경계가 있고 구간이 `null`이면 없다(quickstart 2); 입력 점 수와 렌더 점 수가 같다(축소 없음); `role="img"` 대안 텍스트에 세 숫자; 기준만 있으면 파선이 없다; `equity`가 빠진 날이 있으면 경로가 그 자리에서 끊긴다(보간 없음)
- [ ] T057 [US2] (FE 테스트) `frontend/src/components/result/compareQuery.test.ts` — 파싱·조립 왕복, 형식 오류 무시, `searchId` 보존, 비교 해제 시 기준 주소만 남음
- [ ] T058 [US2] (FE 테스트) `frontend/src/components/result/CompareTargetPicker.test.tsx` — 워밍업이 긴 전략에만 "짧아집니다" 문구(R-13); `selectable: false`는 이유와 함께 비활성; 키보드로 선택; 등록표 실패 시 목록 대신 사유
- [ ] T059 [US2] (FE 테스트) `frontend/src/hooks/useCompareTarget.test.tsx`와 `frontend/src/components/result/CompareTargetSlot.test.tsx` — 고르면 `createBacktest`가 1회 호출되고 본문의 섹터·기간·금액·비용·워밍업이 기준 `config`와 같으며 전략만 다르다(SC-003, quickstart 3); `REUSED`면 즉시 READY; RUNNING이면 단계·비율, QUEUED면 기다린 시간; 429면 `retryAfterSeconds`가 보이고 자동 재요청 0회, 버튼을 누르면 1회; FAILED면 사유와 '다시 시도'가 `retryResult`를 부른다; 410이면 기준이 그대로 그려지고 안내가 있다; 비교 대상을 바꿔도 취소 호출이 없다
- [ ] T060 [US2] (FE 테스트) `frontend/src/components/result/PeriodMismatchDialog.test.tsx` — 기간이 다르면 두 곡선을 그리지 않고 대화상자가 뜬다; 확인하면 `createBacktest` 2회 모두 공통 워밍업이다; 이동 경로에 새 두 토큰이 있고 `returnTo`로 원래 주소 연결이 보인다; 422 본문의 필요 값이 대화상자에 반영된다; 취소하면 아무 요청도 없다
- [ ] T061 [US2] (FE 테스트) `frontend/src/pages/ResultPage.test.tsx` 확장 — `?search=`이면 '자동 탐색 후보' 표지와 묶음 연결이 있고 없으면 둘 다 없다(SC-011); 비교 상태에서 요약 문장이 비교 틀이다; 대상 `executionType`이 `SEARCH_BUNDLE`이면 `search` 없이도 표지가 있다; 주 모양 버튼은 비교 대상 고르기 하나뿐이다(착수 전에 알아둘 것 ⑧)

**Checkpoint**: 두 결과가 같은 조건으로만 한 그래프에 그려지고, 조건이 다르면 확인 뒤 재계산으로 이어진다. 회색조에서도 구분된다.

---

## Phase 5: User Story 3 - 상세 근거를 결과 근처에서 찾는다 (Priority: P2)

**Goal**: '자세히 보기'가 화면을 옮기지 않고 열리며 FR-007의 항목이 모두 있다. 계산이 제한된 항목은 제한으로 보이고 0으로 채워지지 않는다. '손실이 커진 때 보기'와 '다른 조건으로 비교'가 다음 분석으로 이어진다.

**Independent Test**: quickstart 6. '자세히 보기'를 눌러 주소가 바뀌지 않고 항목이 모두 있는지, 대표 상품 정보가 그 안에서 펼쳐지는지, '다른 조건으로 비교'가 조건이 채워진 `/strategy`로 이어지는지 확인한다.

### Frontend

- [X] T062 [US3] (FE) `frontend/src/api/sectors.ts`에 `fetchInstrumentProfile(code, signal?)`(`GET /sectors/{code}/instrument-profile`, 계약 `specs/001-etf-market-data/contracts/sector-api.md`)와 대응 타입을 더한다. 001 소유 파일이라 리뷰어에 001 담당을 지정한다(착수 전에 알아둘 것 ④)
- [X] T063 [US3] (FE) `frontend/src/components/instrument/InstrumentProfileDisclosure.tsx` — 001 대표 상품 정보를 접힌 항목으로 펼치는 **공용** 컴포넌트(R-10): 구성종목 중복·대표 ETF 구성·출처·한계 문구는 응답 문자열을 그대로 보이고 005가 다시 쓰지 않는다(FR-007). 머리 주석에 "001의 화면도 이 컴포넌트를 쓴다. 문구의 소유자는 001이다"
- [X] T064 [US3] (FE) `frontend/src/components/result/DetailPanel.tsx` — '자세히 보기' 버튼으로 결과 근처에서 여는 패널(주소 변경 없음, SC-006). 열 때 `/weights`·`/exceptions`를 받는다(첫 화면에서는 부르지 않는다, SC-001). 항목: 회복 기간·회전율(`summary.analytics`, 값이 없으면 계산 제한 표시), 상관관계·위험 기여·손실 기여("제공되지 않음" — 004가 노출하기 전. 추정 기간·산정법 자리를 함께 둔다), 비용 가정·비교 규칙(`config` + `versions`), 목표·실제 비중(`weightsSource`로 사용자 입력과 규칙 계산을 구분, FR-014), 계산 제한 항목(`exceptions`의 `kind`·`rule`을 그대로. 0·임의 값 없음, FR-009), 섹터 후보 집합 **전체**의 대표 ETF별 `InstrumentProfileDisclosure`(실제 보유 여부와 무관, 접힘 기본, 연 것만 조회). 키보드로 열고 닫으며 닫으면 초점이 버튼으로 돌아온다(FR-022)
- [X] T065 [US3] (FE) `frontend/src/components/result/TradeHistory.tsx` — 전체 거래 내역의 페이지 조회(`fetchResultTrades`, 한 번에 받는 건수는 `constants.ts`의 `TRADES_PAGE_SIZE` 한 곳, R-11), 종목·기간·상태 필터를 서버 질의로 넘긴다(Edge Case "기간·종목으로 좁혀"). 패널 안의 '거래 내역'을 열 때만 부른다. 첫 화면에 나열하지 않는다
- [X] T066 [US3] (FE) '손실이 커진 때 보기' `frontend/src/components/result/LossWindowView.tsx` — T040의 구간 **하나**를 그래프에서 강조하고(FR-010, 임계값 없음) 그 구간의 거래(`from`·`to` 질의), 저점일 보유(`/positions?date=troughDate`), 거래별 비용 열을 보인다. 구간이 `null`이면 버튼을 비활성하고 이유("최대 하락 구간을 확인할 수 없습니다")를 적는다. 구간 합계는 004가 주는 값이 없으므로 내지 않는다(원칙 VI)
- [X] T067 [US3] (FE) `frontend/src/components/result/HoldingDifference.tsx` — 비교 대상이 READY이고 두 결과의 `/weights` 실제 보유 섹터 집합이 다르면 상세에 "기준에 없던 섹터·빠진 섹터"(선정 효과)와 공통 섹터의 비중을 나란히(배분 효과) 보인다(FR-008). 새 숫자를 계산하지 않고 두 결과의 값을 나란히 둔다
- [X] T068 [US3] (FE) '다른 조건으로 비교' `frontend/src/components/result/prefillStrategySettings.ts` — 기준 `config`를 002의 `writeStoredSelection(SectorSelectionSnapshot)`(`frontend/src/diagnosis/selectionStorage.ts`)과 003의 `StoredStrategySettings`(`frontend/src/hooks/useStrategySettings.ts`의 `STRATEGY_SETTINGS_STORAGE_KEY`·`STRATEGY_SETTINGS_PAYLOAD_VERSION`)로 저장한 뒤 `/strategy`로 이동한다(FR-010). 003 모듈에 쓰기 함수가 없으므로 `writeStoredStrategySettings`를 그 파일에 더한다(003 소유, 리뷰어 지정). 006의 '설정 바꿔 비교'도 이 함수를 쓴다(FR-019). 값을 몰래 바꾸지 않는다 — 등록표 범위 밖 값의 처리는 003 화면(`reconcileStoredSettings`)이 한다

### Frontend 테스트

- [X] T069 [US3] (FE 테스트) `frontend/src/components/instrument/InstrumentProfileDisclosure.test.tsx` — 응답 문자열이 그대로 보인다; 접힘 기본; 여러 ETF가 각각 접힌다; 조회 실패 시 즉석 문장 없이 '불러오지 못했습니다'
- [X] T070 [US3] (FE 테스트) `frontend/src/components/result/DetailPanel.test.tsx` — 열기 전 `fetchResultWeights`·`fetchResultExceptions` 0회, 연 뒤 각 1회(SC-001); 열어도 `location`이 같다(SC-006, quickstart 6); FR-007 항목 10종의 제목이 있다; `analytics`가 없으면 '계산 제한'이 있고 텍스트 '0'이 없다(FR-009); 미노출 3항목이 '제공되지 않음'; 대표 상품 정보가 패널 안에 있고 이동이 없다; 키보드로 닫으면 초점이 버튼으로 돌아온다
- [X] T071 [US3] (FE 테스트) `frontend/src/components/result/TradeHistory.test.tsx`와 `LossWindowView.test.tsx` — 페이지·필터가 질의 인자로 넘어간다; 구간 보기의 `from`·`to`가 고점·저점 날짜이고 `fetchResultPositions`가 저점일로 불린다; 구간이 `null`이면 비활성과 이유; 첫 렌더에서 거래 조회 0회
- [X] T072 [US3] (FE 테스트) `frontend/src/components/result/prefillStrategySettings.test.ts` — 저장 키·페이로드 버전·값이 `config`와 같다; 이동 경로가 `/strategy`; 기준 결과의 값을 바꾸지 않는다

**B07 구현에서 태스크와 달라진 것** ([S15P21E205-311](https://ssafy.atlassian.net/browse/S15P21E205-311)):

- **T040·T055를 B07이 만들었다.** T066이 같은 구간을 써야 하는데 B04가 시작 전이었다. 착수 전에 알아둘 것 ③과 같은 방식(먼저 병합되는 쪽이 만든다)이며, B04는 이 함수를 재사용하고 T041~T044·T056만 한다. **고점은 T040 문구와 다르게 고른다**: 워커는 고점을 시작 금액에서 시작하므로(`worker/src/worker/portfolio_execution.py`의 `self.peak`) 저점 앞에서 `drawdown`이 0인 마지막 날, 그런 날이 없으면 계열의 첫날이 고점이다. '저점 직전에 `equity`가 가장 높았던 날'로 찾으면 시작 금액 아래에서 출발한 결과의 구간을 늦게 잡는다.
- **T062는 할 일이 없었다.** 001이 `fetchInstrumentProfile`을 이미 `frontend/src/api/sectors.ts`에 만들었다.
- **T063은 새 공용 컴포넌트를 만들지 않았다.** 001의 `components/diagnosis/InstrumentProfilePanel.tsx`가 이미 R-10이 말한 공용 패널이라, 005는 섹터마다 접는 틀만 `components/result/InstrumentProfileDisclosure.tsx`에 두고 그 패널을 쓴다(`components/instrument/`는 만들지 않았다). 섹터 이름과 대표 ETF를 알려고 패널을 열 때 `GET /sectors`를 부르며, **지금 대응표의 대표 ETF가 결과가 쓴 종목과 다르면 펼치지 않는다** — 상품 정보 조회가 섹터 코드로 지금의 ETF를 돌려주기 때문이다.
- **T066의 버튼 비활성·이유는 `ResultPage`에 있다.** '자세히 보기'·'손실이 커진 때 보기'·'다른 조건으로 비교'를 한 줄에 두려고 여는 버튼은 페이지가, 보기는 컴포넌트가 가진다(`hooks/useDisclosure.ts`). 그래프 위 강조는 T044로 넘겼다.
- **T067은 `DetailPanel`의 `compare` 인자로 연결한다.** 비교 대상이 아직 없어(B05) 지금은 넘기는 곳이 없다. B05 T050이 대상이 READY일 때 `{token, name, items}`를 넘기면 보인다.
- **T068은 누를 때 등록표를 받는다.** 결과에 최소 평가 기간이 없어 003이 처음 열릴 때와 같은 등록표 기본값을 넣으려면 등록표가 필요하다. 그 기본값으로 기간이 짧다고 판정되면 003 화면이 알린다. 003의 `writeStoredSettings`는 보관 성공 여부를 돌려주는 `writeStoredStrategySettings`로 공개했다(003 소유, 리뷰어 지정). 규칙이 비중을 계산한 결과는 002가 손대지 않은 선택처럼 `distributeEven`으로 넘기되, 잔여분이 002 표시 순서의 첫 섹터에 붙도록 섹터 목록을 함께 받는다. 설정 화면이 다루지 않는 별도 시나리오(`scenario`가 `FULL_PERIOD`가 아닌 결과)는 조건을 바꿔 넘기지 않고 사유를 보인다. **003이 이어받을 수 있을 때만 넘긴다** — 섹터 목록을 함께 받아 대응표 버전이 결과와 다르거나 쓸 수 있는 섹터가 둘 미만이면 보관값을 건드리지 않고 사유를 보인다(`useSectorSelection`이 그런 선택을 앞 단계로 되돌리기 때문이다). 같은 탭에 보관된 다른 전략의 설정값과 앞서 고른 최소 평가 기간은 지우지 않는다. 설정 보관이 실패하면 먼저 쓴 002 선택을 앞선 값으로 되돌린다.
- **B07이 더한 파일**: `hooks/useResource.ts`(열 때 받는 자원. 다시 받는 동안 앞선 응답을 남긴다), `hooks/useDisclosure.ts`(여닫기와 초점 복귀), `hooks/useSectorCatalogState.ts`(섹터 목록. 대응표 미발행·빈 목록은 다시 불러오기를 준다), `components/result/{ResourceArea.tsx,sectorLabels.ts,OtherConditionsButton.tsx,resultDetail.module.css}`. `ResourceArea`는 불러오는 중·실패·받은 내용을 한 자리에 그리고, 페이지를 넘기거나 다시 불러올 때 키보드 초점을 잃지 않게 한다(FR-022). 거래·보유·비중·예외 기록이 부분 결과(`partial`)면 완료 범위까지의 기록이라고 적는다. `types/result.ts`의 `ResultTradePage`에 빠져 있던 `partial`을 더했다(004 §2 "하위 리소스 다섯 개는 모두 `partial`을 싣는다"). 새 픽스처 넷을 004 예시와 키 집합으로 대조하다 드러났다.

**Checkpoint**: 상세 근거가 결과 근처에서 열리고, 계산 제한은 제한으로, 미노출은 미제공으로 보인다.

---

## Phase 6: User Story 4 - 결과의 상태와 한계를 결과 옆에서 본다 (Priority: P2)

**Goal**: 결과 의미를 바꾸는 네 갈래 상태와 검증 구분이 결과 옆 한 문장으로 보이고, 경고가 목록으로 늘지 않는다.

**Independent Test**: quickstart 5. 실행 도중 취소한 요청을 열어 숫자 자리가 0으로 채워지지 않고 "계산되지 않았습니다"와 완료 범위가 보이는지, 자료가 부족한 결과에 한 문장이 있는지, 내부 검증 미완료가 경고로 나열되지 않는지 확인한다.

- [ ] T073 [US4] (FE) `frontend/src/components/result/statusSentences.ts` — 네 갈래(FR-013, R-12)의 고정 문장 틀: 부분 완료(`partial` + `progress.lastTradingDate`로 완료 범위를 날짜로), 지표 없음(`summaryUnavailable`의 `CANCELLED_WHILE_RUN_CONTINUED`·`CANCEL_UNANSWERED`), 자료 부족·계산 제한(`exceptionKinds`와 `summary.exceptionCount`로 종류·건수), 실행 실패(`failure.code`·`message`. 낮은 수익으로 표현하지 않는다). 검증 구분(`validationTier`)은 "검증되지 않았습니다. 판정 기준이 아직 정해지지 않았습니다"(FR-012, PD-12 확정 전). 값 자리만 비운 틀이며 T035와 같은 금지 어휘 검사를 받는다
- [ ] T074 [US4] (FE) `frontend/src/components/result/ResultStatusLine.tsx` — 결과 옆(요약 카드 바로 아래) 한 문장 자리. 해당하는 갈래만 보이고, 여럿이면 문장을 이어 쓰되 목록으로 늘리지 않는다. 결과 의미를 바꾸지 않는 항목(내부 검증 미완료 등)은 여기 두지 않는다(FR-013 후단). 검증 구분은 항상 보인다. 비교 대상 자리에도 대상의 상태 문장을 같은 틀로 보인다
- [ ] T075 [US4] (FE) `frontend/src/pages/ResultPage.tsx`에 `ResultStatusLine`을 끼우고 취소 결과·실패 결과·부분 결과의 화면 흐름을 맞춘다 — 취소(`CANCELLED`)는 카드가 완료 범위까지의 값을 보이고, `summaryUnavailable`이면 숫자 없이 문장만(T031), 실패(`FAILED`)는 사유와 '다시 시도'(`retryResult`)가 결과 자리에 있다. 종료 시 평가 구분(FR-015)·비중 출처(FR-014)·'미검증' 표시가 카드와 상태 줄에서 한 번씩만 보인다
- [ ] T076 [US4] (FE 테스트) `frontend/src/components/result/statusSentences.test.ts`(네 갈래 문장, 금지 어휘 0건, '미검증' 문구에 이유가 함께 있다, FR-012)와 `frontend/src/components/result/ResultStatusLine.test.tsx`(취소 결과에 완료 범위 날짜와 "계산되지 않았습니다"가 있고 숫자 '0'이 없다 — quickstart 5, SC-005; 예외가 있으면 한 문장; 예외가 없으면 자료 부족 문장이 없다; 실패면 사유와 '다시 시도'; `<ul>` 형태의 경고 목록이 없다)

**Checkpoint**: 결과 의미를 바꾸는 상태가 결과 옆에 한 문장으로 보이고 상세에만 숨지 않는다.

---

## Phase 7: User Story 5 - 보조 보기와 다음 탐색으로 이어간다 (Priority: P3)

**Goal**: 지수 비교와 목표 위험 맞춤을 요청해야 열리는 보조 보기로 두고, '경제환경이 비슷했던 과거 보기'·'설정 바꿔 비교'·'내보내기' 진입점을 둔다. 다른 spec의 화면이 없으면 진입점은 이유와 함께 닫혀 있다.

**Independent Test**: 결과에서 지수 비교 보조 보기를 열어 참고 정보로 표시되고 기본 비교 기준(`?compare=`)이 바뀌지 않는지, 목표 위험 맞춤이 별도 시나리오로 표시되는지, 011·006 진입점이 조건을 유지한 채 열리거나(구현되면) 이유와 함께 닫혀 있는지 확인한다.

- [ ] T087 [US5] (BE) 지수 비교 자원 `GET /results/{token}/index-comparison` — `PublicResultService.getIndexComparison(token)`과 `ResultController`에 경로를 더한다. 결과의 `effectiveStart`~`effectiveEnd`와 시작 금액으로 KOSPI 종가를 환산한 계열(`date`·`value`, 문자열)을 서버가 만든다(FR-017, spec Assumptions: 화면은 계산하지 않는다). 지수 자료는 001이 소유한 조회 경로에서 읽는다. 지금 `GET /markets/kospi`(`market/presentation/controller/MarketController.java`)는 기간 질의가 없으므로, 기간 조회가 가능한 포트가 없으면 `available: false`와 사유(`INDEX_SERIES_UNAVAILABLE`)를 돌려주고 값을 지어내지 않는다. 결과 기간 안에 지수가 빠진 날은 빈 채로 둔다. 001 소유 조회를 쓰므로 리뷰어에 001 담당을 지정한다
- [ ] T088 [US5] (BE 테스트) `result/application/service/PublicResultServiceTest.java`·`result/presentation/controller/ResultControllerTest.java` 확장 — 환산 계열의 첫 값이 시작 금액과 같고 결과 기간 밖 날짜가 없다; 기간 자료가 없으면 `available: false`와 사유이며 계열이 비어 있다; 만료 토큰은 410; 응답에 `backtestId`가 없다
- [ ] T077 [US5] (FE) `frontend/src/components/result/IndexComparisonView.tsx` — 요청해야 열리는 보조 보기. `frontend/src/api/result.ts`에 `fetchResultIndexComparison(token)`을 더해 T087의 환산 계열을 **받은 그대로** 별도 패널에 그리고 "참고: 지수를 시작 금액으로 환산했습니다. 비교 기준을 바꾸지 않습니다"를 적는다(FR-017). 화면은 환산하지 않는다(spec Assumptions의 계산 예외는 최대 하락 구간 하나뿐이다). `available: false`면 이유와 함께 비활성이다. 첫 화면 그래프에 겹치지 않는다(원칙 II: 그래프 1개)
- [ ] T078 [US5] (FE) `frontend/src/components/result/TargetRiskView.tsx` — '목표 위험 맞춤' 보조 보기: 기준 `config`에 `conditions.scenario: 'TARGET_RISK'`와 `scenarioParams.targetVolatility`(등록표 `analysisConditions.targetVolatility`의 `default`)를 넣어 `createBacktest`(T047) → 별도 시나리오 표지와 함께 세 숫자만 보인다(FR-017). `failure.code`가 `ESTIMATION_FAILED`·`DATA_INSUFFICIENT`면 계산 제한 표시. 429·422 처리는 T050·T052의 흐름을 재사용한다. 요청하기 전에는 API를 부르지 않는다
- [ ] T079 [US5] (FE) `frontend/src/components/result/NextStepLinks.tsx` — '경제환경이 비슷했던 과거 보기'(FR-018): 넘길 값(섹터 후보 집합·전략·설정값·분석 기간·금액)을 `config`에서 준비하되, 011의 진입 계약과 라우트가 없으면 버튼을 누를 수 없게 두고 이유를 적는다(007 `CompareHandoff`가 005 없이 한 방식). '설정 바꿔 비교'는 T068을 재사용하고, '내보내기'는 006 소유라 006 화면이 없으면 같은 방식으로 닫아 둔다(FR-019). 돌아오는 길은 브라우저 뒤로 가기다. 로그인 여부와 무관하게 결과 화면은 열린다
- [ ] T080 [US5] (FE 테스트) `frontend/src/components/result/IndexComparisonView.test.tsx`·`TargetRiskView.test.tsx`·`NextStepLinks.test.tsx` — 보조 보기는 열기 전 API 호출 0회(SC-001); 지수 보기는 `fetchResultIndexComparison`의 값을 그대로 그리고 화면에서 환산하지 않는다(응답 값과 렌더 값이 같다), `available: false`면 이유와 함께 비활성; 지수 보기를 열어도 `?compare=`가 바뀌지 않는다; 목표 위험 요청 본문에 `scenario`와 `targetVolatility`가 있고 별도 시나리오 표지가 보인다; 추정 실패면 계산 제한 표시; 011·006이 없으면 비활성 문구가 있고 다른 버튼 동작에 영향이 없다

**Checkpoint**: 다섯 스토리가 모두 독립적으로 동작한다.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 문서를 구현과 맞추고, 잔재와 금지 사항을 검사하고, 전체 검증을 돈다

### 문서

- [ ] T081 [P] (DOCS) `specs/005-result-comparison/{plan.md,research.md,data-model.md,quickstart.md}` 동기화 — `features/result/` → `components/result/`(plan Project Structure, research R-07, data-model §3-5, quickstart 4), `PublicResultToken` 위치(`backtest/domain/model`, 순환 회피), 공개 상세 응답의 추가 필드(`strategyName`·`weightsSource`·`exceptionKinds`)와 `?search=` 인자·`/trades`의 `from`·`to`·재시도 응답(T001에서 contracts에 이미 적었다), plan "004에 필요한 변경"에 이 tasks가 찾은 접점 둘(거래 기간 필터, 익명 실행 상한)과 그 처리를 추가, plan "작업 묶음 구성"을 B01~B09로 갱신. 004가 `maxDrawdownWindow`·`analytics` 노출을 넣었으면 T040·T064의 임시 처리를 지웠는지 함께 확인한다
- [ ] T082 [P] (DOCS) `frontend/CLAUDE.md` "규칙"에 두 줄 — "결과 화면(`/results/:token`)은 `backtestId`(`BT-…`)를 받지도 보이지도 않는다(spec 005 SC-009)"와 "해석 요약·상태 문장은 `components/result/{summarySentences,statusSentences}.ts`의 틀에서만 나온다(SC-004). 문장을 컴포넌트에서 짓지 않는다". `backend/CLAUDE.md` "규칙"에 한 줄 — "`result` 패키지는 공개 토큰 열람 경계다. `backtest`는 `result`를 참조하지 않는다(ArchUnit 규칙 8). 비로그인 요청은 `requester_id` NULL이고 `public_token_expires_at`이 필수다"
- [ ] T083 (DOCS) [quickstart.md](quickstart.md) 1~10 수동 확인을 돌리고 결과를 MR 본문 "테스트" 절에 항목별로 적는다. 004 변경 ③·④가 아직이면 2(음영 생략)와 상세 3항목(제공되지 않음)을 기대 동작으로 적는다. 007 연결이 아직이면 3의 자동 탐색 후보 부분은 `?search=`를 직접 넣어 확인하고 그 사실을 적는다
- [ ] T083a (DOCS) 사용성 관찰 항목(SC-002·SC-007·SC-010) — PD-14 회차에 과제 셋(상세를 열지 않고 세 숫자의 뜻을 말하기, 진행자 도움 없이 손실이 커진 때·거래 내역·비용 가정 찾기, 회색조 인쇄물에서 기준과 비교 대상 구분하기)을 넣고, 관찰 기록과 조치 내역을 `specs/005-result-comparison/` 아래 결과 문서로 남긴 뒤 MR 본문에 요약한다. 두 명 이상이 같은 지점에서 오해하면 요약 화면을 고친다(SC-002). **코드 구현이 아니라 관찰 절차이므로 B09 병합을 막지 않으며, 회차 일정은 002·003과 함께 잡는다**(003 T074a 방식)

### 코드 정리·금지 검사

- [ ] T084 [P] (FE) 잔재·금지 검사 — `grep -rn "BT-\|backtestId" frontend/src/components/result frontend/src/pages/ResultPage.tsx frontend/src/api/result.ts frontend/src/types/result.ts frontend/src/hooks/useResult.ts frontend/src/hooks/useCompareTarget.ts`가 0건(테스트·픽스처 제외, SC-009); `grep -rn "decimate" frontend/src/components/result` 0건(R-08); 전략 ID 문자열 리터럴(`FIXED_WEIGHT` 등) 0건(003 T076과 같은 검사, 화면은 `strategyName`·`weightsSource`·`executionType`으로만 분기); `<ul>`로 나열한 경고 목록이 `ResultStatusLine`에 없다
- [ ] T085 [P] (BE) 잔재 검사 — `grep -rn "requireRequesterId" backend/src/main/java/com/ssafy/thorp/result` 0건(공개 경로에 인증 요구 없음); `result/presentation/response/` record에 `backtestId`·`runId`·`requesterId` 컴포넌트 0건; `PublicResultToken`이 로그 문자열에 찍히지 않는다(`grep -rn "publicToken" backend/src/main/java --include=*.java | grep -i "log\."` 0건)

### 전체 검증

- [ ] T086 (DOCS) 전체 검증 — `cd backend && ./gradlew spotlessApply build`(ArchUnit 규칙 8·`FlywayMigrationTest` 포함), `cd frontend && npm run lint && npm run build && npm test`(`skipped` 0 확인), `infra/scripts/check-migration-version.sh`(버전 역전 없음). 결과를 MR 본문 "테스트" 절에 적는다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음. T001이 contracts를 마무리해야 T002·T005·T006이 같은 모양을 쓴다
- **Foundational (Phase 2)**: T004(설정)에 의존한다. T003(오류 코드 `RESULT_EXPIRED`)은 B02의 공개 열람에서만 쓰므로 B01을 막지 않는다. **모든 스토리를 막는다** — B01이 병합되기 전에는 서버와 붙여 볼 수 없다
- **User Stories (Phase 3~7)**: US1의 백엔드(B02)는 B01 뒤. US1 프론트엔드(B03)는 T002 픽스처만 있으면 B02 병합 전에 출발할 수 있다(실제 서버와 붙여 보는 것만 B02 뒤). US2·US3·US4는 B03 뒤, US5는 US2(B05)와 US3(B07) 뒤
- **Polish (Phase 8)**: 모든 묶음 뒤

### User Story Dependencies

- **US1 (P1)**: B01·B02·B03·B04(그래프)로 완성된다. 다른 스토리에 의존하지 않는다
- **US2 (P1)**: US1의 화면 골격과 그래프를 쓴다. `createBacktest`(T047)는 003 B06과 같은 파일이다
- **US3 (P2)**: US1의 골격만 있으면 된다. `HoldingDifference`(T067)만 US2의 비교 대상이 있을 때 보인다
- **US4 (P2)**: US1의 골격만 있으면 된다. US2·US3과 독립이다
- **US5 (P3)**: 목표 위험 보기는 US2의 요청 흐름(T050·T052)을, '설정 바꿔 비교'는 US3의 T068을 재사용한다

### 작업 묶음 = 티켓·브랜치·MR 단위 (B01~B09)

**`T###`는 티켓 단위가 아니다.** constitution "Jira 연계"가 `작업 묶음 = 1 Branch = 1 MR`로 정한다. 아래 아홉이 Jira Task·브랜치·MR의 단위이고, 89개 태스크는 그 안에서 실행 지시의 최소 단위로 남는다.

**묶음 티켓은 아직 만들지 않았다.** AGENTS.md가 "곧 브랜치를 딸 작업 묶음이 아니면 티켓을 세우지 않는다"고 정한다. 각 묶음에 착수할 때 Jira Task를 먼저 만들고(제목은 아래 표의 것, 에픽 `S15P21E205-30 백테스트`, 구현 대상 Story와 `relates to`, 담당·스프린트·포인트), 그 번호로 브랜치를 만든 뒤 이 표의 티켓 열을 채운다.

**묶음 이름은 계층이 아니라 목적으로 붙였다**(003과 같은 방식). 이름이 그대로 Jira Task 제목이 된다.

| 묶음 · 티켓 | Jira Task 제목 | 포함 T### | 수 | position · type | 선행 |
|---|---|---|---|---|---|
| B01 · [S15P21E205-291](https://ssafy.atlassian.net/browse/S15P21E205-291) `feat(be)/291-public-result-token` | `[BE] 비로그인 요청을 받고 결과마다 공개 토큰을 발급한다` | T004, T008~T018 | 12 | `be` · `feat` | 없음 |
| B02 · [S15P21E205-318](https://ssafy.atlassian.net/browse/S15P21E205-318) `feat(be)/318-public-result-read` | `[BE] 공개 토큰으로 결과를 열고 만료되면 닫는다` | T001, T002, T003, T019~T026 | 11 | `be` · `feat` | B01 |
| B03 · 미발급 | `[FE] 결과 첫 화면에서 핵심을 읽는다` | T005, T006, T007, T027~T039 | 16 | `fe` · `feat` | B02의 계약(T001·T002) |
| B04 · 미발급 | `[FE] 자산가치 곡선과 하락 구간을 색에 기대지 않고 그린다` | T040~T044, T055, T056 (T040·T055는 B07이 먼저 만들었다) | 7 | `fe` · `feat` | B03 |
| B05 · 미발급 | `[FE] 비교 대상을 골라 같은 조건으로 요청한다` | T045~T054, T057~T061 | 15 | `fe` · `feat` | B04 (+ 003 B06과 파일 조율) |
| B06 · 미발급 | `[FE] 결과 옆 한 문장으로 상태와 한계를 보인다` | T073~T076 | 4 | `fe` · `feat` | B03 |
| B07 · [S15P21E205-311](https://ssafy.atlassian.net/browse/S15P21E205-311) `feat(fe)/311-result-detail-panel` | `[FE] 자세히 보기에서 근거를 찾는다` | T062~T072 | 11 | `fe` · `feat` | B03 (+ 001·003 소유 파일 리뷰) |
| B08 · 미발급 | `[FE] 보조 보기와 다음 탐색으로 이어간다` | T077~T080, T087, T088 | 6 | `fe` · `feat` | B05, B07 (+ 001 소유 조회 리뷰) |
| B09 · 미발급 | `[DOCS] 문서 동기화와 최종 검증` | T081~T086(T083a 포함) | 7 | `fe` · `docs` | 전부 |

```text
B01 ─▶ B02 ─▶ B03 ─┬─▶ B04 ─▶ B05 ─┬─▶ B08 ─┐
                   ├─▶ B07 ────────┘        ├─▶ B09
                   └─▶ B06 ─────────────────┘
```

**plan의 A~E를 아홉으로 나눈 이유.** A(공개 열람 경계)는 "비로그인 접수·토큰 발급"(B01)과 "공개 열람·만료"(B02)로 갈랐다. B01만으로도 "비로그인 사용자가 분석을 요청하고 접수 응답에 공개 토큰이 온다"가 성립해 독립적으로 검증·롤백할 수 있고, 스키마 변경(T008)과 새 패키지(`result`)를 한 MR에 넣으면 리뷰어가 한 번에 읽기 어렵다. B(첫 화면)는 "화면"(B03)과 "상태 한 문장"(B06)으로 갈랐다 — US4가 별도 스토리이고 취소·실패 결과로 따로 검증된다. C(비교 그래프)는 B04, D(비교 대상 선택·실행 요청)는 B05, E(상세 패널)는 B07이며 US5의 보조 보기(B08)와 문서 마무리(B09)는 plan에 없던 묶음이다.

**각 묶음이 끝나면 무엇이 달라지나.** 묶음 조건 1(하나의 목적)을 만족하는지 보는 기준이다.

| 묶음 | 끝나면 |
|---|---|
| B01 | 로그인하지 않아도 분석을 요청할 수 있고, 모든 요청에 추측할 수 없는 공개 토큰이 붙는다. `backtest`는 `result`를 모른다 |
| B02 | 공개 토큰으로 결과·시계열·상세 자원을 읽고, 만료된 비로그인 결과는 410으로 닫히며 정리 작업이 요청 행을 지운다 |
| B03 | 결과 주소를 열면 핵심 숫자 3개·해석 요약 1문장·조건 한 줄이 보이고, 만료와 없음이 다르게 안내된다 |
| B04 | 자산가치 곡선과 최대 하락 구간 음영이 한 그래프에 그려지고, 두 곡선이 실선·파선·이름표로 구분된다 |
| B05 | 비교 대상을 고르면 같은 조건으로 요청이 나가고, 진행·한도·실패·만료·기간 불일치가 각각 제자리에 보인다 |
| B06 | 결과 의미를 바꾸는 상태 네 갈래와 검증 구분이 결과 옆 한 문장으로 보인다 |
| B07 | '자세히 보기'가 화면을 옮기지 않고 열리며 FR-007 항목과 대표 상품 정보가 그 안에 있다 |
| B08 | 지수 비교·목표 위험 보기가 요청해야 열리고, 011·006 진입점이 조건을 들고 있거나 이유와 함께 닫혀 있다 |
| B09 | 문서가 구현과 같고 금지 검사가 0건이며 양쪽 빌드가 돈다 |

**묶음을 계층으로 더 쪼개지 않는다.** B03이 16개로 가장 크지만 "첫 화면"은 통째로만 검증할 수 있다 — API 모듈과 훅만 먼저 올리면 앞 조각이 혼자서는 아무것도 보이지 않아 묶음 조건 3(미완성 task 없음)을 어긴다. 크기를 고르게 맞추는 것보다 목적이 하나인 편이 중요하다.

- **B01이 나머지 전부를 막는다.** 토큰과 익명 접수가 없으면 `result` 패키지가 열 것이 없다
- **B03은 B02의 계약 부분만 기다린다** — [contracts/result-api.md](contracts/result-api.md)가 응답 모양을 고정했고 T006 픽스처로 화면 테스트가 돈다. B02가 T001·T002를 먼저 커밋하면 머지 전에도 프론트엔드가 출발할 수 있다. 실제 서버와 붙여 보는 것(quickstart 1·7·8)만 B02 머지 뒤다
- **B06과 B07은 B04·B05와 병렬이다** — 셋 다 B03의 골격만 쓰고 파일이 겹치지 않는다(`ResultPage.tsx`에 끼우는 한 줄씩만 겹치므로 먼저 병합된 쪽 위에 리베이스한다)
- **B05는 003 B06(T039)과 `frontend/src/api/backtest.ts`를 나눠 쓴다.** 먼저 병합되는 쪽이 파일을 만들고 다른 쪽은 재사용한다(착수 전에 알아둘 것 ③). 따로 뺀 이유는 막히지 않은 것이 막힌 것 때문에 함께 서지 않게 하려는 것이다
- **B07은 001·003 소유 파일에 함수를 더한다**(T062·T068). 두 담당을 리뷰어로 지정하고 MR 본문에 어느 파일을 왜 만졌는지 적는다
- **묶음 안의 태스크를 절반만 올리지 않는다.** 특히 B01은 T008·T010·T018이 함께 있는 상태로만 MR을 올린다(스키마 변경 규칙). 커밋으로는 나누되 브랜치는 나누지 않는다
- **묶음마다 Jira Task를 먼저 만들고 그 번호로 브랜치를 만든다**(constitution). 브랜치는 `{type}({position})/{티켓번호}-{설명}`이며 B01·B02는 `feat(be)`, B03~B08은 `feat(fe)`, B09는 `docs(fe)`다

### 파트 사이 순서

- 백엔드(B01·B02)가 먼저다. 프론트엔드는 픽스처로 B03을 병행하되 서버 확인은 B02 뒤로 미룬다
- 004 담당이 리뷰할 접점(T008·T010~T015)은 B01·B02 MR을 열 때 리뷰어로 지정한다. 004에 요청한 나머지 변경(③·④·⑤)은 이 spec의 묶음을 막지 않는다 — 임시 처리(T040·T064·T022)가 각각 자리를 지킨다
- 007·001·003·006·011과의 경계는 착수 전에 알아둘 것 ②~④와 T079가 정한 대로 "먼저 있는 쪽을 쓰고 없으면 이유와 함께 닫아 둔다"

### Within Each User Story

- 순수 함수·문장 틀(`summarySentences`·`drawdownWindow`·`compareQuery`·`compareRequest`·`statusSentences`)을 먼저 만들고 테스트로 고정한 뒤 컴포넌트가 쓴다
- API 모듈 → 훅 → 컴포넌트 → 페이지 조립 순서다
- 테스트 태스크는 대응하는 구현 태스크 뒤에, 같은 묶음 안에 둔다

### Parallel Opportunities

- Setup: T002·T003·T004·T005·T006·T007은 T001 뒤 모두 병렬이다
- B01 안: T009는 T008과 병렬, T013·T014·T015는 T010 뒤 병렬
- B03 안: T027·T030·T032·T033은 병렬(다른 파일), T034가 모은다
- B05 안: T045·T047·T048·T049는 병렬, T050이 모은다
- 서로 다른 묶음: B04·B06·B07은 B03 뒤 서로 병렬(사람이 여럿이면)

---

## Parallel Example: User Story 1 (B03)

```bash
# 순수 함수·API·안내 화면을 한꺼번에 시작한다(다른 파일, 의존 없음):
Task: "frontend/src/api/result.ts — fetch·retry 함수와 오류 정규화" (T027)
Task: "frontend/src/components/result/conditionLine.ts — 조건 한 줄 순수 함수" (T030)
Task: "frontend/src/components/result/summarySentences.ts — 해석 요약 문장 틀" (T032)
Task: "frontend/src/components/result/ResultUnavailableNotice.tsx — 410·404 안내" (T033)

# 테스트도 구현 뒤 병렬로:
Task: "summarySentences.test.ts — 금지 어휘 0건" (T035)
Task: "ResultSummaryCard.test.tsx — 숫자 3개·정의·청산 구분" (T036)
Task: "api/result.test.ts — 경로·오류 정규화" (T038)
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. B01 — 비로그인 접수와 토큰 발급(스키마 변경 포함)
2. B02 — 공개 열람 API와 만료
3. B03 — 첫 화면(숫자 3개·문장 1개·조건 한 줄·만료 안내)
4. B04 — 그래프(곡선 + 음영 + 색 비의존 구분)
5. **STOP and VALIDATE**: quickstart 1·2·7·8·10. 이 네 묶음으로 US1이 완성된다. 상세·비교 없이도 "결과를 열어 핵심을 이해한다"가 성립한다

### Incremental Delivery

1. B05 → US2: 비교 대상 선택·요청·기간 불일치 (quickstart 3·9)
2. B06 → US4: 결과 옆 한 문장 (quickstart 5)
3. B07 → US3: 상세 패널 (quickstart 6)
4. B08 → US5: 보조 보기·다음 탐색
5. B09 → 문서·검사·전체 검증
6. 004의 변경 ③·④가 들어오면 T040·T064의 임시 처리를 지우는 작은 MR을 따로 낸다(그때 티켓을 만든다)

### Parallel Team Strategy

백엔드 한 사람이 B01→B02를 순서대로, 프론트엔드 한 사람이 픽스처로 B03을 병행한다. B03 뒤에는 B04→B05 한 줄과 B06·B07을 나눠 맡을 수 있다. spec 005의 담당은 한 사람이므로(specs/README.md) 실제로는 B01→B02→B03→B04→B05→B06→B07→B08→B09 순서가 기본이고, 위 병렬은 사람이 늘 때의 선택지다.

---

## Notes

- [P] = 다른 파일·의존 없음. 같은 파일(`ResultPage.tsx`·`useCompareTarget.ts`)을 만지는 태스크는 순서대로 한다
- 값을 계산하는 코드는 T040(최대 하락 구간 찾기) 하나뿐이며 spec Assumptions가 허용한 유일한 예외다. 그 밖의 숫자는 004가 준 문자열을 자릿수만 다듬어 보인다(원칙 VI)
- 문장은 `summarySentences.ts`·`statusSentences.ts` 두 파일에서만 나온다. 컴포넌트가 문장을 만들면 SC-004의 검수 대상이 흩어진다
- `backtestId`(`BT-…`)는 백엔드 `result` 패키지의 응답과 프론트엔드 결과 화면 어디에도 없어야 한다(SC-009). T084·T085가 검사한다
- 각 묶음의 MR 본문 "주요 변경 사항"에 포함한 T### 번호를 나열해 추적한다(constitution "Jira 연계")
