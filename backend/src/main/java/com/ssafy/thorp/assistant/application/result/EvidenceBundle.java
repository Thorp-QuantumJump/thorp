package com.ssafy.thorp.assistant.application.result;

import com.ssafy.thorp.assistant.application.port.AssistantTool;
import java.time.LocalDate;
import java.util.List;

/**
 * 근거 빌더가 요청 시작 때 만드는 묶음: 초기 근거 팩, 그 결과에 묶인 도구, 부분 결과의 완료 끝 날짜.
 *
 * @param facts 초기 팩. id는 아직 없을 수 있다 — 루프가 {@code F1}부터 새로 매긴다
 * @param tools 이 요청의 결과에 묶여 만들어진 도구. 식별자 인자가 없다(FR-007)
 * @param partialUntil 부분 결과면 완료된 끝 날짜, 아니면 null. 답의 첫 문장 접두에 쓴다(FR-010)
 */
public record EvidenceBundle(
        List<Evidence> facts, List<AssistantTool> tools, LocalDate partialUntil) {

    public static EvidenceBundle empty() {
        return new EvidenceBundle(List.of(), List.of(), null);
    }
}
