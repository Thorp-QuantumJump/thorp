package com.ssafy.thorp.learning.presentation.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 배우기 관리 경로의 권한 판정을 <b>요청 본문 검증보다 먼저</b> 돌린다(learning-admin-api §0, research R6).
 *
 * <p>컨트롤러 메서드 첫 줄의 판정만 두면 {@code @Valid @RequestBody} 검증이 먼저 돌아, 권한 없는 계정이 틀린 본문을 보냈을 때 404가 아니라
 * 400과 요청 필드 목록을 받는다. 인터셉터는 인자 해석 전에 돌므로 그 틈을 닫는다. 판정 자체는 {@link LearningAdminAccess} 한 곳이다.
 *
 * <p>{@link LearningAdminAccess}를 {@link ObjectProvider}로 받는 이유: {@code @WebMvcTest} 슬라이스는 이 설정을
 * 올리지만 관리 권한 구현(infrastructure)은 올리지 않는다. 요청 시점에 꺼내면 관리 경로를 부르지 않는 슬라이스가 깨지지 않는다.
 */
@Configuration
public class LearningAdminWebConfig implements WebMvcConfigurer {

    static final String ADMIN_PATH = "/admin/learning/**";

    private final ObjectProvider<LearningAdminAccess> adminAccess;

    public LearningAdminWebConfig(ObjectProvider<LearningAdminAccess> adminAccess) {
        this.adminAccess = adminAccess;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler) {
                                adminAccess.getObject().requireAdminUserId();
                                return true;
                            }
                        })
                .addPathPatterns(ADMIN_PATH);
    }
}
