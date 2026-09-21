# 내 기록 검증 기록 (quickstart S1~S13)

**실행일**: 2026-09-21 | **브랜치**: `docs(be)/401-my-records-verification-docs` (dev `e44c3c45` 기준) | **Quickstart**: [../quickstart.md](../quickstart.md)

**확인한 방식**: 자동 테스트와 로컬 스택 수동 확인 둘 다다. 담당자(신재은)가 2026-09-21에 quickstart 사전 조건대로
스택(postgres·rabbitmq·backend·worker·frontend)을 띄우고 S1~S13을 화면으로 끝까지 밟았고 **전부 통과**했다. 아래 표의 `자동`은
그 시나리오의 판정 조건을 단언하는 테스트가 이번 실행에서 통과했다는 뜻이고, `수동`은 사람이 스택에서 본 항목이다.

## 검증 명령

| 명령 | 결과 |
|---|---|
| `cd backend && ./gradlew spotlessApply build` | BUILD SUCCESSFUL (3m 11s). 테스트 1982건, 실패 0, skipped 4 — `MarketLiveApiTest`·`NewsPerformanceIT`·`RequiredDockerTest`로 006 밖이며 환경 변수로 켜는 테스트다. `myrecord` 패키지 14개 클래스 76건 모두 실행·통과(Docker 켜고 실행, skipped 0) |
| `cd frontend && npm run lint && npm run build` | 통과 |
| `cd frontend && npm test` → `test:dom` | Vitest 139 파일 1311건 통과, skipped 0. `src/components/records/*.test.ts`는 `vite.config.ts` `test.include`에 이미 있어 `reopenSettings.test.ts`가 수집된다 |
| `cd frontend && npm test` → `test:legacy` | 이 PC의 Node 22.15에서는 `node --test`가 `.ts`를 읽지 못해 4파일이 실패한다(`ERR_UNKNOWN_FILE_EXTENSION`). `NODE_OPTIONS=--experimental-strip-types`로 다시 돌리면 13건 통과. 네 파일은 모두 001·009 시장 자료 테스트라 006과 무관하다. 로컬 Node 버전 문제이며 코드 변경 없음 |
| `grep -rn "my_record" --include=*.java backend/src/main/java \| grep -i "schedul\|expire\|cleanup\|purge"` | 결과 없음(S11) |

## 시나리오별 결과

