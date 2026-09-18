package com.ssafy.thorp.regime.domain.model;

import java.util.List;

/**
 * 사례 하나의 비슷한 점·다른 점 (spec FR-015, research R8).
 *
 * <p><b>배치가 템플릿으로 조립해 저장한 문장을 그대로 낸다.</b> 여기서 문장을 만들거나 고르지 않는다 — 조회가 문구를 손보기 시작하면 같은 결과가 화면마다 다른 말을
 * 하게 된다.
 */
public record CaseExplanation(List<String> similar, List<String> different) {}
