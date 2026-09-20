"""사례 연결 작업·이벤트 메시지 계약(pydantic).

기준은 ``docs/specs/regime-link-messaging/``(README + ``examples/*.json``)이고 백엔드
``com.ssafy.thorp.regimelink.application.port.message``의 record와 같은 모양이다. 백테스트 계약인
``worker.contracts``에 넣지 않는다 — 큐도 스키마도 다르다.

``ContractModel``을 따라 JSON camelCase ↔ 내부 snake_case는 alias로, null은 키 생략으로 다루고
``extra="forbid"``에 기본값을 두지 않는다. 값이 없으면 지어내지 말고 계약 오류로 멈춘다.

내용은 T006(묶음 M1b)에서 채운다.
"""
