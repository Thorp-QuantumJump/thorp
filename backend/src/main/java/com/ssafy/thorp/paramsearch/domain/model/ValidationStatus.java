package com.ssafy.thorp.paramsearch.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * 묶음의 검증 상태 — 라벨과 그 라벨이 붙은 이유 한 문장(FR-014·FR-015, research R5).
 *
 * <p><b>왜 한곳에 모으는가</b>: 같은 사실을 실행 전(미리보기)·실행 중(상태 조회)·결과(후보 목록) 세 곳이 말한다. 문구가 흩어져 있으면 한 곳만 고쳐져 "실행
 * 전에는 미검증이라더니 결과에서는 아무 말이 없는" 상태가 된다. 사용자가 미검증임을 <b>결과를 본 뒤에야</b> 알게 되면 그것은 이미 오해가 생긴 뒤다.
 *
 * <p><b>평가 분할이 라벨을 정한다.</b> 분할이 등록되지 않으면 어느 구간이 탐색용이고 어느 구간이 잠금인지 말할 수 없으므로 검증 여부 자체를 말할 수 없다 — 그때가
 * {@link ValidationLabel#UNVALIDATED}다. PD-12가 확정돼 분할이 등록되면 그때부터 후보마다 탐색 구간 성과와 잠금 구간 성과가 갈린다.
 *
 * <p><b>미검증을 검증 통과로 바꿔 말하지 않는다</b>(원칙 VII). "아직 나누지 않았다"와 "나눠서 통과했다"는 정반대이고, 전자를 후자로 읽히게 두면 사용자는 탐색
 * 성과를 검증된 성과로 믿는다.
 */
public record ValidationStatus(ValidationLabel label, String note) {

    private static final String NO_SPLIT_NOTE = "평가 분할이 등록되지 않아 결과는 모두 미검증으로 표시됩니다";

    public ValidationStatus {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(note, "note must not be null");
    }

    /**
     * 묶음의 평가 분할 스냅샷으로 검증 상태를 정한다.
     *
     * @param evaluationSplit 접수 시점에 고정한 평가 분할. 미등록이면 null이다(research R5)
     */
    public static ValidationStatus of(Map<String, Object> evaluationSplit) {
        if (evaluationSplit == null || evaluationSplit.isEmpty()) {
            return new ValidationStatus(ValidationLabel.UNVALIDATED, NO_SPLIT_NOTE);
        }
        // PD-12 확정 전까지 도달하지 않는 가지다. 분할이 생기면 구간별 성과로 라벨이 갈리며,
        // 그 판정은 후보마다 다르므로 여기가 아니라 후보 쪽으로 옮겨 간다.
        throw new IllegalStateException(
                "evaluation split is registered but labelling is not implemented yet (PD-12)");
    }

    /** 분할이 없는 현재의 상태. 미리보기처럼 묶음이 아직 없는 자리가 쓴다. */
    public static ValidationStatus unvalidated() {
        return new ValidationStatus(ValidationLabel.UNVALIDATED, NO_SPLIT_NOTE);
    }
}
