package com.ssafy.thorp.assistant.application.command;

import java.util.Set;

/**
 * {@code POST /assistant/questions} 입력. 검증은 서비스가 플래그 판정 <b>뒤에</b> 한다 — 기능이 꺼진 환경에서 400이 먼저 나가면 경로가
 * 있다는 사실이 새기 때문이다(FR-012 "경로 자체를 노출하지 않음").
 *
 * @param kind 화면 종류 문자열. 낯선 값은 400
 * @param extraContextKeys {@code context}에 함께 온 모르는 키. 하나라도 있으면 400(식별자는 종류당 하나)
 */
public record AskQuestionCommand(
        String question,
        String kind,
        String publicToken,
        Set<String> extraContextKeys,
        String clientIp) {}
