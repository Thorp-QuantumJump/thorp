# Contracts: 대표 ETF 자료 확보

**Plan**: [../plan.md](../plan.md) | **Data model**: [../data-model.md](../data-model.md)

001이 다른 파트에 노출하는 계약 세 가지다. 계약마다 검증 픽스처를 함께 두어, 한쪽만 고치면 테스트가 깨지도록 한다. `backend/CLAUDE.md`가 브로커 메시지 계약에 적용한 방식과 같다.

| 계약 | 소비자 | 문서 |
|---|---|---|
| 섹터 목록·대표 상품 정보 조회 | FRONT (002·005) | [sector-api.md](./sector-api.md) |
| 공통 평가 기간 산식 | BACK (003 안내), Worker (004 실행) | [evaluation-window.md](./evaluation-window.md) |
| 서비스 저장소 자료 읽기 | Worker | [market-dataset.md](./market-dataset.md) |

## 계약 변경 순서

`evaluation-window.md`의 산식을 고칠 때는 다음 순서로 같은 MR에서 함께 바꾼다. 순서를 지키지 않으면 어느 쪽이 기준인지 흐려진다.

1. `contracts/evaluation-window.md`와 픽스처 `fixtures/evaluation-window-cases.json`
2. `worker/src/worker/evaluation_window.py`
3. `backend/.../sector/application/service/EvaluationWindowService.java`

양쪽 테스트가 같은 픽스처를 읽으므로 한쪽만 고치면 빌드가 실패한다.
