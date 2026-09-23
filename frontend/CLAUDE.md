# CLAUDE.md — frontend (FRONT 파트)

React 19 + Vite + TypeScript SPA. 알고리즘/파라미터 선택 UI와 백테스트 결과 시각화 담당.

## 검증 명령 (작업 후 반드시 실행)

```bash
npm run lint && npm run build && npm test
```

`npm test`는 `test:dom`(Vitest)과 `test:legacy`(`node --test`)를 차례로 돌린다. **결과의 `skipped` 수를 확인한다.** Vitest가 수집하는 파일은 `vite.config.ts`의 `test.include`가 정하는데 이 값이 허용 목록이라, 목록에 걸리지 않는 테스트 파일은 실패하지 않고 조용히 수집되지 않는다. 새 테스트 파일을 만들 때는 `include`에 그 경로가 걸리는지 반드시 확인한다.

## 폴더 규칙

- `src/pages/` 라우트 단위 화면, `src/components/` 재사용 컴포넌트
- `src/api/` 서버 통신 전용 — 컴포넌트에서 axios 직접 호출 금지, `apiClient` 경유
- `src/types/` 백엔드 DTO와 1:1 대응 타입 (`backtest.ts` 참고). 백엔드 스펙 변경 시 여기부터 갱신
- `src/hooks/` 커스텀 훅
- `src/components/records/` 내 기록(spec 006) 목록 줄·펼침 층·더 보기 메뉴·대화상자. 성과 수치와 성과 정렬 선택지를 두지 않고(원칙 III), '설정 바꿔 비교'는 005의 `prefillStrategySettings`를 `reopenSettings.ts` 어댑터로 부를 뿐 인계 빌더를 따로 만들지 않는다

## 규칙

- 결과 화면(`/results/:token`)은 `backtestId`(`BT-…`)를 받지도 보이지도 않는다(spec 005 SC-009).
- 해석 요약·상태 문장은 `components/result/{summarySentences,statusSentences}.ts`의 틀에서만 나온다(spec 005 SC-004). 문장을 컴포넌트에서 짓지 않는다.
- 용어 도움말은 `components/learning/TermHelp.tsx`(`termId`·`label`·`returnTo`) 하나를 쓴다. 글 본문과 013이 소유하는 용어 문장을 화면 코드에 상수로 두지 않는다(013 FR-015).
- 013이 소유하지 않는 설명(지표 계산식, 그 화면에서만 쓰는 구분, 조작 안내)은 `components/common/InlineHelp.tsx`에 담아 물음표 뒤로 내린다. TermHelp와 달리 설명을 props로 받는다. 013이 그 용어를 소유하게 되면 TermHelp로 옮기고 화면에서 문장을 지운다(013 FR-015).
- FR이 표시를 요구하는 문장은 도움말로 내리지 않는다. 002 FR-009·FR-016(선택 섹터의 두 가지 뜻, 0% 섹터의 뜻)과 003 FR-004(기본값은 편의용 출발점)가 그 예다.
- `sessionStorage` 키 `thorp.learning.adminDraft.{articleId}`는 관리 화면의 저장하지 않은 편집 내용과 그 기반 `draftVersion`을 보관한다. 저장 성공 시 지우며, 복원할 때는 보관한 판 번호를 유지한다. 일반 사용자 배우기 화면은 저장소에 쓰지 않는다.
- 컴포넌트는 함수형, 파일명 PascalCase(`BacktestForm.tsx`), 훅은 `use` 접두사
- API 필드는 camelCase (백엔드와 동일)
- 백엔드 호출은 `/api` 프록시 경유 (vite.config.ts에 설정됨) — URL 하드코딩 금지
- 환경변수는 `VITE_` 접두사만 클라이언트에 노출됨
- 백테스트는 비동기: 생성 후 `QUEUED → RUNNING → COMPLETED` 폴링 UI를 전제로 설계
- 전략 이름·축·기본값·허용 범위는 `GET /strategies/registry` 응답에서만 읽고 코드에 상수로 두지 않는다(spec 003 FR-005).
- `sessionStorage` 키 `thorp.auth.signupProgress`는 회원가입 진행(이메일·발송/확인 완료 주소·단계·닉네임·전화번호)을
  보관해 화면을 떠났다 돌아와도 인증번호를 다시 받지 않게 한다. **비밀번호와 인증번호는 넣지 않는다.**
  가입 성공 시 지운다.
- `sessionStorage` 키 `thorp.auth.loginEmail`은 로그인 화면에 쳐 둔 이메일을 보관한다.
  **비밀번호는 넣지 않는다.** 로그인 성공 시 지운다.
- `localStorage` 키 `thorp.design`은 디자인 개편 전후 전환값(`classic`·`new`)이다. `index.html`이 `?design=`에서 받아
  저장하고, `classic`이면 `<html data-design="classic">`을 붙인다. 새 색·모서리 토큰은 `index.css`의 기본 `:root`에,
  예전 값은 `[data-design='classic']`에 둔다. 개편 뒤 새로 생긴 토큰은 각 자리에서 `var(--토큰, 예전 값)`으로 쓰고
  classic에서 `initial`로 비운다 — 이 폴백을 빼면 classic이 예전 화면으로 돌아가지 않는다.
- 값의 오르내림(수익률 부호 등)은 `--thorp-up`·`--thorp-down`(상승 빨강·하락 파랑)을 쓴다. `--thorp-positive`·
  `--thorp-negative`는 성공·오류 상태 색이다. 시장 지표의 `tone`은 오르내림이 아니라 판단(좋음·나쁨)이라 여기에 섞지 않는다.
- `sessionStorage` 키: `thorp.strategySettings`(003 설정값), `thorp.diagnosis.sectorSelection`(002 인계 계약). 둘 다 탭 수명이며, 003은 002의 `diagnosis/selectionStorage`를 통해 섹터 선택을 읽는다. 이전 대역 키 `thorp.sectorSelection`은 사용하지 않는다.
