# Implementation Plan: 내 기록

**Branch**: `006-my-records` | **Date**: 2026-09-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-my-records/spec.md`

## Summary

완료한 분석·자동 탐색 묶음·경제환경 탐색을 한 목록에서 찾아 재계산 없이 다시 열고, 조건을 바꿔 새
분석으로 잇고, CSV/JSON으로 내보내고, 이름을 바꾸거나 지우는 기능이다.

핵심 접근은 **새로 만들지 않는 것**이다. 세 종류의 계산 결과와 재현 기록은 이미 `backtest_request`·
`param_search`·`regime_search_snapshot`에 있고, 결과 화면(`/results/{publicToken}`)과 설정 화면
인계 계약(`thorp.diagnosis.sectorSelection`·`thorp.strategySettings`)과 사용자 식별
(`CurrentRequester`)도 이미 동작한다. 006이 더하는 것은 **소유·이름·목록 자리를 담는 개인 항목
테이블 하나**(`my_record`)와 그것을 읽는 목록·내보내기 API, 그리고 `/records` 화면이다. 경제환경 개인
기록은 010이 미리 만들어 둔 `regime_search_log`에 006이 행을 쓴다.

2026-09-18 명료화가 정한 세 가지가 이 구조를 결정했다. ① 삭제 단위는 개인 항목이고 공용 계산 결과는
남는다 → 개인 항목과 계산 결과가 애초에 다른 테이블이어야 한다. ② 시간 기반 자동 삭제를 만들지
않는다 → 만료 배치·예고 UI가 없다. ③ 인증은 014의 세션 인증을 그대로 쓴다 → 인증 코드를 쓰지 않는다.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5 + React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5, Spring Security(서버 세션, 014), Spring Data JPA, Flyway,
Jackson / React 19 + Vite, react-router-dom. **새 라이브러리를 추가하지 않는다** — CSV는 쉼표·따옴표
탈출 한 함수로 끝나고, JSON은 Jackson이 이미 있다.

**Storage**: PostgreSQL. Flyway 타임스탬프 버전 `V{YYYYMMDDHHMMSS}__desc.sql` 하나를 더한다.

**Testing**: backend `./gradlew spotlessApply build` (JUnit 5 + ArchUnit), frontend
`npm run lint && npm run build && npm test`. 새 FE 테스트 파일은 `vite.config.ts`의 `test.include`에
걸리는지 확인한다(frontend/CLAUDE.md).

**Target Platform**: 한국어 웹, 데스크톱 브라우저 우선

**Project Type**: web (backend + frontend 모노레포)

**Performance Goals**: 목록·내보내기의 응답 목표 수치를 이 plan에서 정하지 않는다. 원칙 IX가 실측 후
고정하도록 정했고 `specs/README.md` PD-06이 그 항목을 이미 소유한다. 대신 목록 질의가 정렬·필터를
인덱스로 처리하도록 스키마를 잡는다(data-model §2).

**Constraints**: Backend는 계산하지 않는다(루트 CLAUDE.md). 내보내기도 저장된 값을 조립할 뿐 지표를
다시 계산하지 않는다. 내보내기 파일에 재배포 금지 원천의 일별 시세 원값과 기사 본문을 담을 **자리를
만들지 않는다**(원칙 X).

**Scale/Scope**: 사용자당 기록 수백 건 규모. 탐색 묶음 저장 상한은 설정값이고 초기 임시값 20개다
(spec FR-018). 화면 1개(`/records`)와 목록·상세 메뉴.

## Constitution Check

*GATE: Phase 0 전에 통과해야 하고 Phase 1 설계 뒤 다시 판정한다.*

| 규칙 | 판정 | 근거 |
|---|---|---|
| 원칙 II — 화면마다 가장 중요한 행동 버튼 하나 | 통과 | 목록의 주 버튼은 **'다시 보기' 하나**다. '설정 바꿔 비교'는 같은 크기의 버튼으로 두지 않는다. 3.4.0이 허용한 버튼 둘 예외는 spec 003 화면에만 적용되며 "화면 설계자가 임의로 늘릴 수 없다"고 못 박았다. spec FR-002의 "기본 동작은 '다시 보기 / 설정 바꿔 비교'"는 두 동작이 항목에 있다는 뜻으로 읽고 같은 크기 버튼 둘로 해석하지 않는다([research.md](research.md) R7) |
| 원칙 II — 정보 세 층 | 통과 | 목록에 보이는 것은 이름·섹터·전략·분석 기간·저장일·상태뿐이다. 참조 버전·재현 기록·거래 내역은 펼쳐 보는 층, 감사 정보는 시스템 층이다(FR-019) |
| 원칙 III — 진단이지 추천이 아니다 | 통과 | 목록의 정렬 기준에 **수익률·성과를 넣지 않는다**(저장일·이름만). '최고 수익' 배지·자동 우승 표시를 만들지 않는다. 자신의 실험 간 비교는 005로 넘긴다 |
| 원칙 IV — 비교는 같은 조건에서만 | 해당 없음 | 006은 비교를 수행하지 않는다. 비교 대상 선택과 판정은 005 소유다 |
| 원칙 VI — 없는 결과를 만들어내지 않는다 | 통과 | 대응표·등록표 불일치는 알리고 값을 조용히 바꾸지 않는다(FR-008). 삭제된 참조는 표시로 남긴다(FR-017). 목록 필터가 값을 못 읽는 항목을 조용히 빼지 않는다 |
| 원칙 VIII — 재현할 수 있게 기록한다 | 통과 | 006은 재현 항목을 **새로 만들지 않고** 004·007·010이 남긴 것을 가리킨다. 개인 항목이 복제하는 값은 저장 뒤 바뀌지 않는 조건뿐이고, 변하는 상태(진행·완료·실패)는 원본에서 조인해 읽는다([research.md](research.md) R2) |
| 원칙 IX — 실행 한도는 실측으로 | 통과 | 응답 목표 수치를 정하지 않았다. 탐색 묶음 상한도 값이 아니라 장치를 만든다(설정값, 초기 임시값 20) |
| 원칙 X — 자료는 이용 조건 안에서만 | 통과 | 내보내기 스키마에 원천 일별 시세 원값·기사 본문 필드를 두지 않는다. 012의 `NewsArticleExportResponse`가 쓴 방식과 같다 — 자리가 없어야 실수로 채우지 않는다. 제외한 항목과 이유는 파일 안 `exclusions`에 담는다(FR-016) |
| 기록·개인정보·보관 — 식별정보 분리 | 통과 | `my_record`는 `users(id)` FK만 갖고 이메일·닉네임·전화번호를 복제하지 않는다 |
| 기록·개인정보·보관 — 무기한 보관 전제 금지 | 통과 | 보관 기간은 PD-13 미확정이고 자동 삭제를 만들지 않는다(2026-09-18 명료화). 나중 도입을 막지 않도록 `saved_at`·`last_viewed_at`을 항목마다 둔다 |
| 기록·개인정보·보관 — 기본 동작·추가 메뉴 | 통과 | 상세 설정·거래 내역·내보내기·이름 변경·삭제는 추가 메뉴에 둔다(FR-002) |
| 스키마 변경 규칙 | 통과 예정 | Flyway 변경 1건 + JPA 매핑 + 검증을 같은 MR에 담는다. 기존 컬럼을 지우거나 타입을 바꾸지 않는다. `param_search.owner_user_id`는 **이미 존재하는 NULL 컬럼을 채우는 것**이라 되돌릴 수 없는 변경이 아니다 |
| AI 작업 방식 — 결정 항목 임의 확정 금지 | 통과 | PD-13의 보관 기간·상한 확정값을 이 plan에서 정하지 않는다. 상한 초기값 20은 spec이 "임시값"으로 표시한 값이다 |
| 완료의 정의 | 통과 | 구현 완료를 효과 입증으로 쓰지 않는다. SC-006 사용성 관찰은 구현과 별개 항목이다 |

**미해결 위반: 없음.**

한 가지 미완 의존을 위반이 아니라 **범위 축소**로 처리한다. [011 경제환경-전략 연결](../011-regime-strategy-link/spec.md)은
plan·data-model·tasks까지 나왔지만 **표가 아직 만들어지지 않았다.** 그래서 FR-012가 요구하는 "연결한
섹터·ETF·전략·사후 평가 구간"의 값이 지금은 없다. 011 data-model이 `regime_link_search`를 "006이 읽는
경제환경 탐색 기록의 011 몫"으로 정했으므로 006은 그 자리를 만들지 않고 **표가 생기면 조인해 읽는다.**
지금 존재하는 값(진입 경로·스냅샷 참조)은 010이 만들어 둔 `regime_search_log`에 006이 쓴다. 없는 값을
만들어 채우지 않는다(원칙 VI). 자세한 경계는 [research.md](research.md) R9·R15에 있다.

## Project Structure

### Documentation (this feature)

```text
specs/006-my-records/
├── plan.md              # 이 파일
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── my-records-api.md    # 서버 계약
│   └── record-handoff.md    # 화면 사이 계약('설정 바꿔 비교')
└── tasks.md             # /speckit-tasks 산출물 (이 명령이 만들지 않는다)
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/myrecord/
├── presentation/
│   ├── controller/MyRecordController.java          # GET /my-records, PATCH·DELETE /{recordId}, GET /{recordId}/export
│   ├── request/                                    # RenameMyRecordRequest
│   └── response/                                   # MyRecordListResponse, MyRecordExportResponse
├── application/
│   ├── service/MyRecordQueryService.java           # 목록·상세 조회, 정렬·필터
│   ├── service/MyRecordCommandService.java         # 이름 변경·삭제
│   ├── service/MyRecordRegistrar.java              # 004·007 접수 이벤트를 듣고 항목 생성·기존 항목 연결(FR-009), 경제환경 기록 POST
│   ├── service/MyRecordLimitPolicy.java            # 탐색 묶음 상한 판정(007 접수가 부른다)
│   ├── service/MyRecordExportService.java          # CSV/JSON 조립, exclusions
│   ├── service/MyRecordMappingDriftChecker.java    # UI-01 판정
│   ├── command/ result/ repository/
├── domain/model/                                   # MyRecord, RecordKind, RecordName
└── infrastructure/persistence/                     # MyRecordJpaEntity, Mapper, RepositoryImpl

