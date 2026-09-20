/**
 * 경제환경 사례와 전략 비교 연결의 유스케이스 계층. 사례별 가용성 판정, 잘라내기 작업 조립·발행, 워커 이벤트 반영, 검색 기록과 트랜잭션 경계를 담당하고, 저장소와 외부
 * 도메인은 이 계층의 인터페이스로만 참조한다.
 *
 * <p>이 계층은 시계열 산술을 하지 않는다 — 잘라낼 범위만 정해 메시지에 담고 구간 요약은 워커가 계산한다(plan Constraints, research R1·R2).
 *
 * <p>010 경제환경 검색·004 백테스트·005 공개 열람의 도메인 코드는 고치지 않고 {@code repository}·{@code port} 인터페이스로 읽기만
 * 한다(plan 교차 의존).
 *
 * <p>사례 목록은 010 표시 후보(≤10) 안에서만 다루고 순위를 다시 매기거나 아래 순위에서 채우지 않는다(spec FR-004·FR-017).
 *
 * <p>근거: specs/011-regime-strategy-link/plan.md, research.md.
 */
package com.ssafy.thorp.regimelink.application;
