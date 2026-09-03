# Quickstart: 백테스트 결과 조회 API 검증

**Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/](./contracts/) | **Data model**: [data-model.md](./data-model.md)

이 문서는 기능이 끝까지 동작하는지 확인하는 절차만 담는다. 구현 내용은 `tasks.md`와 코드에 있다.

## 사전 조건

- JDK 21, Docker(PostgreSQL 16)
- 저장소 루트에서 `docker compose -f docker-compose.dev.yml up -d postgres`
- (Windows/WSL) WSL에 JDK가 없으면 Windows 쪽 `gradlew.bat`으로 같은 명령을 실행한다

## 1. 자동 검증 (Phase마다 실행, 통과 필수)

```bash
cd backend
./gradlew spotlessApply build
```

포함되는 것: Spotless 포맷 적용·검사, 전체 단위·슬라이스·`@DataJpaTest`(H2) 테스트, `ArchitectureTest`(ArchUnit 6개 규칙).

기대 결과: `BUILD SUCCESSFUL`. 실패 상세는 `backend/build/test-results/test/*.xml`과 `backend/build/reports/tests/test/index.html`.

특히 확인할 테스트:

| 테스트 | 확인 내용 |
|---|---|
| `BacktestResultRepositoryImplTest` | `equity_curve` JSON 컬럼 라운드트립(H2), `backtest_job_id` unique 위반 시 예외 |
| `BacktestResultTest` | 첫/마지막 포인트 equity ≠ summary 값이면 생성 실패 |
| `BacktestControllerTest` | `GET /backtests/{id}` 200(config·summary 필드), `GET /backtests/{id}/timeseries` 200/404/409 |
| `ArchitectureTest` | domain에 Spring·JPA·Jackson 없음, Entity는 `infrastructure.persistence`에만 |

## 2. dev 기동 + 시더 확인

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

기동 로그에 `DevSampleResultSeeder`가 예시 전략 3개를 삽입했다는 메시지가 나온다. 다시 기동하면 "이미 결과가 있어 건너뜀" 로그만 나오고
행 수가 늘지 않아야 한다(멱등).

```bash
docker exec thorp-postgres psql -U thorp -d thorp -c "select id, strategy, status, initial_cash from backtest_job order by id;"
docker exec thorp-postgres psql -U thorp -d thorp -c "select backtest_job_id, total_return, max_drawdown, metric_definition_version, jsonb_array_length(equity_curve) from backtest_result order by id;"
```

기대: Job 3행(COMPLETED, `initial_cash = 10000000`), 결과 3행(`jsonb_array_length = 20`).

## 3. API 수동 검증 (contracts와 필드명·타입 대조)

```bash
# US1: 완료된 Job → config + summary
curl -s localhost:8080/backtests/1 | jq
# 기대: data.status == "COMPLETED", data.config.initialCash == 10000000,
#       data.summary.initialEquity == 10000000, data.summary.totalReturn == 1.248,
#       data.summary.winRate == null, data.summary.metricDefinitionVersion == "1.0"

# US2: 시계열
curl -s localhost:8080/backtests/1/timeseries | jq '.data.points | length'          # 20
curl -s localhost:8080/backtests/1/timeseries | jq '.data.points[0]'                # equity 10000000, cumulativeReturn 0, drawdown 0
curl -s localhost:8080/backtests/1/timeseries | jq '.data.points[-1].equity'        # 22480000

# 정합(FR-010): 마지막 equity / 첫 equity - 1 == summary.totalReturn
curl -s localhost:8080/backtests/1 | jq '.data.summary.totalReturn'                 # 1.248
curl -s localhost:8080/backtests/1/timeseries | jq '(.data.points[-1].equity / .data.points[0].equity) - 1'   # 1.248

# 404
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/backtests/999                # 404
curl -s localhost:8080/backtests/999/timeseries | jq '.error.code'                  # "RESOURCE_NOT_FOUND"

# POST: initialCash 생략 → 기본값, 202, 응답에 config 포함·summary null
curl -s -X POST localhost:8080/backtests -H 'Content-Type: application/json' \
  -d '{"strategy":"trend","startDate":"2019-01-01","endDate":"2023-12-31"}' | jq
# 기대: HTTP 202, data.status == "QUEUED", data.config.initialCash == 10000000, data.summary == null

# POST: initialCash 지정
curl -s -X POST localhost:8080/backtests -H 'Content-Type: application/json' \
  -d '{"strategy":"trend","startDate":"2019-01-01","endDate":"2023-12-31","initialCash":50000000}' | jq '.data.config.initialCash'   # 50000000

# POST: initialCash 0 이하 → 400 INVALID_INPUT
curl -s -X POST localhost:8080/backtests -H 'Content-Type: application/json' \
  -d '{"strategy":"trend","startDate":"2019-01-01","endDate":"2023-12-31","initialCash":0}' | jq '.error.code'   # "INVALID_INPUT"

# 409: 방금 만든 QUEUED Job(id는 위 POST 응답의 backtestId)의 시계열
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/backtests/4/timeseries      # 409
curl -s localhost:8080/backtests/4/timeseries | jq '.error.code'                    # "RESULT_NOT_READY"
curl -s localhost:8080/backtests/4 | jq '.data.summary'                             # null (상태 조회는 200)
```

## 4. 체크리스트

- [ ] `./gradlew spotlessApply build` 통과(ArchitectureTest 포함)
- [ ] 응답이 모두 `ApiResponse` 래퍼(`success`, `data`, `error`) 형식
- [ ] `GET /backtests/{id}`: COMPLETED → summary 채움, QUEUED → `summary: null`
- [ ] `GET /backtests/{id}/timeseries`: 첫 equity = `initialEquity`, 마지막 equity로 계산한 총 수익률 = `totalReturn`
- [ ] 없는 id 404 `RESOURCE_NOT_FOUND`, 미완료 409 `RESULT_NOT_READY`
- [ ] `POST /backtests` 기존 요청(initialCash 없음)이 202 + 기본값으로 동작
- [ ] 시더 재기동 시 중복 삽입 없음
- [ ] 응답 JSON이 `contracts/*.schema.json`·`contracts/examples/*.json`과 필드명·타입 일치
