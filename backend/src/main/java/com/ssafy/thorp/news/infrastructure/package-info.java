/**
 * 시장 흐름 뉴스 묶음의 기술 구현 계층. application 인터페이스의 구현으로 영속화·외부 수집 클라이언트·스케줄링·설정을 둔다.
 *
 * <p>외부 호출은 공통 HTTP 보안 경계를 통과한다. HTTPS와 등록된 host만 허용하고 redirect 횟수·응답 크기·content-type·시간 예산을 제한하며
 * XML 외부 엔티티 처리를 비활성화한다(spec FR-020).
 *
 * <p>인증 정보와 뉴스원 접근 주소는 로그·저장된 오류 기록·지표 어디에도 남기지 않는다(spec FR-021).
 *
 * <p>근거: specs/012-market-news-digest/plan.md, research.md.
 */
package com.ssafy.thorp.news.infrastructure;
