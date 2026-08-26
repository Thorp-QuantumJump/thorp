# CLAUDE.md — worker (백테스트 워커)

Python 3.11 + pandas. Kafka `backtest.jobs`를 Consumer Group(`backtest-workers`)으로 소비해 백테스트 실행 후 PostgreSQL에 결과 저장. 같은 코드를 N개 실행해 수평 확장.

## 검증 명령 (작업 후 반드시 실행)

```bash
ruff check src tests && ruff format --check src tests && pytest
```

## 구조 규칙

- `strategies/` 전략은 `base.Strategy` 상속, `generate_signals(prices) -> Series`만 구현
  (전략은 신호만 출력, 체결·자산 계산·지표 산출은 `engine.py` 담당 — 이 분리를 깨지 말 것)
- `models.py` Job/Result 계약 — 백엔드 DTO와 필드 동기화 (JSON camelCase ↔ 내부 snake_case는 pydantic alias)
- `config.py` 설정은 환경변수로만 주입, 코드에 접속 정보 하드코딩 금지

## 규칙

- 타입 힌트 필수, 라인 100자 (ruff가 강제)
- 수치 계산은 pandas/numpy 벡터 연산 우선, Python 루프 지양
- Kafka 커밋은 처리 완료 후 수동 커밋 (at-least-once) — 백테스트는 재실행해도 안전하게(멱등) 작성
- 미래 데이터 참조(look-ahead bias) 금지: 신호는 당일까지 데이터만 사용
