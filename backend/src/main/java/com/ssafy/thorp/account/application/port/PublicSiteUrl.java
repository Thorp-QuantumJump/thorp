package com.ssafy.thorp.account.application.port;

/**
 * 브라우저가 보는 서비스 주소로 화면 경로를 만든다.
 *
 * <p>서버가 FE 화면으로 보내는 redirect에 쓴다. 상대 경로로 보내면 Tomcat이 요청의 {@code Host}로 절대 주소를 만드는데, 로컬 Vite proxy는
 * {@code Host}를 백엔드 주소({@code localhost:8080})로 바꿔 보내므로 사용자가 화면이 없는 백엔드 포트로 떨어진다(2026-10-01 code
 * review).
 */
public interface PublicSiteUrl {

    /** {@code path}는 {@code /}로 시작하는 FE 경로다. 서비스 주소가 설정되지 않았으면 경로를 그대로 돌려준다. */
    String of(String path);
}
