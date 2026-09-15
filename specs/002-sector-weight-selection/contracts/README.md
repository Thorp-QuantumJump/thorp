# Contracts: 섹터·비중 선택

**Plan**: [../plan.md](../plan.md) | **Data model**: [../data-model.md](../data-model.md)

002는 서버 API를 만들지 않는다. 소유하는 계약은 화면 사이의 것 하나다.

| 계약 | 소비자 | 문서 |
|---|---|---|
| 섹터·비중 값의 탭 세션 보관과 화면 사이 인계 | FRONT (003이 읽고, 013이 쓴다) | [selection-handoff.md](./selection-handoff.md) |

## 002가 소유하지 않는 계약

아래는 002가 쓰기만 하는 계약이다. **이 폴더에서 다시 정의하지 않으며, 바꿔야 할 일이 생기면 소유 spec에서 바꾼다.**

| 계약 | 소유 | 002의 쓰임 |
|---|---|---|
| `GET /sectors` | [001](../../001-etf-market-data/contracts/sector-api.md) | 섹터 목록, 예시 대응표 여부(`isSample`), 대표 ETF 상장일. 409 `SECTOR_MAP_NOT_READY` 처리(FR-017) |
| `GET /sectors/{code}/instrument-profile` | [001](../../001-etf-market-data/contracts/sector-api.md) | 대표 상품 정보 패널(FR-010) |
| `GET /sectors/evaluation-window` | [001](../../001-etf-market-data/contracts/sector-api.md) | **부르지 않는다.** 워밍업 거래일 수를 필수 입력으로 받으므로 전략을 고른 뒤인 003의 몫이다([research.md](../research.md) 결정 4) |
| 인증과 세션 | [014](../../014-user-auth/plan.md) | 002의 화면은 보호 대상이 아니다. 로그인 왕복 뒤 값이 남는 것은 탭 세션 보관이 담당한다 |
