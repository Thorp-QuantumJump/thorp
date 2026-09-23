"""경제환경 검색 사전 계산 (spec 010).

010의 계산은 전부 여기 있다. Backend는 적재된 행을 읽어 내보낼 뿐이고(`AGENTS.md`
"Backend는 계산하지 않는다"), 사용자 요청 처리 중에는 척도도 거리도 계산하지 않는다
(spec FR-030, plan.md "오프라인 사전 계산 + 읽기 전용 조회").

모듈은 계산 순서대로 놓았다. 앞 모듈의 함수를 뒤가 부르고 그 반대는 없다::

    macro/      지표 소스 경계 (008) — 시계열·발표일·버전이 들어오는 유일한 통로
    comparison  지표별 비교 시점 판정 (FR-001)
    scale       연속 구간 판정, 중앙값·MAD·IQR, 보정 상수 (FR-005·FR-006)
    distance    z, E_i, D_g, 종합 거리, 지표·분야 기여 (FR-009·FR-010)
    era         시대별 위치 — 후보월 단위, 기준월 t와 무관 (FR-017)
    candidates  후보 제한·전체 순위·상위 10 (FR-012·FR-013)
    explain     비슷한 점·다른 점 템플릿 조립 (FR-015)
    snapshot    기준월 단위 조립, 검색 상태, content_hash (FR-029)
    watermark   재계산 범위 t ≥ M 판정 (FR-030)
    load        PostgreSQL 적재 — revision·기준월·보관 정리
    rounding    12자리 반올림 — 저장·비교 직전 (research R4)

**지표 ID·지표명·분야 이름을 여기에 두지 않는다**(spec FR-004). 값은 `macro` 경계가
돌려주는 것만 쓰며, 008에 지표가 늘어도 이 패키지는 고치지 않는다.
"""
