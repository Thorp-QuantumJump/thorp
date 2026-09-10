/**
 * 시장 흐름 뉴스 묶음의 순수 도메인 계층. 기사·묶음·매체·허가 이력·수집 상태·규칙 버전 모델과 불변식을 둔다. Spring·JPA·Jackson에 의존하지 않는다.
 *
 * <p>기사 본문·HTML·이미지·GDELT Tone에 해당하는 필드는 어떤 모델에도 두지 않는다(spec FR-002).
 *
 * <p>외부 노출 식별자는 사업 키에서 결정론적으로 유도해 같은 대상을 다시 수집해도 같은 값이 나오게 한다(spec FR-025).
 *
 * <p>근거: specs/012-market-news-digest/spec.md, data-model.md.
 */
package com.ssafy.thorp.news.domain;
