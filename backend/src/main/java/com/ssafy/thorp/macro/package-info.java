/**
 * spec 008 경제지표 등록·수집의 <b>조회 쪽</b>. 17개 지표의 표시 정보를 한 응답으로 내보낸다.
 *
 * <p><b>여기서 계산하지 않는다.</b> 수집·변환·발행은 전부 {@code data/src/data_pipeline/macro/}에 있고, 이 패키지는 그 배치가 적재한
 * {@code macro_*} 행을 읽어 내보낼 뿐이다(루트 {@code AGENTS.md} "Backend는 계산하지 않는다").
 *
 * <p><b>응답은 한 번에 조립한다</b>(FR-023). 17개 메타데이터·값·최근 3개월 경로·분야별 {@code T_g}·{@code T_all}·수집 상태·세 버전이
 * <b>단일 스냅샷 응답</b>으로 나간다. 현재 포인터의 세 버전을 먼저 고정한 뒤 조립하는 이유는 포인터 교체가 조회 도중에 일어나면 한 응답 안에 서로 다른 자료 버전의
 * 값이 섞이기 때문이다 — 그렇게 섞인 화면은 어느 시점의 경제환경인지 아무도 말할 수 없다.
 *
 * <p>계층 규약은 ADR-0001(docs/adr/0001-be-layered-package-architecture.md)을 따른다.
 *
 * <ul>
 *   <li>{@code presentation/{controller,response}}
 *   <li>{@code application/{service,result,repository}}
 *   <li>{@code domain/{model,vo}}
 *   <li>{@code infrastructure/persistence}
 * </ul>
 *
 * <p><b>소비자</b>는 둘이다. 009 시장 흐름 화면은 이 조회를 거쳐 읽고 {@code macro_*}에 직접 닿지 않는다(T064). 010 경제환경 검색의
 * <b>등록표</b>는 {@code regime.application.port.MacroIndicatorPort}로 나가며, 그 구현체는 008이 완성되면 {@code
 * regime.infrastructure.macro}의 자리지킴을 대신한다(T070). 010의 <b>시계열</b>은 Backend를 거치지 않고 {@code data/}가
 * 직접 읽는다.
 */
package com.ssafy.thorp.macro;
