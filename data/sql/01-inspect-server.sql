-- 서버 DB 상태 확인 (읽기만 한다. 아무것도 바꾸지 않는다)
--
-- 왜 먼저 보는가
--   발행된 자료 버전은 UPDATE·DELETE 가 트리거로 막혀 있다(FR-017). 한 번 잘못 넣으면
--   되돌릴 수 없으므로, 무엇을 넣을지 정하기 전에 무엇이 있는지 본다.
--
--   특히 dataset_version.map_version_id 가 대응표를 가리킨다. 서버의 대응표 번호가 로컬과
--   다른데 로컬 번호를 그대로 넣으면 **엉뚱한 대응표를 가리키는 자료**가 발행되고 지울 수 없다.
--
-- 쓰는 법
--   docker exec -i thorp-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < 01-inspect-server.sql
--   또는 psql 에 붙여 한 줄씩 실행한다.

\echo '=== 1. 섹터-ETF 대응표 =========================================='
-- 열린 버전은 최대 하나다(uk_sector_etf_map_version_open). 자료 버전이 이 id 를 가리킨다.
SELECT id,
       version,
       effective_to IS NULL AS 열림,
       is_sample,
       published_at
  FROM sector_etf_map_version
 ORDER BY id;

\echo '=== 2. 대응표에 붙은 종목 ======================================='
SELECT v.version,
       s.code   AS 섹터,
       e.instrument_id,
       e.listed_on
  FROM sector_etf_map_entry m
  JOIN sector_etf_map_version v ON v.id = m.map_version_id
  JOIN sector s ON s.id = m.sector_id
  JOIN representative_etf e ON e.id = m.etf_id
 ORDER BY v.version, s.code;

\echo '=== 3. 발행된 자료 버전 ========================================='
-- 같은 마감일을 다시 발행하려면 판 번호가 필요하다(KRX_DAILY_{YYYYMMDD}[_R{판}]).
-- 여기 이미 있는 이름과 겹치면 적재가 거부된다.
SELECT dataset_id,
       data_deadline,
       complete,
       is_sample,
       source,
       map_version_id
  FROM dataset_version
 ORDER BY data_deadline, dataset_id;

\echo '=== 4. 시계열이 얼마나 있나 ====================================='
SELECT 'daily_price'       AS 표, count(*) AS 행수,
       min(date)::text     AS 처음, max(date)::text AS 마지막
  FROM daily_price
UNION ALL
SELECT 'daily_index', count(*), min(date)::text, max(date)::text
  FROM daily_index
UNION ALL
SELECT 'corporate_action', count(*), min(ex_date)::text, max(ex_date)::text
  FROM corporate_action
UNION ALL
SELECT 'data_gap', count(*), NULL, NULL FROM data_gap
UNION ALL
SELECT 'suspension_period', count(*), NULL, NULL FROM suspension_period
UNION ALL
SELECT 'reconciliation_record', count(*), NULL, NULL FROM reconciliation_record;

\echo '=== 5. 자료 버전별 분량 ========================================='
-- 자료 버전이 여럿이면 어느 것이 실제로 쓰이는지 여기서 보인다.
SELECT dataset_id, count(*) AS 일봉행수
  FROM daily_price
 GROUP BY dataset_id
 ORDER BY dataset_id;
