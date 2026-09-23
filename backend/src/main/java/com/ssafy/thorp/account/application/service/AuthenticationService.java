package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.EmailNormalizer;
import com.ssafy.thorp.account.application.command.LoginCommand;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일·비밀번호 로그인과 현재 사용자 복원.
 *
 * <p><b>인증 상태의 주인은 Spring Security다.</b> 별도의 토큰이나 사용자 캐시를 만들지 않고, 인증 결과를 {@link
 * SecurityContextRepository}에 넣어 Spring Session이 PostgreSQL에 보관하게 한다. {@code SecurityConfig}가
 * {@code requireExplicitSave(true)}이고 이 로그인은 Spring Security의 인증 필터를 거치지 않으므로, 세션 식별자 교체와 저장을 여기서
 * 직접 부른다 — 부르지 않으면 로그인 응답은 200인데 다음 요청이 비로그인이 된다.
 *
 * <p><b>세션에 담는 주체는 사용자 식별자다.</b> 이메일이 아니다. Spring Session의 {@code spring_session.principal_name}은
 * VARCHAR(100)인데 {@code users.email}은 255라, 긴 이메일 계정이 로그인하는 순간 세션 저장이 깨진다. 식별자는 길이가 안전하고 이메일이 바뀌어도
 * 그대로다.
 *
 * <p><b>거절 사유는 밖으로 내보내지 않는다.</b> 계정 없음·비밀번호 불일치·비활성 계정은 모두 {@link ErrorCode#INVALID_CREDENTIALS}
 * 하나로 끝난다. 구분해서 알려주면 이메일만 넣어 가입 여부를 확인할 수 있다. 원인은 서버 로그에만 남긴다.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;
    private final Clock clock;

    /** 세션 무효화와 {@code SecurityContext} 비우기. 세션이 없으면 아무 일도 하지 않으므로 로그아웃의 멱등성이 여기서 나온다. */
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    /**
     * 계정을 찾지 못했을 때 대조할 버리는 해시.
     *
     * <p>계정이 없다고 바로 돌아가면 응답이 눈에 띄게 빨라서, 비밀번호를 모르는 쪽도 가입된 이메일인지 알 수 있다. 같은 비용의 해시 대조를 한 번 거쳐 그 차이를
     * 없앤다. 기동할 때마다 임의 값으로 만들어 어떤 실제 비밀번호와도 무관하다.
     */
    private final String absentAccountPasswordHash;

    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CsrfTokenRepository csrfTokenRepository,
            Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
        this.clock = clock;
        this.absentAccountPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** 자격 증명을 확인하고 현재 요청의 세션을 인증 상태로 바꾼다. */
    @Transactional
    public AuthenticatedUserResult login(
            LoginCommand command, HttpServletRequest request, HttpServletResponse response) {
        String email = EmailNormalizer.normalize(command.email());
        User user = verifyCredentials(email, command.password());

        userRepository.updateLastLoginAt(user.getId(), clock.instant());

        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        String.valueOf(user.getId()), null, List.of());

        // 순서가 중요하다. 먼저 식별자를 교체해야 인증 상태가 새 세션에 들어간다(FR-018).
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        rotateCsrfToken(request, response);

        return projectionOf(user);
    }

    /**
     * 현재 요청의 세션을 끝낸다.
     *
     * <p><b>이미 비로그인이어도 같은 결과로 끝난다.</b> 로그아웃은 "이 브라우저에 인증 세션이 없게 만든다"는 뜻이고, 처음부터 없었다면 그 상태가 이미 참이다.
     * 여기서 401이나 404를 돌려주면 FE는 세션이 정말 끝났는지 확인하지 못한 채 '로그아웃 미확인'에 머문다(FR-009).
     *
     * <p><b>끝내는 것은 이 세션 하나다.</b> 같은 계정으로 다른 브라우저에서 연 세션은 건드리지 않는다 — 한 기기에서 나간 것이 다른 기기에서 쫓겨날 이유가 되지
     * 않는다. 전체 세션 종료가 필요해지면 그때 별도 동작으로 만든다.
     *
     * <p>세션 무효화는 Spring Session이 {@code spring_session} 행을 지우게 하고, 같은 응답에 만료된 {@code THORP_SESSION}
     * 쿠키를 실어 보낸다.
     */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 세션 식별자는 남기지 않는다. 로그에 남으면 그 줄을 본 사람이 세션을 그대로 흉내 낼 수 있다.
        userId(authentication).ifPresent(id -> log.info("Logout: userId={}", id));

        logoutHandler.logout(request, response, authentication);

        rotateCsrfToken(request, response);
    }

    /**
     * CSRF 토큰을 새 값으로 바꾼다.
     *
     * <p>토큰은 세션이 아니라 쿠키에 있다. 그래서 로그인 전에 브라우저에 자리 잡은 값이 로그인한 뒤에도, 로그아웃한 뒤에도 같은 값으로 남는다. 인증 경계가 바뀌는 두
     * 지점에서 값을 갈아 브라우저가 들고 있는 토큰의 수명을 한 로그인 안으로 끊는다(research D3).
     *
     * <p><b>서버가 옛 값을 거절하지는 못한다.</b> 이중 제출 방식이라 쿠키와 헤더가 서로 맞기만 하면 통과하고, 저장소에 대조할 원본이 없다. 갈아 주는 효과는
     * 브라우저에 남아 있던 값이 덮여 사라지는 데 있다.
     *
     * <p>새 값은 이 응답의 쿠키로 나가므로 SPA는 {@code /auth/csrf}를 다시 부르지 않고 다음 요청을 보낼 수 있다.
     */
    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(
                csrfTokenRepository.generateToken(request), request, response);
    }

    /**
     * 세션에 남아 있는 인증 주체로 현재 사용자를 다시 읽는다.
     *
     * <p>세션에 담아 둔 값을 그대로 돌려주지 않는다. 그러면 정지된 계정이 세션이 만료될 때까지 살아 있는 사용자로 보이고, 바뀐 닉네임도 반영되지 않는다.
     */
    @Transactional(readOnly = true)
    public AuthenticatedUserResult currentUser(Authentication authentication) {
        return userId(authentication)
                .flatMap(userRepository::findById)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(AuthenticationService::projectionOf)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    private User verifyCredentials(String email, String password) {
        Optional<User> found = userRepository.findByEmail(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(password, absentAccountPasswordHash);
            log.warn("Login rejected: reason=ACCOUNT_NOT_FOUND");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = found.get();

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("Login rejected: reason=PASSWORD_MISMATCH, userId={}", user.getId());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn(
                    "Login rejected: reason=ACCOUNT_NOT_ACTIVE, userId={}, status={}",
                    user.getId(),
                    user.getStatus());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        return user;
    }

    /** 인증 주체는 로그인이 넣어 둔 사용자 식별자다. 그 형태가 아니면 우리가 만든 세션이 아니므로 로그인 상태로 보지 않는다. */
    private static Optional<Long> userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(authentication.getName()));
        } catch (NumberFormatException e) {
            log.warn("Unusable authentication principal in session");
            return Optional.empty();
        }
    }

    private static AuthenticatedUserResult projectionOf(User user) {
        return new AuthenticatedUserResult(
                user.getId(), user.getEmail(), user.getNickname(), user.getStatus().name());
    }
}
