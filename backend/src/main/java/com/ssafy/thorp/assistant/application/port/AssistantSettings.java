package com.ssafy.thorp.assistant.application.port;

import com.ssafy.thorp.assistant.application.result.PageKind;
import java.time.Duration;
import java.util.Set;

/**
 * 도우미 설정값(계약 §4). 한도는 남용 방지용이고 금액 상한은 없다(FR-013). 값은 {@code thorp.assistant.*}에서 오고 초안 수치는 실측 뒤
 * 조정한다(원칙 IX).
 *
 * @param enabledScopes 켜진 화면 종류. 비어 있으면 기능 전체가 꺼진다(FR-016)
 * @param totalBudget 요청 전체 시간 예산. 라운드별 예산은 이 안에서 남은 시간과 작은 쪽이다
 */
public record AssistantSettings(
        Set<PageKind> enabledScopes,
        int perIpPerMinute,
        int perTokenPerMinute,
        int perDay,
        int maxQuestionLength,
        Duration totalBudget,
        Duration firstCallBudget,
        Duration nextCallBudget,
        int maxRounds,
        int maxFacts,
        int maxFactsPerRound,
        int maxAnswerLength,
        int maxParts) {}
