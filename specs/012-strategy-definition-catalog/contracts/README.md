# Contracts: 전략 정의 카탈로그 (이관 완료)

이 디렉터리는 설계 시점의 계약 초안이 있던 곳이다. 계약은 **`docs/specs/strategy-definition/`으로
이관됐고 그곳이 유일 출처다**(헌장 IV: 파트 경계 계약은 `docs/specs/`에 문서화).

| 옮긴 것 | 현재 위치 |
|---|---|
| `strategy-definition.schema.json` | [`docs/specs/strategy-definition/`](../../../docs/specs/strategy-definition/strategy-definition.schema.json) |
| `strategy-catalog-api.schema.json` | [`docs/specs/strategy-definition/`](../../../docs/specs/strategy-definition/strategy-catalog-api.schema.json) |
| `strategy-execution-binding.schema.json` | [`docs/specs/strategy-definition/`](../../../docs/specs/strategy-definition/strategy-execution-binding.schema.json) |
| `test-vectors/*.json` | [`docs/specs/strategy-definition/test-vectors/`](../../../docs/specs/strategy-definition/test-vectors/) |
| `examples/*.json` (전략 정의 4종) | [`backend/src/main/resources/strategies/`](../../../backend/src/main/resources/strategies/) |

전략 정의 파일은 Backend 클래스패스가 거처다. 계약 문서 쪽의 `examples/`는 이제 **API 요청·응답 예시**를
담으며, 배포 정의와 어긋나지 않는지 검증기가 대조한다.

읽을 문서: [`docs/specs/strategy-definition/README.md`](../../../docs/specs/strategy-definition/README.md)
