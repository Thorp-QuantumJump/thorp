# 운영 데이터 배치 실행 — 경제지표 적재와 경제환경 사전 계산

S15P21E205-446. 운영 호스트에서 spec 008(경제지표)과 spec 010(경제환경 검색)의 배치를 돌리는 절차다.
배치는 상주 서비스가 아니다. `infra/docker-compose.prod.yml`의 `data-batch` 서비스(`batch` 프로필)를
**필요할 때 한 번씩** `run --rm`으로 띄운다. Backend는 결과를 읽기만 한다(원칙: Backend는 계산하지 않는다).

## 0. 전제

- Backend가 배포돼 Flyway 마이그레이션이 끝나 있다. 경제지표 테이블(`macro_*`)과 검색 결과 테이블
  (`regime_search_*`)은 Backend 마이그레이션이 만든다. 배치는 테이블을 만들지 않는다.
- `infra/.env.prod`에 `ECOS_API_KEY`·`KOSIS_API_KEY`·`DATA_GO_KR_SERVICE_KEY`가 있다(수집할 때만 필요).
  값은 서버에서만 넣는다. 저장소·로그·티켓에 적지 않는다.
- 아래 명령은 모두 서버의 `$DEPLOY_DIR/infra`에서 실행한다.

```bash
cd "$DEPLOY_DIR/infra"
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod --profile batch"
```

## 1. 이미지 빌드

코드가 바뀐 배포 뒤에는 매번 다시 빌드한다. 배포 파이프라인은 이 이미지를 만들지 않는다.

```bash
$COMPOSE build data-batch
```

## 2. 적재 전 백업

사전 계산은 기준월마다 새 revision을 쓰고, 끝나면 기록이 가리키지 않는 옛 revision을 정리한다
(spec 010 FR-029). 정리된 행은 되살릴 수 없으므로 **처음 돌리거나 모델 버전이 바뀐 뒤 처음 돌릴 때는
먼저 백업한다.**

```bash
./scripts/backup-db.sh
```

## 3. 경제지표 (spec 008)

### 3.1 등록표 등록 — 처음 한 번

```bash
$COMPOSE run --rm data-batch python scripts/register_macro_registry.py --dry-run
$COMPOSE run --rm data-batch python scripts/register_macro_registry.py
```

이미 등록돼 있으면 "이미 포인터가 있다"를 출력하고 종료 코드 1로 끝난다. 정상이다 — 덮어쓰지 않는다.

### 3.2 수집

```bash
# 처음: 전 구간
$COMPOSE run --rm data-batch python scripts/ingest_macro.py --mode initial --dry-run
$COMPOSE run --rm data-batch python scripts/ingest_macro.py --mode initial
# 그 뒤: 새 발표분과 최근 N개월
$COMPOSE run --rm data-batch python scripts/ingest_macro.py --mode regular
```

원천 응답 원본은 `$DATA_DIR/raw`, 정제 결과는 `$DATA_DIR/processed`에 남는다.

## 4. 경제환경 사전 계산 (spec 010)

```bash
# 계산만 하고 적재하지 않는다 — 실패한 기준월이 있는지 먼저 본다
$COMPOSE run --rm data-batch python scripts/precompute_regime.py --source macro --all --dry-run
# 적재
$COMPOSE run --rm data-batch python scripts/precompute_regime.py --source macro
```

- 재계산 범위는 배치가 정한다. 자료가 바뀐 달부터 다시 돌고, **범위마다 현재 모델 버전으로 적재한
  행이 없으면 그 범위는 전 구간을 다시 돈다**(data-model §6). 모델 버전이 바뀐 뒤(예: 2026-09-25 `REGIME-V3-EQ.1`·`REGIME-V1.1`)
  첫 실행이 여기에 해당한다 — `--all`을 줄 필요가 없다.
- 실데이터 전 구간(기준월 약 4,500개)은 로컬 실측 약 60초다.
- 출력의 `실패` 목록이 비어 있어야 한다. 기준월 하나의 실패는 배치를 멈추지 않고 그 기준월만 이전
  revision으로 남긴다.
- `VersionNotBumpedError`가 나오면 자료·등록표 버전이 같은데 결과가 달라진 것이다 — 계산·문장·설정을
  바꾸고 모델 버전을 올리지 않았다. 배포를 되돌리거나 모델 버전을 올린 뒤 다시 돌린다.

## 5. 확인

```bash
# 범위별 모델 버전과 기준월 수 — 종합(COMPOSITE)이 REGIME-V3-EQ.1, 분야(FIELD)가 REGIME-V1.1이어야 한다
docker exec thorp-postgres psql -U "$(sed -n 's/^POSTGRES_USER=//p' .env.prod)" \
  -d "$(sed -n 's/^POSTGRES_DB=//p' .env.prod)" -c \
  "select mode, model_version, count(*) from regime_search_snapshot group by 1, 2 order by 1, 2"
```

화면: 시장 흐름의 '비슷했던 때 보기'에서 **Thorp 종합 비교**를 연다.

- 사례에 "○○ 흐름은 비슷했습니다/달랐습니다" 블록 문장이 보인다.
- 사례 상세에 **경제 블록별 거리와 기여**(실물 경기·대외 거래·물가·금융 여건)가 보인다.
- 특이 사례에는 "같은 흐름에 속한 지표들이 평소보다 엇갈려 움직였습니다"와 **당시 맥락** 링크가 붙는다.

## 6. 되돌리기

- 기준월 하나: `precompute_regime.py --withdraw --mode composite --baseline-month YYYY-MM --reason "..."`
  (최신 revision을 철회하고 이전 revision이 다시 조회된다. 이전 revision이 정리됐으면 되돌릴 것이 없다).
- 전체: 2절의 백업으로 DB 전체를 복구한다. **배치 뒤에 생긴 모든 쓰기(사용자 기록 포함)가 사라진다.**
  서비스를 멈추고, DB를 새로 만들고, 백업을 넣는다.

  ```bash
  PGU=$(sed -n 's/^POSTGRES_USER=//p' .env.prod); PGD=$(sed -n 's/^POSTGRES_DB=//p' .env.prod)
  DATA_DIR=$(sed -n 's/^DATA_DIR=//p' .env.prod)
  docker compose -f docker-compose.prod.yml --env-file .env.prod stop backend worker worker-flex
  docker exec thorp-postgres dropdb -U "$PGU" --if-exists "$PGD"
  docker exec thorp-postgres createdb -U "$PGU" "$PGD"
  gunzip -c "$DATA_DIR/backup/<백업 파일>.sql.gz" | docker exec -i thorp-postgres psql -U "$PGU" -d "$PGD"
  docker compose -f docker-compose.prod.yml --env-file .env.prod up -d backend worker worker-flex
  ```

## 7. 당시 맥락 표 갱신

`data/config/regime-context-v1.json`(KDI 경제동향·기준금리 변경·기준순환일)은 개발 환경에서
`scripts/collect_regime_context.py`로 다시 만들고 커밋한다. 운영 배치는 이미지에 들어간 표를 읽는다.
후보 사례는 모두 12개월보다 오래된 구간이라 새 호가 늘어도 기존 결과는 바뀌지 않는다. **이미 있는 항목을
고치는 갱신**은 결과를 바꾸므로 모델 버전을 함께 올린다(4절의 `VersionNotBumpedError`).
