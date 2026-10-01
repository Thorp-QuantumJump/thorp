package com.ssafy.thorp.account.infrastructure.session;

import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 대기 상태를 현재 요청의 서버 세션 속성으로 둔다(research D12).
 *
 * <p>Spring Session JDBC가 세션을 PostgreSQL에 보관하므로 서버를 다시 띄워도 남는다. 세션 식별자는 쿠키에만 있고 URL에 실리지
 * 않는다(FR-025).
 *
 * <p>요청은 {@link RequestContextHolder}로 얻는다. Spring Boot의 요청 문맥 필터가 보안 필터보다 앞에 있어서 콜백 처리기에서도 쓸 수 있다.
 */
@Component
public class HttpSessionPendingSocialLoginStore implements PendingSocialLoginStore {

    static final String ATTRIBUTE = "thorp.social.pending";

    @Override
    public Optional<PendingSocialLogin> find() {
        HttpSession session = currentRequest().getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        return session.getAttribute(ATTRIBUTE) instanceof PendingSocialLogin pending
                ? Optional.of(pending)
                : Optional.empty();
    }

    @Override
    public void save(PendingSocialLogin pending) {
        currentRequest().getSession(true).setAttribute(ATTRIBUTE, pending);
    }

    @Override
    public void clear() {
        HttpSession session = currentRequest().getSession(false);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
        }
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
    }
}
