# 서버 DB로 시장 자료 옮기기

로컬에서 수집·확인한 시장 자료를 서버 PostgreSQL로 옮기는 절차다.

## 왜 이 절차가 필요한가

수집 파이프라인(`data/`)은 **서버에 배포되지 않는다.** 배포되는 것은 backend·worker·postgres·
rabbitmq·kafka·nginx뿐이고, 서버 워커는 `MARKET_DATA_URL`로 **서버 PostgreSQL에서** 시장 자료를
읽는다. 그래서 로컬에서 만든 자료가 서버 DB에 들어가야 화면에 실제 자료가 보인다.

**로컬에서 먼저 적재해 확인한 뒤에 옮긴다.** 발행된 자료 버전은 UPDATE·DELETE가 트리거로 막혀
있어(FR-017) 서버에 잘못 넣으면 되돌릴 수 없다.

## 순서

### 1) 서버 상태를 본다 — `01-inspect-server.sql`

읽기만 하는 질의다. 확인할 것은 셋이다.

- **대응표 번호**: 자료 버전이 `map_version_id`로 대응표를 가리킨다. 서버 번호가 로컬과 다른데
  로컬 번호를 그대로 넣으면 **엉뚱한 대응표를 가리키는 자료**가 발행되고 지울 수 없다
- **이미 발행된 자료 버전 이름**: 겹치면 적재가 거부된다. 같은 마감일을 다시 내려면 판 번호를
  쓴다(`KRX_DAILY_{YYYYMMDD}[_R{판}]`, research.md 결정 4의 2026-09-17 보완)
- **대표 ETF가 있는지**: 없으면 대응표부터 옮겨야 한다

```bash
docker exec -i thorp-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < 01-inspect-server.sql
```

### 2) 서버 DB를 백업한다

**넣기 전에 반드시 한다.** 발행본은 지울 수 없으므로 되돌릴 방법은 백업뿐이다.

```bash
docker exec thorp-postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc \
    -f /tmp/thorp-before-market-data.dump
docker cp thorp-postgres:/tmp/thorp-before-market-data.dump ./
```

컨테이너 밖으로 꺼내 두어야 컨테이너를 지워도 남는다.

### 3) 로컬에서 자료를 뽑는다 — `02-export-local.sh`

**1단계 결과를 보고 만든다.** 서버의 대응표 번호와 발행할 자료 버전 이름이 정해져야 뽑을 수
있으므로, 이 스크립트는 1단계 뒤에 쓴다.

### 4) 서버에 넣는다

한 트랜잭션으로 넣어 중간에 실패하면 아무것도 남지 않게 한다.

### 5) 확인한다 — `03-verify-server.sql`

조정 종가가 원시 종가와 달라졌는지, 「분배를 모른다」 구간이 사라졌는지 본다.

## 옮기는 표와 순서

외래키 때문에 순서를 바꿀 수 없다.

| 순서 | 표 | 비고 |
|---|---|---|
| 1 | `sector` · `representative_etf` | 서버에 이미 있으면 건너뛴다 |
| 2 | `sector_etf_map_version` · `sector_etf_map_entry` | 열린 버전은 최대 하나다 |
| 3 | `dataset_version` | 이 뒤로는 되돌릴 수 없다 |
| 4 | `daily_index` · `daily_price` | 분량의 대부분 |
| 5 | `corporate_action` · `data_gap` · `suspension_period` | |

## 주의할 것

- **열린 대응표는 하나뿐이다.** 새 대응표를 열려면 기존 것을 먼저 닫는다
- **`dataset_version`을 넣은 뒤에는 그 행도, 그것이 가리키는 대응표 행도 지울 수 없다**
- 서버 시간대는 `Asia/Seoul`이다. `timestamptz` 값은 시간대를 함께 적는다
