/**
 * 경제환경 사례와 전략 비교 연결의 기술 구현 계층. {@code application} 인터페이스의 구현으로 영속화·브로커 메시징·주기 작업·설정을 둔다.
 *
 * <p>{@code regime-link} exchange와 큐의 토폴로지 선언은 백엔드 책임이다. 워커는 선언하지 않고 큐가 없으면 종료하므로 이 계층의 {@code
 * Declarables}가 유일한 선언 자리다(계약 regime-link-messaging §2).
 *
 * <p>큐·exchange·routing key와 {@code schemaVersion}은 설정이 아니라 계약 상수로 두어 워커 {@code
 * worker.regime_link.messaging}과 같은 값을 유지한다.
 *
 * <p>근거: specs/011-regime-strategy-link/contracts/regime-link-messaging.md, plan.md.
 */
package com.ssafy.thorp.regimelink.infrastructure;
