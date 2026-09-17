# Ingestion Pipeline Checklist: 경제지표 등록·수집

**Purpose**: 원천에서 자료를 받아 적재하기까지의 요구사항이 빠짐없고 한 가지로 읽히는지 검토한다. 원천별 제약, 재조회 범위, 실패와 재시도, 멱등성이 대상이다.
**Created**: 2026-09-17
**Feature**: [spec.md](../spec.md) · [plan.md](../plan.md) · [research.md](../research.md) · [pd11-source-verification.md](../pd11-source-verification.md)

**Note**: 이 점검표는 수집이 실제로 동작하는지 시험하지 않는다. 수집 규칙을 적은 문장의 품질을 검토한다.
**Review Ownership**: 검토자가 요구사항 품질을 판단해 항목을 확인한다. 새 항목은 모두 미체크 상태다.
**Marker Semantics**: `[x]`는 요구사항 품질이 검토·충족되었다는 뜻이며 구현이나 실제 수집 완료를 뜻하지 않는다.

## Requirement Completeness

- [x] CHK001 원천마다 다른 조회 제약(요청당 기간·행·셀 상한)을 등록표에 적고 그에 맞춰 요청을 나누라는 요구가 있는가? [Completeness, Spec §FR-017, §FR-017a]
- [x] CHK002 최초 전 구간 적재와 이후 정기 재조회를 서로 다른 실행으로 구분하고, 각각의 범위를 정하는 요구가 있는가? [Completeness, Spec §FR-017]
- [x] CHK003 응답에 섞여 오는 합계·소계 행처럼 월별 관측값이 아닌 행을 걸러내라는 요구가 있는가? [Gap, Verification §4]
- [x] CHK004 기준월이 바뀌며 표가 나뉘는 계열을 여러 표에서 받아 잇는 절차가 요구사항으로 적혀 있는가? [Completeness, Spec §FR-010, Verification §2]
- [x] CHK005 원천이 발표일을 주지 않는 경우에 무엇을 대신 기록할지와 무엇으로 대체하면 안 되는지가 정해져 있는가? [Completeness, Spec §FR-007]

## Requirement Clarity

- [x] CHK006 정기 재조회 구간 `N`을 지표별로 정하는 근거와 정하기 전까지의 기본값이 분명한가? [Clarity, Spec §FR-002, §FR-017]
- [x] CHK007 `N` 밖의 과거 개정을 확인하는 경로(원천 공지·메타데이터·갱신일)와 확인했을 때의 재조회 범위가 분명한가? [Clarity, Spec §FR-017]
- [x] CHK008 전 구간 재적재를 촉발하는 조건(기준연도 개편, 값 규칙 변경)과 부분 재조회로 충분한 조건이 구별되는가? [Clarity, Spec §FR-003, §FR-017]
- [x] CHK009 원천이 주는 갱신일이 통계표 단위인지 관측치 단위인지 구별해 쓰라는 요구가 분명한가? [Ambiguity, Spec §FR-007, Verification §2]
- [x] CHK010 이용 권리가 '확인 완료'·'권리 확인 대기'·'미확인'인 지표의 수집과 발행 절차가 서로 어떻게 다른지 분명한가? [Clarity, Spec §FR-016a, §FR-016b]

## Requirement Consistency

- [x] CHK011 사용자 요청 중 원천을 호출하지 않는다는 요구가 수집·조회 양쪽 서술에서 같은 뜻으로 쓰였는가? [Consistency, Spec §FR-017, §FR-023]
- [x] CHK012 호출 한도 도달 시의 중단 요구와 전 구간 적재에 필요한 다회 호출 요구가 서로 모순되지 않는가? [Consistency, Spec §FR-011, §FR-017a]
- [x] CHK013 한도를 확인하지 못한 원천을 정기 수집에 올리지 않는다는 요구가 17개 전부를 수집해야 완결 버전이 난다는 요구와 양립하는가? [Conflict, Spec §FR-011, §FR-017a]

