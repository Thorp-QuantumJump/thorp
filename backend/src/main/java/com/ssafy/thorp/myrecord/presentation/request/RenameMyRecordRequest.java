package com.ssafy.thorp.myrecord.presentation.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * {@code PATCH /my-records/{recordId}} 요청(contracts).
 *
 * <p><b>이 요청으로 바꿀 수 있는 것은 이름뿐이다.</b> {@code name} 외의 키는 무시하지 않고 거절한다 — 조용히 버리면 사용자는 바꾼 줄 알고 화면은 안
 * 바뀐 값을 계속 보인다.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)}로는 이것을 강제할 수 없다. 그 값은 "전역 {@code
 * FAIL_ON_UNKNOWN_PROPERTIES} 설정을 따른다"는 뜻이고 Spring Boot 는 그 설정을 꺼 둔다. 전역을 켜면 다른 도메인의 요청 DTO 가 함께
 * 바뀌므로, 모르는 키를 여기서 받아 두고 이 요청에서만 거절한다.
 *
 * <p>길이·공백 판정은 {@code RecordName}이 한다. 여기서 한 번 더 적으면 규칙이 두 곳이 된다.
 */
public record RenameMyRecordRequest(
        @NotNull(message = "name 은 필수입니다") String name, @JsonAnySetter Map<String, Object> extra) {

    public RenameMyRecordRequest {
        if (extra != null && !extra.isEmpty()) {
            throw new IllegalArgumentException("이 요청으로 바꿀 수 있는 것은 name 뿐입니다: " + extra.keySet());
        }
    }
}
