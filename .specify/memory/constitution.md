<!--
Sync Impact Report
- Version change: 1.0.0 → 1.0.1 (2026-09-03 — 서비스명 표기를 Quantum Jump에서 Thorp로 정정.
  원칙 내용 변경 없음. 팀명 "Quantum Jump"는 유지한다)
- Version change: (template, unversioned) → 1.0.0 (initial ratification; pre-merge draft
  revised 2026-09-02 — version kept at 1.0.0 because the document had not yet been adopted)
- Modified principles (draft revisions, latest first):
  - II 근거: "식별자를 즉시 반환" 파생 결과 문장 제거 (API 동작을 확정하지 않도록)
  - III 3항: "나중의 도입을 어렵게 만드는 결정은 피한다" → 현재 요구사항을 복잡하게 하지 않는
    범위에서 연산·데이터 경계를 특정 구현 기술에 불필요하게 결합하지 않는다 (과도한 추상화
    정당화 여지 제거)
  - IV 2항: "같은 MR에서 함께 갱신" 강제 → 비호환 상태의 Merge/배포 금지, 단계적 변경 허용
  - 품질 게이트: "CI는 동일한 검증 실행, 미통과 Merge 불가" → 파트별 적용 가능한 필수 검증
    통과 요건, CI는 최소한 필수 검증 포함
  - V. 백테스트 결과는 신뢰할 수 있어야 한다: "멱등성"과 "재현성"을 별개 조항으로 분리;
    "전략이 지표를 직접 산출해서는 안 된다" → 공통 지표는 동일한 정의·계산 기준을 사용해야
    한다는 특성으로 완화, 구현 구조는 헌장에서 정하지 않음
  - 보안·데이터 제약: "환경변수로만 주입" → 코드·저장소에 포함하지 않고 안전한 runtime
    configuration 또는 secret 관리 수단으로 주입 (주입 수단을 강제하지 않음)
  - 개발 워크플로우와 품질 게이트 → 품질 게이트: Git 컨벤션, AI 코드리뷰 절차, 릴리즈 주기를
    헌장 밖(CLAUDE.md)으로 이동; 자동화 검증 통과 요건만 유지
  - 전체: 파트 이름(worker 등), 전달 방식(폴링), 언어명 등 구현 선택을 가리키는 표현 제거
  - I. 계산은 Worker에서만 한다 → I. 백테스트 연산은 독립적인 실행 경계를 가진다
  - II. 백테스트 실행은 비동기 Job이다 → II. 백테스트 실행은 요청 생명주기와 독립적이며 추적
    가능해야 한다
  - III. 빅데이터 기술은 실측 후 도입한다 (저장 포맷 지정 제거)
- Added sections: Core Principles I–V, 보안·데이터 제약, 품질 게이트, Governance
- Removed sections: 개발 워크플로우 항목(Git 컨벤션, AI 코드리뷰 게이트, 릴리즈 주기) —
  루트 CLAUDE.md의 "Git Convention", "AI 협업 규칙"에 이미 기록되어 있어 그곳을 단일 출처로 삼음
- Sources: README.md, CLAUDE.md (root + frontend/backend/worker/data), Jenkinsfile,
  docker-compose.yml, backend/build.gradle, worker/pyproject.toml, data/pyproject.toml,
  backend BacktestJobRequest ↔ worker models.py contract, worker strategies/base.py ↔ engine.py
- Deliberately excluded (changeable tech choices / design decisions, per user input):
  Worker component and replica count, message broker / DB vendor (Kafka, PostgreSQL),
  polling vs. push for status delivery, storage format (Parquet) and partition layout,
  secret injection mechanism (env vars), framework and language versions, formatter/linter
  tool names, per-part folder/package layouts, MVP milestone scope, frontend styling choices,
  Git branch/commit convention, review process, release cadence.
- Follow-up TODOs: none
-->

# Thorp Constitution

