package com.ssafy.thorp.backtest.presentation.support;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 운영자 전용 경로({@code /internal/**})의 문. spec 004 T012 — 측정 집계는 사용자 화면에 노출하지 않는다.
 *
 * <p><b>이 저장소에는 아직 로그인·역할이 없다</b>({@link CurrentRequester}와 같은 사정). 그때까지 운영자는 {@code X-Ops-Token}
 * 헤더가 설정 {@code thorp.ops.token}과 같은 사람이다. <b>토큰이 설정되지 않은 환경에서는 경로가 없는 것처럼 404 를 돌려준다</b> — 운영에 값을
 * 넣지 않으면 열리지 않고, 틀린 토큰도 404 다(경로의 존재를 알려 주지 않는다). spec 014(사용자 인증)가 들어오면 이 클래스 하나만 역할을 읽도록 바꾼다.
 */
@Component
public class OperatorGate {

    public static final String HEADER = "X-Ops-Token";

    private final byte[] token;

    public OperatorGate(@Value("${thorp.ops.token:}") String token) {
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    /** 운영자가 아니면 {@code 404 RESOURCE_NOT_FOUND}. 403 을 쓰지 않는 이유는 경로의 존재를 숨기기 위해서다. */
    public void requireOperator(HttpServletRequest request) {
        String presented = request.getHeader(HEADER);
        if (token.length == 0 || presented == null) {
            throw notFound();
        }
        // 길이가 달라도 상수 시간 비교 — 토큰을 글자 단위로 맞춰 나가는 것을 막는다.
        if (!MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw notFound();
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.");
    }
}