| 시나리오 | 결과 | 근거 |
|---|---|---|
| S1 목록·다시 보기 | 자동 + 수동 통과 | `MyRecordsPage.test` "fixture 세 종류를 …", "행마다 주 모양 버튼은 다시 보기 하나다"(S1-4), "자세히를 열면 참조 버전…"(S1-7); `MyRecordApiIT` 접수한_항목의_목록과_상세. '다시 보기'는 `/results/{token}?from=records&recordId=`로 가는 링크라 새 요청을 만들지 않는다(`recordLabels.reviewHref`). S1-6(RabbitMQ 큐에 새 작업 없음)·S1-8(버전·식별자 입력 요구 없음)은 수동으로 확인 |
| S2 자료 갱신 뒤 동일 결과 | 수동 통과 | 새 `dataset_version`을 적재한 뒤 같은 기록을 다시 열어 핵심 숫자 3개가 같음을 수동으로 확인. 결과는 `backtest_result` 저장값을 005 읽기 경로로 그대로 읽으며(`MyRecordExportService`·`ResultPage`), 재계산 경로가 없다 |
| S3 설정 바꿔 비교 | 자동 + 수동 통과 | `reopenSettings.test` "reopenPayload 를 005 함수에 그대로 넘긴다 — … warmupTradingDays 도 그대로다", "대응표가 달라 섹터를 넘기지 못하면 … reselectSectors"; `MyRecordsPage.test` "설정 바꿔 비교는 상세를 받아 … 옮긴다"; `MyRecordReopenIT` 축 하나 변경 → 새 행, 원본 `backtest_result` 불변(SC-003). S3-2·S3-3(`thorp.strategySettings`의 `registryVersion`이 저장 당시 값이고 `warmupTradingDays` 키가 없음)과 S3-5·S3-6은 수동으로 확인 |
| S4 같은 조건 재분석 | 자동 | `MyRecordReopenIT` 동일 조건 → 행 수 불변(FR-009); `MyRecordRegistrarTest` "second SINGLE request on the same run → same record" |
| S5 대응표 변경 세 경우 | 자동 | `MappingDriftCheckerTest` 5건(버전만 오름 → `changed=false`, 대표 ETF 교체 → `INSTRUMENT_REPLACED`, 섹터 삭제 → `SECTOR_REMOVED`); `ResultPage.records.test` "판정 근거가 없으면 문장을 덧붙이지 않는다" |
| S6 등록표 범위 밖 값 | 자동 + 수동 통과 | `RegistryDriftTest` 좁힌 범위 → `outOfRange`·`fields`에 저장값 그대로. 축을 비우고 복원 선택을 보이는 것은 003 `useStrategySettings`의 기존 동작이라 006 테스트가 없고, 등록표를 좁힌 뒤 화면에서 축이 비고 복원 전에는 '분석하기'가 막히는 것을 수동으로 확인 |
| S7 탐색 묶음·경제환경 | 자동 + 수동 통과 | `MyRecordsPage.test` "탐색 묶음을 펼치면 부분 완료 N/M …"(S7-2·S7-3 표시), "같은 경제환경 검색이라도 진입 경로가 다르면 두 항목이고 … 고정 문장"(S7-5·S7-6); `RegimeRecordIT` 진입 경로별 두 쌍, 비로그인 401·행 없음(S7-4), `baseline_origin` 저장; `RegimeSearchPage.test` "?searchId= 로 열면 저장된 판을 고정 조회"(S7-5-1의 API 경로). 부분 완료 묶음(S7-3)과 새 revision 뒤 저장 당시 순위(S7-5-1)는 수동으로 확인 |
| S8 내보내기 | 자동 + 수동 통과 | `MyRecordExportIT` 8건 — 구획 전부·`exclusions` ≥2·금지 키 없음(S8-2~4·S8-8), `fills_recorded=false`면 키 없음(S8-7), CSV BOM·CRLF·`# 구획`(S8-5는 한국어 Windows 표 도구로 열어 한글이 깨지지 않음을 수동으로 확인), 탐색 묶음 `searchSpace`·`candidates`(S8-6), 실패 항목은 설정·버전·사유만; `CsvWriterTest` 탈출 3경우·BOM |
| S9 이름 변경·삭제 | 자동 | `MyRecordCommandIT` 이름만_바뀐다(`backtest_result` 해시 불변, S9-1), 삭제는_한_번뿐이다(S9-2·3), 남의_기록은_함께_사라지지_않는다(S9-4·5, SC-007); `MyRecordsPage.test` "삭제는 확인을 거치고 … 복구를 제안하지 않는다"(S9-6). 삭제는 `my_record.deleted_at`만 쓰고 다른 표에 DELETE가 없다(`MyRecordCommandService`) |
| S10 저장 상한 | 자동 | `MyRecordLimitIT` 상한 2에서 세 번째 접수 → 409 `RECORD_LIMIT_REACHED` `{current: 2, limit: 2}`, `param_search` 행·메시지 없음, 기존 행 유지, 삭제 뒤 통과; `ParamSearchPage.test` "저장 상한(409) … N/M 으로 안내" |
| S11 자동 삭제 없음 | 자동 | 위 grep 결과 없음. `saved_at`은 NOT NULL 컬럼, `last_viewed_at`은 `GET /my-records/{id}`가 올린다 — `MyRecordListQueryIT` 참조_버전과_열람_표시, `MyRecordCommandIT` 이름_변경은_열람이_아니다 |
| S12 비로그인 경계 | 자동 + 수동 통과 | `MyRecordsPage.test` "비로그인은 ProtectedRoute 가 로그인으로 보내고 기록을 부르지 않는다"; `MyRecordApiIT` 비로그인 401. S12-1~4(로그인 안내·복귀·진단 입력 유지·세션 만료 뒤 보호 정보 숨김과 재로그인 복귀)는 수동으로 확인. 인증 동작 자체는 014의 [verification/integration.md](../../014-user-auth/verification/integration.md) 소유 |
| S13 기본 보기·필터 | 자동 + 수동 통과 | `MyRecordListQueryIT` 기본_보기는_완료_항목만, 실패_취소는_필터로만_보인다(사유 동반); `MyRecordsPage.test` "기본 보기는 상태를 보내지 않고 … 정렬 선택지에 성과가 없다"(S13-4), "상태 필터를 켜면 그 상태로 다시 부른다"(S13-3). S13-5(응답 본문에 성과 수치 없음)는 `MyRecordViewQuery.VIEW`의 select에 `backtest_result`·`search_candidate` 조인이 없고, 네트워크 탭에서도 수동으로 확인 |
| S14 사용성 관찰 | 해당 없음 | 구현 검증이 아니라 별도 항목(SC-006). 여기서 통과로 쓰지 않는다 |

## 확인하지 않은 것

quickstart "확인하지 않는 것" 표의 다섯 항목(보관 기간 만료, 경제환경 연결 값, 참조 삭제 표시, 응답 시간 목표, 상한 확정값)은
그대로 두었다. 여기에 이번 실행이 더한 것:

- **`test:legacy`의 Node 버전 의존** — Node 22에서는 `--experimental-strip-types` 없이 실패한다. 006 범위 밖이라 고치지 않았고
  담당 파트에 말로 전달한다.
- **S14 사용성 관찰**은 하지 않았다. 구현 검증과 별개 항목이며 이 기록을 SC-006 통과로 쓰지 않는다.
