# 운영 DB Flyway 전환 절차

지금까지 운영 DB의 `backtest_job` 스키마는 Hibernate `ddl-auto: update`가 만들어 왔다.
이제 `prod` 프로필은 `ddl-auto: validate` + Flyway 조합이므로, **Flyway가 관리하지 않는
기존 테이블 위에 그대로 배포하면 첫 마이그레이션에서 실패한다.**

`baseline-on-migrate`는 `false`로 고정되어 있다. 이 값을 켜면 Flyway가 기존 스키마를
"이미 V1이 적용된 상태"로 간주하고 기록만 남긴다. 실제 스키마가 `V1__init_schema.sql`과
다르면, 어긋난 채로 기록만 맞춰지고 이후 Hibernate `validate`가 기동을 막는다.
따라서 **아래 확인을 사람이 먼저 마친 뒤에만** baseline 여부를 결정한다.

## 전환 순서

### 1) 배포 전 DB 백업

전환 작업 전에 반드시 논리 백업을 뜨고, 복구 가능한 위치에 둔다.

```bash
docker exec thorp-postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/thorp-pre-flyway.dump
```

백업 파일을 컨테이너 밖으로 꺼내 두어야 컨테이너를 지워도 남는다.

```bash
docker cp thorp-postgres:/tmp/thorp-pre-flyway.dump ./thorp-pre-flyway.dump
```

### 2) `backtest_job` 테이블과 데이터 존재 여부 확인

```bash
docker exec -it thorp-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\dt"
```

```bash
docker exec -it thorp-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM backtest_job;"
```

- 테이블이 없다 → **새 DB로 취급한다. 5)로 간다.**
- 테이블이 있다 → 3)으로 간다. 행이 0건이더라도 스키마는 남아 있으므로 확인이 필요하다.

### 3) 실제 스키마와 V1 비교

```bash
docker exec -it thorp-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\d+ backtest_job"
```

`backend/src/main/resources/db/migration/V1__init_schema.sql`과 다음을 하나씩 대조한다.

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| `id` | `bigint` | NOT NULL | PK, identity(또는 기존 sequence 기본값) |
| `strategy` | `varchar(255)` | NOT NULL | |
| `start_date` | `date` | NOT NULL | |
| `end_date` | `date` | NOT NULL | |
| `status` | `varchar(20)` | NOT NULL | enum을 문자열로 저장 |
| `created_at` | `timestamp` | NOT NULL | |
| `updated_at` | `timestamp` | NOT NULL | |

컬럼 개수, 이름, 타입, 길이, NULL 허용 여부, PK 정의가 **모두** 같아야 한다.
`ddl-auto: update`는 컬럼을 추가만 하고 삭제하지 않으므로, V1에 없는 잔여 컬럼이
남아 있을 수 있다. 이 경우도 "같지 않음"으로 본다.

### 4) 기존 스키마가 V1과 정확히 같을 때만 baseline 버전 결정

- **완전히 같다** → 이 DB는 이미 V1 상태다. baseline 버전을 `1`로 잡는다.
- **다르다** → baseline을 적용하지 않는다. 차이를 먼저 해소해야 한다.
  기존 데이터를 버려도 되는 단계라면 DB를 비우고 5)로 가는 편이 안전하고 빠르다.
  데이터를 지켜야 한다면, 실제 스키마를 V1로 맞추는 보정 SQL을 사람이 검토한 뒤
  적용하고 다시 3)으로 돌아온다.

baseline은 **일회성 수동 작업**이며, 아래는 예시일 뿐 자동 배포 과정에 넣지 않는다.
Jenkins 파이프라인이나 Compose에 baseline 명령을 심으면, 확인 없이 반복 실행되어
위 판단 자체가 무의미해진다.

```bash
docker run --rm --network thorp-internal \
  -v "$PWD/backend/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:11 \
  -url=jdbc:postgresql://postgres:5432/DB이름 \
  -user=사용자 \
  -baselineVersion=1 \
  baseline
```

접속 정보는 셸 히스토리에 남지 않도록 그 자리에서 입력하거나 환경변수로 넘긴다.

### 5) 새 DB는 V1부터 실행

빈 DB에는 baseline이 필요 없다. 애플리케이션이 뜨면서 Flyway가 V1을 적용하고
`flyway_schema_history`를 만든다. 별도 조치가 없다.

### 6) 마이그레이션 실패 시

Flyway 실패는 애플리케이션 기동 실패로 이어진다. **원인을 해결하기 전에는 애플리케이션을
다시 띄우지 않는다.** `ddl-auto: validate`를 임시로 `update`로 되돌려 기동을 통과시키는
우회는 하지 않는다. 그렇게 하면 Hibernate가 스키마를 다시 손대서 원인이 덮인다.

확인 순서는 다음과 같다.

```bash
docker logs thorp-backend --tail 100
```

```bash
docker exec -it thorp-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c 'SELECT "installed_rank", "version", "description", "success" FROM "flyway_schema_history" ORDER BY "installed_rank";'
```

`success = false` 행이 남아 있으면 그 행을 정리하고(Flyway `repair`) 원인을 고친 뒤
다시 적용한다. 판단이 서지 않으면 1)의 백업으로 되돌린다.

## 검증할 때 하지 말 것

- `docker inspect`나 `docker compose config` 전체 출력을 그대로 공유하지 않는다.
  DB 비밀번호와 외부 API 키가 평문으로 함께 찍힌다. 확인이 필요하면 컨테이너 안에서
  필요한 항목만 조회한다.
- 백업 파일과 접속 정보를 저장소나 채팅에 올리지 않는다.
