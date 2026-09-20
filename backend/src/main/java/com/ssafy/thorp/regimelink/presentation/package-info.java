/**
 * 경제환경 사례와 전략 비교 연결의 HTTP 입출력 계층. 가용성 조회·사례 구간 조회·검색 기록 생성·링크 생성·링크 조회·재시도 응답을 담당한다.
 *
 * <p>공개 토큰으로 여는 {@code GET}은 익명이 호출할 수 있고, 계산을 만드는 {@code POST}는 요청자가 없으면 계산을 시작하지 않고 {@code 401
 * AUTHENTICATION_REQUIRED}다(spec FR-025, research R7).
 *
 * <p>응답 스키마에 내부 PK·요청자 식별자·유지율·전략과 지수의 차이 값을 두지 않는다(계약 regime-link-api.md §6).
 *
 * <p>계약은 specs/011-regime-strategy-link/contracts/regime-link-api.md이고 요약본은
 * docs/specs/regime-link-api/README.md다.
 */
package com.ssafy.thorp.regimelink.presentation;
