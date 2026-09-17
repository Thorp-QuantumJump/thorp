"""PostgreSQL 적재 (spec FR-029·FR-030).

`regime_search_snapshot`·`regime_baseline_month`·`regime_search_case`를 한 트랜잭션에 쓴다.
**결과가 달라진 것만 새 revision으로 저장하고 이전 행을 덮어쓰지 않는다** — 내용이 같으면
행이 늘지 않아야 배치를 두 번 돌려도 멱등하다(SC-002).

보관 대상은 각 (모드·기준월)의 가장 최신 항목과 006 기록이 가리키는 항목 둘뿐이고, 정리는
적재를 마친 **같은 실행에서** 한다 — 사람이 따로 돌리지 않는다(FR-029).

구현은 묶음 M5에서 채운다.
"""