퀀트 전략 백테스트 플랫폼(Thorp)의 모든 파트에 장기적으로 적용되는 원칙만 담는다.
이 문서는 "반드시 지켜야 하는 특성"을 정하고, 그 특성을 만족시키는 아키텍처·기술·배포 형태는
정하지 않는다. 그런 결정은 `docs/adr/`, `docs/specs/`, 각 파트의 `CLAUDE.md`에 두며 측정과
요구사항에 따라 바꿀 수 있다. 브랜치·커밋 컨벤션, 리뷰 절차, 릴리즈 주기 같은 작업 방식은
루트 `CLAUDE.md`에 둔다.

## Core Principles

### I. 백테스트 연산은 독립적인 실행 경계를 가진다

- 백테스트 연산은 API 요청 처리와 독립적인 실행 경계를 가져야 한다(MUST).
- Backend API 서버가 장시간 백테스트 계산을 요청 처리 과정에서 직접 수행해서는 안 된다(MUST NOT).
- 계산 실행 방식(별도 컴포넌트 여부, 인스턴스 수, 분배 방식)은 부하 측정과 요구사항을 바탕으로
  결정하며, 특정 실행 기술이나 배포 형태를 헌장에서 강제하지 않는다.

근거: 연산과 요청 처리가 한 경계에 있으면 계산량을 따로 측정할 수 없고, 분산 처리의 당위성을
수치로 검증한다는 프로젝트 목표를 달성할 수 없다.

### II. 백테스트 실행은 요청 생명주기와 독립적이며 추적 가능해야 한다

- 백테스트 실행은 그것을 시작한 요청의 생명주기와 독립적이어야 한다(MUST). 요청 처리가 끝나거나
  클라이언트 연결이 끊겨도 실행은 영향을 받지 않는다.
- 사용자는 실행을 식별할 수 있어야 하고, 그 식별자로 진행 상태와 결과를 실행 이후에도 추적할 수
  있어야 한다(MUST).
- 실행 상태의 집합과 전이는 이를 생산·소비하는 모든 파트가 공유하는 계약으로 `docs/specs/`에
  문서화하며, 한 파트가 단독으로 바꾸지 않는다. 상태를 전달하는 방식은 헌장에서 정하지 않는다.

근거: 계산 시간이 백테스트마다 크게 달라지므로 실행이 요청 생명주기에 종속되면 원칙 I을 만족할
수 없다. 따라서 실행과 요청 처리는 독립적으로 진행될 수 있어야 한다.

### III. 빅데이터 기술은 실측 후 도입한다

- Spark/Hadoop 등 대규모 분산 처리 기술을 계산량 실측 없이 선도입해서는 안 된다(MUST NOT).
  도입 제안은 병목을 보여주는 측정 수치를 근거로 해야 한다.
- 새 인프라·의존성 추가는 "지금 해결하는 문제"를 명시해야 한다. 미래 대비만을 이유로 한 추가는
  거절한다.
- 현재 요구사항을 불필요하게 복잡하게 만들지 않는 범위에서, 연산과 데이터의 경계를 특정 구현
  기술에 불필요하게 결합하지 않는다. 구체적인 저장 포맷과 구조는 헌장에서 정하지 않는다.

근거: 단순한 구조로 시작해 측정으로 판단하는 것이 팀의 명시적 합의다.

### IV. 파트 경계의 계약은 명시하고 함께 바꾼다

- 파트 경계를 넘는 데이터(API 요청·응답, 실행 요청 메시지, 결과 스키마)는 문서화된 계약을 가진다.
  API와 메시지의 JSON 필드는 camelCase를 사용한다(MUST).
- 계약 변경은 생산자와 소비자의 호환성을 함께 고려해야 하며, 어느 한쪽이 호환되지 않는 상태의
  변경을 Merge하거나 배포해서는 안 된다(MUST). 변경은 필요에 따라 단계적으로 수행할 수 있다.
