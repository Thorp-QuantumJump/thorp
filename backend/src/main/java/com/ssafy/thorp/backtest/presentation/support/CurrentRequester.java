package com.ssafy.thorp.backtest.presentation.support;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 요청자 식별. spec 004 R-12는 모든 백테스트 엔드포인트에 인증을 요구하고 {@code backtest_request.requester_id}는 NOT NULL이다.
 *
 * <p><b>이 저장소에는 아직 로그인·토큰 검증이 없다.</b> 회원 가입(account)만 있고 Spring Security도 붙어 있지 않아서, 접수 경로가 요청자를 알
 * 방법이 없다. spec 014(사용자 인증)가 들어오면 이 클래스 하나만 인증 주체를 읽도록 바꾼다.
 *
 * <p>그때까지는 <b>기본적으로 막는다</b>. 개발 편의로 {@code X-User-Id} 헤더를 믿는 경로를 두되 {@code
 * thorp.auth.trust-user-id-header} 를 켠 환경에서만 동작하고, 기본값은 꺼짐이다. 이 값을 운영 설정에 넣으면 누구나 남의 요청을 조회·취소할 수
 * 있다 — <b>헤더를 신뢰하는 것은 인증이 아니다.</b>
 */
@Component
public class CurrentRequester {

    /** 개발용 헤더. 인증이 아니라 "로그인이 아직 없다"는 사실을 드러내는 임시 통로다. */
    public static final String DEV_HEADER = "X-User-Id";

    private final boolean trustHeader;

    public CurrentRequester(
            @Value("${thorp.auth.trust-user-id-header:false}") boolean trustHeader) {
        this.trustHeader = trustHeader;
    }

    /**
     * 요청자를 찾되 없으면 비어 있다. 소유자 필터가 아직 없는 경로(spec 007 자동 탐색, research R4)가 쓴다.
     *
     * <p>{@link #requireRequesterId}와 갈라 두는 이유: 남의 결과를 막아야 하는 조회는 요청자가 없으면 진행하면 안 되고, 소유자를 기록만 하는
     * 접수는 없다고 막을 근거가 없다. 헤더 값이 숫자가 아니면 양쪽 모두 거절한다 — 그것은 "없음"이 아니라 잘못된 입력이다.
     */
    public Optional<Long> findRequesterId(HttpServletRequest request) {
        if (!trustHeader) {
            return Optional.empty();
        }
        String raw = request.getHeader(DEV_HEADER);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parse(raw));
    }

    public long requireRequesterId(HttpServletRequest request) {
        if (!trustHeader) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "요청자 인증이 아직 연결되지 않았습니다(spec 014). 개발 환경에서는"
                            + " thorp.auth.trust-user-id-header 를 켜고 "
                            + DEV_HEADER
                            + " 헤더를 보내 주세요.");
        }
        String raw = request.getHeader(DEV_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, DEV_HEADER + " 헤더가 필요합니다.");
        }
        return parse(raw);
    }

    private static long parse(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException exc) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, DEV_HEADER + " 헤더는 숫자여야 합니다: " + raw);
        }
    }
}
