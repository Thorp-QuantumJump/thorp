package com.ssafy.thorp.myrecord.application.port;

/**
 * 내 기록 상한 값(spec 006 FR-018). 설정에서 읽어 온 불변 사본이다.
 *
 * <p>{@code MyRecordProperties}를 정책이 직접 주입받지 않는 이유: 그 클래스는 {@code infrastructure.config}에 있고
 * application은 infrastructure를 참조할 수 없다({@code ArchitectureTest} 계층 규칙). {@code ExecutionLimits}와
 * 같은 자리다.
 *
 * @param searchBundle 한 사용자가 저장해 둘 수 있는 탐색 묶음 기록 수. <b>PD-13 확정 전 임시값</b>이며 산출물은 값이 아니라 장치다
 */
public record MyRecordLimits(int searchBundle) {

    public MyRecordLimits {
        if (searchBundle < 1) {
            throw new IllegalArgumentException("searchBundle limit must be positive");
        }
    }
}
