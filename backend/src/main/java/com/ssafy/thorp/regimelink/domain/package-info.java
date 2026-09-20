/**
 * 경제환경 사례와 전략 비교 연결의 순수 도메인 계층. 링크·검색 식별자, 사례 참조 {@code (snapshotKey, endMonth)}, 사후 구간, 가용성 판정,
 * 시나리오와 링크 상태의 모델과 불변식을 둔다. Spring·JPA·Jackson에 의존하지 않는다.
 *
 * <p>사례 식별자는 {@code snapshotKey}와 {@code endMonth}를 {@code ~}로 이은 한 값이고 011은 {@code snapshotKey}를
 * 분해하지 않는다(research R6).
 *
 * <p>유지율·초과 수익·승패·연율화 수익률에 해당하는 필드는 어떤 모델에도 두지 않는다(spec FR-013·FR-021, 계약 §6).
 *
 * <p>근거: specs/011-regime-strategy-link/data-model.md, plan.md.
 */
package com.ssafy.thorp.regimelink.domain;
