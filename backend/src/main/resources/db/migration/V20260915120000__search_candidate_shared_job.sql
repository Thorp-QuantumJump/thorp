-- "후보 하나 = 실행 하나"라는 전제를 거둔다(S15P21E205-221).
--
-- V3는 search_candidate.backtest_job_id에 UNIQUE를 걸어 그 전제를 스키마에 못박았다. 그런데 004는
-- 같은 조건의 요청을 살아 있는 실행에 합류시키거나 완료 결과를 재사용한다(spec 004 R-02,
-- BacktestService.joinExisting). 같은 조건을 다시 접수하면 두 번째 묶음의 후보가 첫 번째와 같은 실행
-- 행을 받고, 그 순간 UNIQUE에 걸려 접수 전체가 롤백됐다 — 계약 §7의 "같은 조건으로 다시 요청하면 항상
-- 새 묶음이다"(FR-013c)가 지켜지지 않았다.
--
-- 둘 중 하나는 틀렸고 틀린 쪽은 이 제약이다. 재사용을 막으면 같은 조건의 계산을 두 번 돌리게 되는데,
-- 그것은 007이 아끼려는 바로 그 비용이다. 유니크를 조회용 인덱스로 낮춘다.
--
-- 대신 실행 하나에 후보가 여럿 걸릴 수 있다 — 서로 다른 묶음의 것일 수도 있다. 결과 이벤트가 어느
-- 후보의 것인지 찾는 경로는 그래서 다건 조회다(ParamSearchProgressService).
DROP INDEX uk_search_candidate_job;

-- 발행 전·제외·미실행 후보는 backtest_job_id가 NULL이고, 후보 대부분이 한때 그 상태다. 부분 인덱스로
-- 두면 그 행들을 담지 않아 인덱스가 작고 갱신도 싸다.
CREATE INDEX idx_search_candidate_job ON search_candidate (backtest_job_id)
    WHERE backtest_job_id IS NOT NULL;
