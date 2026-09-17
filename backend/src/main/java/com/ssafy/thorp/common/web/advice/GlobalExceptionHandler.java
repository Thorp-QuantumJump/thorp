package com.ssafy.thorp.common.web.advice;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.common.web.response.ErrorResponse;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 컨트롤러 밖으로 나온 예외를 {@link ApiResponse} 실패 포맷으로 변환한다.
 *
 * <p>{@link ResponseEntityExceptionHandler}를 상속해 Spring MVC 표준 예외(잘못된 JSON, 경로 변수 타입 불일치, 허용되지 않는
 * 메서드 등)가 catch-all 핸들러로 떨어져 500이 되는 것을 막는다. 상태 코드는 Spring이 정한 값을 그대로 쓰고 본문만 우리 포맷으로 감싼다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("Business exception: code={}, message={}", errorCode, e.getMessage());
        return ResponseEntity.status(errorCode.getStatus())
                .body(
                        ApiResponse.fail(
                                ErrorResponse.of(errorCode, e.getMessage(), e.getDetails())));
    }

    /**
     * 도메인 불변식 위반. 값 객체·도메인 모델이 잘못된 인자를 거부할 때 던지며, 대부분 클라이언트 입력이 원인이므로 400으로 응답한다.
     *
     * <p>Bean Validation으로 잡히지 않는 필드 간 제약(예: startDate > endDate)이 여기로 온다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Domain constraint violated: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.fail(ErrorResponse.of(ErrorCode.INVALID_INPUT, e.getMessage())));
    }

    /** 컨트롤러 또는 서비스 경계에서 발견한 미인증 요청을 공통 JSON 포맷으로 변환한다. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException e) {
        log.warn("Authentication required: type={}", e.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.AUTHENTICATION_REQUIRED.getStatus())
                .body(ApiResponse.fail(ErrorResponse.of(ErrorCode.AUTHENTICATION_REQUIRED)));
    }

    /** MVC 계층까지 전달된 CSRF 검증 실패를 필터 계층과 같은 응답으로 변환한다. */
    @ExceptionHandler(CsrfException.class)
    public ResponseEntity<ApiResponse<Void>> handleCsrf(CsrfException e) {
        log.warn("CSRF validation failed: type={}", e.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.CSRF_TOKEN_INVALID.getStatus())
                .body(ApiResponse.fail(ErrorResponse.of(ErrorCode.CSRF_TOKEN_INVALID)));
    }

    /** 예상하지 못한 예외. 내부 정보가 새지 않도록 메시지는 고정 문구만 내려준다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorResponse.of(ErrorCode.INTERNAL_ERROR)));
    }

    /**
     * 요청 DTO 검증 실패. {@code message}에는 첫 번째 필드 오류를, {@code details.fields}에는 필드 오류 전부({@code
     * field}·{@code message})를 싣는다.
     *
     * <p>{@code details.fields}는 한 요청에 필드가 많아 첫 오류만으로는 어디를 고칠지 알 수 없는 화면(배우기 관리 초안 저장,
     * learning-admin-api §5)이 위치를 그 자리에 보이려고 쓴다. 다른 경로의 400에는 키가 하나 늘 뿐이다.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        String message =
                fieldErrors.stream()
                        .findFirst()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .orElse(ErrorCode.INVALID_INPUT.getMessage());
        Map<String, Object> details =
                fieldErrors.isEmpty()
                        ? null
                        : Map.of(
                                "fields",
                                fieldErrors.stream()
                                        .map(
                                                error ->
                                                        Map.of(
                                                                "field",
                                                                error.getField(),
                                                                "message",
                                                                String.valueOf(
                                                                        error.getDefaultMessage())))
                                        .toList());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(
                        ApiResponse.fail(
                                ErrorResponse.of(ErrorCode.INVALID_INPUT, message, details)));
    }

    /**
     * Spring MVC 표준 예외의 공통 출구. 상태 코드는 Spring이 정한 값을 유지하고 본문만 {@link ApiResponse}로 감싼다.
     *
     * <p>{@code error.code}는 상태에 따른 큰 분류만 담는다. 정확한 원인은 HTTP 상태 코드가 나타낸다. 예외 메시지에는 파서 내부 정보가 섞일 수 있어
     * 노출하지 않는다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ErrorCode errorCode = toErrorCode(statusCode);
        if (statusCode.is5xxServerError()) {
            log.error("Spring MVC exception: status={}", statusCode, ex);
        } else {
            log.warn(
                    "Spring MVC exception: status={}, type={}",
                    statusCode,
                    ex.getClass().getSimpleName());
        }
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(ApiResponse.fail(ErrorResponse.of(errorCode)));
    }

    private static ErrorCode toErrorCode(HttpStatusCode statusCode) {
        if (statusCode.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.RESOURCE_NOT_FOUND;
        }
        return statusCode.is4xxClientError() ? ErrorCode.INVALID_INPUT : ErrorCode.INTERNAL_ERROR;
    }
}