backend/src/main/resources/db/migration/
└── V2026{...}__my_record.sql                       # my_record 한 표

frontend/src/
├── pages/MyRecordsPage.tsx                         # MyRecordsEntryPage.tsx를 대체한다
├── components/records/                             # RecordList, RecordRow, RecordFilters, RecordDetailPanel, ExportMenu, DeleteConfirm
├── api/myRecords.ts
└── hooks/useMyRecords.ts
```

**Structure Decision**: 새 backend 도메인 `myrecord/`를 ADR-0001의 도메인별 4계층으로 만든다.
`record`는 Java의 제한 식별자와 헷갈리므로 쓰지 않는다. `CurrentRequester`는
`backtest/presentation/support`에 있는 것을 **그대로 가져다 쓴다** — 이미 `paramsearch`·`result`
컨트롤러가 그렇게 쓰고 있어 006이 네 번째다. 공통 위치로 옮기는 정리는 기존 세 파일을 건드려야 해서
이 범위에 넣지 않는다.

프런트엔드는 `/records/*` 경로와 `ProtectedRoute`가 이미 App.tsx에 있다. 006은 그 안의
`MyRecordsEntryPage`를 목록 화면으로 갈아낀다.

## Complexity Tracking

> Constitution Check에 정당화가 필요한 위반이 없으므로 비어 있다.

다만 되짚어야 할 설계 비용 하나를 적어 둔다.

| 선택 | 비용 | 그래도 택한 이유 |
|---|---|---|
| 개인 항목 테이블 `my_record` 신설 | 004·007 접수 서비스에 이벤트 발행 한 줄씩, 006에 경제환경 기록 POST 하나가 생긴다 | 세 원본을 `UNION ALL`로 묶어 정렬·필터·쪽 나누기를 하는 대안은 컬럼 구성이 서로 달라 질의가 더 길고, 무엇보다 "개인 항목만 삭제"를 원본 테이블에 `deleted_at`을 세 번 붙여 표현해야 한다. `regime_search_snapshot`은 공용 스냅샷이라 소유자 자리가 아예 없어 새 테이블이 어차피 필요하다([research.md](research.md) R1) |

## Phase 0·1 산출물

| 파일 | 내용 |
|---|---|
| [research.md](research.md) | 결정 R1~R16. Technical Context에 NEEDS CLARIFICATION이 남지 않았다 |
| [data-model.md](data-model.md) | `my_record` 한 표, 제약·인덱스, 만들지 않는 것(`regime_search_log` 재사용) |
| [contracts/my-records-api.md](contracts/my-records-api.md) | 목록·상세·이름 변경·삭제·내보내기, 007 접수 경로에 요청하는 변경 |
| [contracts/record-handoff.md](contracts/record-handoff.md) | '설정 바꿔 비교'의 화면 사이 인계(기존 두 계약에 쓰는 쪽) |
| [quickstart.md](quickstart.md) | 검증 시나리오 S1~S14와 확인하지 않는 것 |

## Constitution Check — Phase 1 설계 뒤 재판정

설계로 새로 생긴 판정거리만 적는다. 위 표의 판정은 그대로 유지된다.

| 규칙 | 판정 | 설계가 무엇을 바꿨나 |
|---|---|---|
| 원칙 VIII — 재현 기록은 한 곳 | 통과 | `my_record`가 섹터·전략·분석 기간을 복제하는 것이 두 번째 재현 기록이 아닌지 다시 봤다. 복제하는 값은 **접수 시점에 정해져 이후 바뀌지 않는 조건**뿐이고 목록의 정렬·필터에만 쓴다. 변하는 상태는 복제하지 않고 원본을 조인한다(R2). 결과·거래·참조 버전은 복제하지 않는다 |
| 원칙 VI — 조용한 대체 금지 | 통과 | 목록 질의의 허용하지 않는 `sort`·`kind`·`status` 값을 기본값으로 바꾸지 않고 `400`으로 돌려주기로 했다(contracts). '설정 바꿔 비교'가 채운 값도 003의 기존 판정을 타 범위 밖이면 비워진다(record-handoff) |
| 원칙 VI — 없는 값을 만들지 않는다 | 통과 | 011 표가 아직 없어 비어 있는 경제환경 연결 항목을 기본값으로 채우지 않기로 했다(R9). quickstart S7-6이 그 비어 있음을 **기대 상태로** 확인한다. 개별 체결도 `fills_recorded=false`면 키를 만들지 않는다(contracts) |
| 원칙 III — 순위 화면이 되지 않는다 | 통과 | 정렬에서 성과를 뺀 것만으로는 부족하다고 보아 **목록 응답 본문에서 수익률·하락 폭·비용을 아예 뺐다**(contracts). 값이 있으면 정렬 없이도 나란히 보이는 순위 화면이 된다. quickstart S13-5가 이것을 확인한다 |
| 원칙 II — 버튼 하나 | 통과 | quickstart S1-4를 판정 단계로 넣어 같은 크기 버튼 둘이 들어오는 것을 검증에서 막았다. 006에서 버튼 둘을 같은 크기로 두려면 plan이 아니라 constitution 개정이 필요하다(R7) |
| 원칙 X — 이용 조건 | 통과 | 금지 항목을 값이 아니라 **스키마 자리로** 막았고(R10), `exclusions`를 빈 배열이어도 반드시 담게 해 "무엇을 뺐는지"를 판정 가능하게 했다. quickstart S8-4가 키 존재까지 확인한다 |
| 기록·개인정보·보관 | 통과 | 자동 삭제가 없다는 것을 quickstart S11이 `grep`으로 확인한다. 규칙을 문장으로만 두지 않았다 |
| 스키마 변경 규칙 | 통과 | 표 하나 생성뿐이다. `regime_search_log`는 행을 더할 뿐 스키마를 바꾸지 않는다. 컬럼·표 삭제, 타입 변경, 기존 행이 위반하게 되는 제약 추가가 없으므로 되돌릴 수 없는 변경이 아니다 |
| 완료의 정의 | 통과 | quickstart "확인하지 않는 것"에 발생 조건이 없는 규칙(참조 삭제 표시)을 적고 "구현했다는 사실을 검증됐다는 뜻으로 쓰지 않는다"를 명시했다. SC-006 사용성 관찰도 별도 항목으로 뺐다 |

**미해결 위반: 없음.** Complexity Tracking에 정당화가 필요한 항목이 없다.

**접점 조사(/speckit-tasks)에서 고친 것**: `regime_search_log`가 이미 있어 `my_record_regime_link`를 만들지
않기로 했고(R9·R15), `param_search.owner_user_id`는 접수가 이미 채우고 있어 007에 요청할 변경에서 뺐다.
항목 생성은 순환 참조를 피해 접수 이벤트로 받는다(R16).

**dev 대조(2026-09-20)에서 고친 것**: 011이 plan·data-model까지 확정해 연결 항목의 출처가
`regime_link_search`로 정해졌고(R9), 004가 `backtest_fill`과 `backtest_result.fills_recorded`를 더해
내보내기에 개별 체결 구획이 생겼으며(contracts), 참조 버전은 `backtest_run.versions`와 `request_spec`의
실제 키에서 읽도록 대응을 적었다. `versions.worker`·`versions.dependencies`는 005가 운영 정보로 보고
공개 응답에서 빼기로 한 판단을 006도 따른다. 저장 당시 워밍업·평가 기간은 보존만 하고 재산정은
003·005가 한다(FR-007a).

새로 생기는 공통 자원 두 개를 적어 둔다. 둘 다 기존 규칙 안이다.

- `ErrorCode.RECORD_LIMIT_REACHED` (409) — 실행 자원 한도(429)와 저장 자리 한도를 다른 코드로 나눈다.
- `SecurityConfig`의 `/my-records/**` → `authenticated()` — 014가 만든 경계에 경로 하나를 더한다.
