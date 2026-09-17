/**
 * spec 013 배우기. 주제별 글과 진단 화면이 함께 쓰는 용어 설명을 보관하고 공개한다.
 *
 * <p><b>글·용어 설명의 소유자는 013이다.</b> 002·003·005 화면은 {@code termId}로 설명을 요청할 뿐 문장을 따로 두지 않는다(FR-008).
 *
 * <p>계층 규약은 ADR-0001(docs/adr/0001-be-layered-package-architecture.md)을 따른다.
 *
 * <ul>
 *   <li>{@code presentation/{controller,request,response,support}}
 *   <li>{@code application/{service,command,result,repository,port,exception}}
 *   <li>{@code domain/{model,vo,exception}}
 *   <li>{@code infrastructure/{persistence,sector,admin,rules}}
 * </ul>
 *
 * <p><b>의존 방향</b>: {@code learning.infrastructure → sector.application}이다. 조회 결과({@code
 * SectorListResult})가 담은 {@code sector.domain.model.Sector}까지 읽는다. 예시 섹터를 판정할 대응표는 001이 소유하고 013은
 * {@code SectorMapPort}로 읽는다. {@code sector}는 {@code learning}을 참조하지 않는다({@code ArchitectureTest}
 * 규칙 10).
 *
 * <p>일반 사용자 조회는 공개 중인 판({@code learning_article_revision})과 게시 용어 색인({@code
 * learning_published_term})만 읽는다. 초안 작업본({@code learning_article}·{@code learning_term})의 내용으로 공개
 * 응답을 만들지 않는다(SC-006).
 */
package com.ssafy.thorp.learning;
