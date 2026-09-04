# 단일 프로세스 baseline 측정 (v0.1.1)

분산 처리 speedup을 주장하려면 **분모**가 필요하다. 이 문서는 그 분모를 어떻게 재고,
어떤 조건에서 잰 수치라야 신뢰할 수 있는지를 고정한다.

- 러너: `worker/src/worker/baseline.py` (`python -m worker.baseline`)
- 계산 정의: [backtest-spec.md](./backtest-spec.md)

## 1. 무엇을 재는가

| 항목 | 의미 |
|---|---|
| Job 1건 소요시간 | 전략 1개 × 파라미터 1조합 × 유니버스 전체 × 기간 전체 |
| N건 순차 소요시간 | 단일 프로세스 처리량(jobs/sec) |
| 데이터 로드 시간 | **계산 시간과 분리해서** 기록. 분산 시 워커마다 중복 발생하는 비용이다 |
| 구간별 시간(`--profile`) | 신호 생성 / 체결 시뮬 / 지표 계산 |
| 예상 총 소요시간 | v0.1.2 예상 Job 수 × Job 1건 시간 → 분산 도입 근거 |

**baseline은 Worker가 실제로 쓰는 것과 동일한 `engine.run_portfolio_backtest`를 호출한다.**
Kafka·DB·프로세스 다중화만 빠진 순차 실행이라 비교가 성립한다.

## 2. 측정 조건 (지키지 않으면 수치를 버린다)

1. **같은 머신, 같은 컨테이너에서 측정한다.** baseline과 분산을 다른 환경에서 재면
   speedup은 두 머신의 성능 차이를 재는 꼴이 된다.
2. **공식 수치는 서버(AWS Lightsail, 4 vCPU) 컨테이너에서만 낸다.** 로컬 실행은 배선 확인용이다.
3. **BLAS 스레드 수를 1로 고정한다** (`OMP_NUM_THREADS=1`). 고정하지 않으면 "단일 프로세스"가
   내부적으로 멀티코어를 써서 분산 효과가 실제보다 작게 나온다. compose에 이미 박아두었다.
4. **측정 중 다른 부하를 멈춘다.** 4 vCPU에서 Jenkins 빌드나 백엔드가 같이 돌면 측정은 무의미하다.
   `--no-deps`로 Kafka·PostgreSQL을 띄우지 않고 러너만 실행한다.
5. **버스팅 스로틀링을 피한다.** Lightsail은 버스터블 인스턴스라 CPU를 오래 100%로 쓰면
   크레딧이 소진되어 강제로 느려진다. `--cooldown`으로 반복 사이에 쉬고, 측정 후
   Lightsail 콘솔의 CPU 사용률 그래프가 지속 가능 영역(sustainable zone)을 오래 벗어났으면
   **그 회차는 버린다.**
6. **데이터 스냅샷을 고정한다.** 시세가 갱신되면 수치도 흔들린다. 결과 JSON의 `input_rows`가
   이전 측정과 다르면 같은 조건이 아니다.
7. **warmup 1회 후 최소 5회 반복하고 median을 쓴다.** 평균만 보면 튀는 값에 속는다.

러너는 위 조건 중 위반이 감지되면 결과 JSON의 `warnings`에 남긴다. **`warnings`가 비어 있지 않은
결과는 공식 수치로 쓰지 않는다.**

## 3. 실행 방법

### 서버 (공식 측정)

```bash
# 시세 스냅샷이 ./data/processed 에 있어야 한다
docker compose --profile app build worker
docker compose --profile app run --rm --no-deps worker \
    python -m worker.baseline \
      --start 2021-01-01 --end 2025-12-31 \
      --jobs 20 --repeat 5 --warmup 1 --cooldown 30 \
      --profile --out /app/out/baseline-$(date +%Y%m%d).json
```

결과는 호스트의 `benchmarks/` 에 떨어진다. (`infra/scripts/run-baseline.sh` 로도 실행 가능)

### 로컬 (배선 확인용, 공식 수치 아님)

```bash
cd worker
OMP_NUM_THREADS=1 python -m worker.baseline --synthetic --jobs 5 --repeat 3 --profile
```

## 4. 결과 기록

측정할 때마다 아래 표에 한 줄 추가하고, 원본 JSON은 `benchmarks/` 에 커밋한다.

| 측정일 | 환경 | 유니버스/기간 | 입력 행 수 | Job 1건(median) | 처리량 | JSON |
|---|---|---|---|---|---|---|
| (미측정) | Lightsail 4 vCPU / 컨테이너 | 10종목 / 5년 | - | - | - | - |

> **서버 측정 전이다.** 아래는 로컬(합성 데이터) 참고치이며 공식 수치가 아니다.
>
> | 측정일 | 환경 | 유니버스/기간 | 입력 행 수 | Job 1건(median) | 처리량 |
> |---|---|---|---|---|---|
> | 2026-09-04 | 개발 PC 22 vCPU / 컨테이너 밖 / 합성 데이터 | 10종목 / 5년 | 13,040 | 12.2 ms | 82.3 jobs/sec |

## 5. 해석과 후속 판단

측정치가 나오면 아래 기준으로 v0.1.2 계획을 확정한다.

| 조건 | 판단 |
|---|---|
| 예상 총 소요시간이 수 분 이내 | **분산의 효과가 눈에 안 띈다.** 알고리즘·파라미터 조합 수를 늘려 작업량을 키운 뒤 비교해야 의미가 생긴다 |
| 예상 총 소요시간이 수십 분 이상 | 분산 처리 도입 근거 확보. 예정대로 진행 |
| 데이터 로드가 계산 시간을 압도 | 병목이 연산이 아니라 I/O다. 워커를 늘려도 안 빨라지므로 데이터 배치 전략을 먼저 손봐야 한다 |

Spark/Hadoop 도입 판단도 이 수치를 근거로 한다. (`CLAUDE.md` 아키텍처 원칙 4)

## 6. 분산 측정 시 주의 (v0.1.2에서 사용)

- **baseline을 그때 다시 잰다.** 그 사이 전략 수·엔진 코드·데이터가 바뀌므로 예전 수치를
  분모로 쓰면 안 된다. baseline → 분산 순서로 같은 세션에서 연달아 측정한다.
- **speedup 상한은 vCPU 수(4)** 다. 워커를 1→2→3→4→8로 늘려 곡선을 그리면
  "코어 수가 상한이고 그 지점까지 도달했다"는 근거 있는 주장이 된다.
- **`backtest.jobs` 토픽 파티션 수 ≥ 최대 워커 수.** 현재 compose는 토픽 자동 생성이라
  파티션이 1개다. 이 상태로는 워커를 몇 개 띄워도 1개만 일하고 speedup은 1.0x가 나온다.
- **결과 정합성 확인.** 결과 JSON의 `result_sample` 지표가 baseline과 분산에서 동일해야 한다.
  같은 답을 더 빨리 내야 의미가 있다.
