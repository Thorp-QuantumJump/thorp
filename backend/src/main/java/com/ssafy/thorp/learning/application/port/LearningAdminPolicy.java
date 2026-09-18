package com.ssafy.thorp.learning.application.port;

/**
 * 로그인한 사용자가 배우기 관리 계정인지 답한다(research R5). 관리 권한 판정은 이 포트 한 곳이다. 컨트롤러마다 판정을 다시 쓰지 않는다.
 *
 * <p>014가 역할을 제공하면 구현 한 클래스만 역할을 읽도록 바꾼다.
 */
public interface LearningAdminPolicy {

    /**
     * @param userId 014 로그인 세션의 사용자 식별자({@code users.id})
     */
    boolean isAdmin(long userId);
}
