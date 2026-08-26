# CLAUDE.md — frontend (FRONT 파트)

React 19 + Vite + TypeScript SPA. 알고리즘/파라미터 선택 UI와 백테스트 결과 시각화 담당.

## 검증 명령 (작업 후 반드시 실행)

```bash
npm run lint && npm run build
```

## 폴더 규칙

- `src/pages/` 라우트 단위 화면, `src/components/` 재사용 컴포넌트
- `src/api/` 서버 통신 전용 — 컴포넌트에서 axios 직접 호출 금지, `apiClient` 경유
- `src/types/` 백엔드 DTO와 1:1 대응 타입 (`backtest.ts` 참고). 백엔드 스펙 변경 시 여기부터 갱신
- `src/hooks/` 커스텀 훅

## 규칙

- 컴포넌트는 함수형, 파일명 PascalCase(`BacktestForm.tsx`), 훅은 `use` 접두사
- API 필드는 camelCase (백엔드와 동일)
- 백엔드 호출은 `/api` 프록시 경유 (vite.config.ts에 설정됨) — URL 하드코딩 금지
- 환경변수는 `VITE_` 접두사만 클라이언트에 노출됨
- 백테스트는 비동기: 생성 후 `QUEUED → RUNNING → COMPLETED` 폴링 UI를 전제로 설계
