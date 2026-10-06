package com.ssafy.thorp.assistant.application.result;

import com.ssafy.thorp.assistant.application.port.AssistantTool;
import java.time.LocalDate;
import java.util.List;

/**
 * 근거 빌더가 요청 시작 때 만드는 묶음: 초기 근거 팩, 그 요청에 묶인 도구, 답 앞에 붙일 접두의 판단 재료.
 *
 * @param facts 초기 팩. id는 아직 없을 수 있다 — 루프가 {@code F1}부터 새로 매긴다
 * @param tools 이 요청에 묶여 만들어진 도구. 식별자 인자가 없다(FR-007)
 * @param partialUntil 부분 결과면 완료된 끝 날짜, 아니면 null. 답의 첫 문장 접두에 쓴다(015 FR-010)
 * @param registryProvisional 003 등록표가 임시값인가. 등록표 근거를 쓴 답에 접두를 붙인다(017 FR-009b). 결과 화면은 false
 * @param sectorSample 001 섹터 대응표가 예시 자료인가. 섹터 목록 근거를 쓴 답에 접두를 붙인다(017 FR-009b). 결과 화면은 false
 */
public record EvidenceBundle(
        List<Evidence> facts,
        List<AssistantTool> tools,
        LocalDate partialUntil,
        boolean registryProvisional,
        boolean sectorSample) {

    public EvidenceBundle(List<Evidence> facts, List<AssistantTool> tools, LocalDate partialUntil) {
        this(facts, tools, partialUntil, false, false);
    }

    public static EvidenceBundle empty() {
        return new EvidenceBundle(List.of(), List.of(), null);
    }
}
