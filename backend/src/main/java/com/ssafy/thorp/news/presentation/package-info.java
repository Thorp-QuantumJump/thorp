/**
 * 시장 흐름 뉴스 묶음의 HTTP 입출력 계층. 묶음 조회와 기사 메타데이터 내보내기 응답을 담당한다.
 *
 * <p>READY·EMPTY·STALE·PREPARING은 모두 정상 응답으로 반환하고 외부 뉴스원 장애를 오류 응답으로 번지게 하지 않는다(spec
 * FR-014·FR-024).
 *
 * <p>응답 스키마에 기사 본문 필드를 두지 않으며 노출 모드를 클라이언트가 고르게 하지 않는다(spec FR-002·FR-016).
 *
 * <p>계약은 specs/012-market-news-digest/contracts/openapi.yaml이다.
 */
package com.ssafy.thorp.news.presentation;