- 스펙이 애매하면 코드를 작성하기 전에 `docs/specs/`의 문서를 먼저 확인하거나 갱신한다(SDD).
  아키텍처 수준의 결정은 `docs/adr/`에 기록한다.

근거: 서로 다른 언어·런타임으로 나뉜 파트가 같은 도메인을 다루므로 계약 불일치가 가장 흔한
장애 원인이 된다.

### V. 백테스트 결과는 신뢰할 수 있어야 한다

- 미래 데이터 참조(look-ahead bias)를 금지한다. 특정 일자의 매매 신호는 그 일자까지의 데이터만
  사용한다(MUST).
- **멱등성**: 동일한 실행 요청의 재전달·재시도는 중복 결과나 중복 상태 변경을 발생시키지 않아야
  한다(MUST).
- **재현성**: 동일한 전략, 파라미터, 시장 데이터 및 백테스트 조건으로 실행한 결과는 재현 가능해야
  한다(MUST).
- **비교 가능성**: 전략 간 비교에 사용되는 공통 성과지표(수익률, MDD, Sharpe 등)는 전략 구현과
  독립적인 동일한 정의와 계산 기준을 사용해야 한다(MUST). 이를 구현하는 구조는 헌장에서 정하지
  않는다.

근거: 결과가 재현·비교되지 않으면 플랫폼의 존재 이유가 사라진다.

## 보안·데이터 제약

- 접속 정보, API Key 등의 비밀값은 코드와 저장소에 포함하지 않고 안전한 runtime configuration
  또는 secret 관리 수단을 통해 주입한다(MUST). 저장소에는 값이 비어 있는 예시 파일만 둘 수 있다.
- 수집·정제된 시장 데이터 파일은 저장소에 커밋하지 않는다.
- 클라이언트에 전달되는 설정은 비밀값을 포함해서는 안 된다.

## 품질 게이트

- 각 파트는 해당 파트에 적합한 lint, 테스트, 빌드 등의 자동화 검증을 정의한다. 변경은 적용 가능한
  필수 검증을 통과해야 Merge할 수 있다(MUST). CI는 최소한 해당 필수 검증을 포함한다.
- 코드 포맷과 lint 규칙은 자동화 도구로 강제한다. 구체적인 도구와 설정은 각 파트가 정한다.
- 리뷰 절차, 브랜치·커밋 컨벤션, 릴리즈 주기는 루트 `CLAUDE.md`를 따른다. 이 문서는 그것을
  중복 정의하지 않는다.

## Governance

- 이 헌장은 각 파트의 `CLAUDE.md`, `docs/`, 개인 작업 방식보다 우선한다. 충돌 시 헌장을 따르고,
  헌장이 틀렸다면 개정 절차로 고친다.
- **개정 절차**: 헌장 변경은 MR로 제안하고, 변경 이유와 영향 범위를 MR 본문에 기록한다.
  팀 합의 후 Merge하며, Merge 시점에 버전과 `Last Amended` 날짜를 갱신한다.
- **버전 정책(SemVer)**: MAJOR = 원칙 삭제 또는 의미가 바뀌는 재정의, MINOR = 원칙·섹션 추가
  또는 실질적 확장, PATCH = 문구·오타·명확화.
- **준수 확인**: 모든 기능의 spec/plan은 헌장 원칙(특히 I·II·IV·V) 위반 여부를 확인하는 항목을
  포함한다. 원칙에서 벗어나는 설계는 그 사유와 대안 검토를 문서에 남겨야 한다.
- **포함 기준**: 팀이 명시적으로 합의했고 프로젝트 전반에 장기 적용되는 "요구 특성"만 이 문서에
  넣는다. 그 특성을 구현하는 아키텍처·기술·배포 결정은 ADR과 파트 문서에 둔다. 현재 구현되어
  있다는 사실만으로 원칙으로 승격하지 않으며, 확실하지 않은 규칙은 만들지 않는다.

**Version**: 1.0.1 | **Ratified**: 2026-09-02 | **Last Amended**: 2026-09-03
