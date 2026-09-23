package com.ssafy.thorp.news.presentation.controller;

/**
 * 내보내기 엔드포인트의 오류 매핑.
 *
 * <p>두 엔드포인트가 같은 컨트롤러에 있어 {@link NewsDigestExceptionHandler}가 이미 덮는다. 이 타입은 시험이 처리기를 따로 끼울 자리로만 둔다
 * — standalone MockMvc는 컨트롤러 하나에 처리기 하나를 붙이는 편이 무엇을 시험하는지 분명하다.
 */
public class NewsArticleExportExceptionHandler extends NewsDigestExceptionHandler {}