## Acceptance Criteria Quality

- [x] CHK014 같은 기간을 다시 수집했을 때 무엇이 같아야 하고 무엇이 새로 생겨도 되는지(개정 행) 측정 가능하게 적혔는가? [Measurability, Spec §FR-009, §SC-004]
- [x] CHK015 전 구간 적재가 끝났음을 판정할 기준(지표별 기대 시작월과 대상월 수)이 정해져 있는가? [Measurability, Gap, Verification §1~4]
- [x] CHK016 `N`이 적절했는지 사후에 판정할 근거(놓친 개정 건수 등)를 남기라는 요구가 있는가? [Measurability, Gap, Spec §FR-017]

## Scenario & Edge Case Coverage

- [x] CHK017 원천이 일시적 오류나 응답 없음을 돌려줄 때의 재시도와 포기 기준이 요구사항에 있는가? [Coverage, Gap, Spec §FR-011]
- [x] CHK018 같은 수집 실행을 중간에 끊고 다시 돌렸을 때 중복 없이 이어지도록 하라는 요구가 있는가? [Recovery, Gap, Spec §FR-011]
- [x] CHK019 원천이 경로나 식별자를 바꿔 기존 호출이 실패하는 경우를 어떻게 알아채고 다루는지 정해져 있는가? [Edge Case, Gap, Verification §4]
- [x] CHK020 개발 환경의 예시 자료 수집과 운영 수집이 같은 경로를 쓰지 않도록 분리 조건이 정의되어 있는가? [Coverage, Spec §FR-022]

## 검토 메모 (2026-09-17)

20문항을 spec·plan과 대조했다. 8개는 요구사항 자체가 없어 걸렸고, 모두 spec에 채운 뒤 확인했다.

| 항목 | 무엇이 없었나 | 어디에 채웠나 |
|---|---|---|
| CHK001·CHK003 | 요청당 조회 기간 상한에 맞춘 분할, 응답에 섞인 합계 행 제외 | FR-017a |
| CHK004 | 개편으로 표가 여러 개로 나뉜 계열을 겹침 구간 비율로 잇는 절차 | FR-010 |
| CHK005·CHK009 | 발표일 미제공 시 처리, 통계표 단위 갱신일을 관측치 발표일로 쓰지 않기 | FR-007 |
| CHK006·CHK016 | 재조회 구간 N의 기본값과 사후 적절성 판정 근거 | FR-017 |
| CHK013 | 한도 미확인 원천 제외와 17개 완결성의 충돌 해소 | FR-017a |
| CHK015·CHK017·CHK018·CHK019 | 전 구간 적재 완료 판정, 재시도와 포기, 중단 후 재실행, 원천 변경 감지 | **FR-011b 신설** |

CHK010은 한시적 대체 원천 예외가 철회되어 문항을 현행 권리 판정 상태(확인 완료·권리 확인 대기·미확인) 기준으로 고쳐 읽었다.

FR-011b의 원천 변경 감지는 2026-09-17에 관세청 기준 주소가 바뀐 것을 인증키 문제로 오진한 경험에서 나왔다. 원천이 경로를 바꾸면 인증 오류처럼 보이는 응답이 오므로, 실패 원인을 인증 문제로 단정하지 않도록 요구에 적었다.


## Notes

- 항목을 `[x]`로 바꾸는 것은 검토자의 요구사항 품질 승인이다. 구현·수집 완료 표시가 아니다.
- 충족하지 않은 항목은 고칠 문서와 판단 근거를 옆에 적고 미체크로 둔다.
- `$speckit-implement`는 체크 상태를 읽지만 이 표식을 수정하지 않는다.
- `checklists/requirements.md`는 `$speckit-specify`·`$speckit-clarify`가 관리하는 별도 기본 점검표다.
- 변환 규칙은 [transformation.md](transformation.md), 이용 조건은 [source-compliance.md](source-compliance.md), 자료·소비 계약은 [macro-data.md](macro-data.md)가 다룬다.
