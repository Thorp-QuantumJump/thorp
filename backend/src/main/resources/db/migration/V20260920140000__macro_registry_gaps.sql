-- 008 등록표·수집 기록의 빈 칸 셋을 채운다 (T075·T077·T079).
--
-- 앞선 두 변경(V20260918120000, V20260918153100)은 손대지 않고 덧붙인다 — 운영에 이미
-- 적용되었을 수 있고, 적용 기록과 내용이 어긋나면 그 다음 배포가 모두 막힌다
-- (constitution "스키마 변경 규칙").
--
-- 셋 다 **추가만** 한다. 지우는 것도, 기존 행이 위반하게 되는 제약도 없다.

-- ---------------------------------------------------------------------------
-- 1. 걸러낸 행 수 (T075, FR-017a)
-- ---------------------------------------------------------------------------
-- 관세청 응답에는 <year>총계</year> 합계 행이 섞여 온다. 월별 관측값으로 세면 그 해가 한 번
-- 더, 그것도 열두 배 큰 값으로 들어가므로 걸러낸다. **걸러낸 수를 남기지 않으면 "9개월치를
-- 받았다"와 "10행을 받아 1행을 뺐다"가 같은 모양이 된다** — 원천이 합계 행의 모양을 바꿔
-- 걸러지지 않게 됐을 때 그 사실이 드러나지 않는다.
--
-- 기존 행은 0 으로 채운다. 그 수집들이 아무것도 걸러내지 않았다는 뜻이 아니라 세지 않았다는
-- 뜻이지만, 세지 않은 것과 0 을 구분하는 값을 새로 만들면 읽는 쪽이 매번 셋을 갈라야 한다.
-- 이 칸은 추세를 보는 자리이지 과거를 소급하는 자리가 아니다.
ALTER TABLE macro_collection_item_status
    ADD COLUMN excluded_row_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE macro_collection_item_status
    ADD CONSTRAINT ck_macro_item_excluded_rows CHECK (excluded_row_count >= 0);

-- ---------------------------------------------------------------------------
-- 2. 공표 지연 (T077, FR-011b(3))
-- ---------------------------------------------------------------------------
-- 전 구간 적재가 끝났는지 판정하려면 "몇 달치가 와야 하는가"를 알아야 한다. 그 수는 상수가
-- 아니라 달마다 늘어나므로 **개수 대신 지연을 적고 개수는 계산한다** — 오늘이 2026-09 인데
-- 최신 대상월이 2026-07 이면 2다. 개수를 박아 두면 다음 달에 곧바로 틀리고, 기대가 실제보다
-- 작으면 무엇이 빠졌든 판정은 늘 통과한다.
--
-- NULL 을 허용하는 이유는 이 컬럼이 생기기 전에 발행된 등록표 버전이 있을 수 있어서다. 발행된
-- 등록표는 불변이므로 뒤에서 값을 채우지 않는다(FR-003).
ALTER TABLE macro_indicator_definition
    ADD COLUMN publication_lag_months INTEGER;

ALTER TABLE macro_indicator_definition
    ADD CONSTRAINT ck_macro_indicator_publication_lag
    CHECK (publication_lag_months IS NULL OR publication_lag_months >= 0);

-- ---------------------------------------------------------------------------
-- 3. 원천별 호출 규칙 (T079, FR-011b(1)·FR-017a)
-- ---------------------------------------------------------------------------
-- 재시도 횟수·간격과 원천이 공표한 호출 한도를 등록표 버전과 함께 남긴다. 계산 등록표 쪽에
-- 두는 이유는 이 값이 지표가 아니라 원천의 성질이고, 바뀌면 그때부터 수집이 다르게 돌기
-- 때문이다 — 값이 왜 달라졌는지 되짚을 때 어떤 규칙으로 받았는지가 함께 있어야 한다.
ALTER TABLE macro_calculation_registry_version
    ADD COLUMN source_policy JSONB;

-- ---------------------------------------------------------------------------
-- 4. 재조회 구간 N 의 근거 (T073, FR-017)
-- ---------------------------------------------------------------------------
-- N 이 왜 그 값인지, 무엇을 봐야 확정되는지, 틀릴 때 어느 쪽으로 틀리는 편이 나은지를 등록표
-- 버전과 함께 남긴다. `change_reason` 에 섞지 않는 이유는 그 칸이 "이 버전을 왜 만들었나"이지
-- "N 을 왜 그렇게 정했나"가 아니어서다 — 섞으면 다음 버전에서 근거가 사라진다.
--
-- 지금 들어갈 값은 **잠정이며 미확인으로 닫은 것**이다. 근거 없는 칸을 비워 두는 대신, 비어
-- 있는 이유까지 적어 둔다(constitution 미확인 항목 처리).
ALTER TABLE macro_calculation_registry_version
    ADD COLUMN refresh_months_basis TEXT;

-- ---------------------------------------------------------------------------
-- 5. 발행 보류를 수집 기록에 남긴다 (T083, FR-011·FR-011b(3))
-- ---------------------------------------------------------------------------
-- 17개를 다 받았는데 전 구간이 기대 대상월 수에 못 미쳐 발행을 막은 실행은, 지금까지 시도 행이
-- `SUCCEEDED` 로 남았다. 지표는 정말 다 성공했으니 거짓은 아니지만, **아무것도 발행되지 않았는데
-- DB 만 보면 완전히 성공한 실행과 구분되지 않는다.** 보류 사유는 로그에만 있고 로그는 지나간다.
--
-- `PARTIAL` 로 대신할 수 없다. 그것은 "일부 지표만 성공"이라 지표가 다 성공한 이 경우를 말하지
-- 못하고, 어차피 어느 지표가 실패했는지는 item_status 가 이미 들고 있다. 여기 status 가 답해야
-- 하는 물음은 "이 실행의 결과가 밖으로 나갔는가"다.
--
-- 사유를 status 와 함께 두는 이유는, 보류를 본 사람이 곧바로 다음에 무엇을 볼지 알아야 하기
-- 때문이다 — 지표가 덜 찬 것과 전 구간이 짧은 것은 고칠 자리가 다르다.
ALTER TABLE macro_collection_attempt DROP CONSTRAINT ck_macro_collection_attempt_status;
ALTER TABLE macro_collection_attempt
    ADD CONSTRAINT ck_macro_collection_attempt_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'HELD'));

ALTER TABLE macro_collection_attempt ADD COLUMN hold_reason TEXT;

-- 사유 없는 보류는 로그로 돌아가라는 말과 같다.
ALTER TABLE macro_collection_attempt
    ADD CONSTRAINT ck_macro_collection_attempt_hold_reason
    CHECK (status <> 'HELD' OR hold_reason IS NOT NULL);
