package com.ssafy.thorp.account.presentation;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.account.application.command.SignupCommand;
import com.ssafy.thorp.account.application.result.SignupResult;
import com.ssafy.thorp.account.application.service.EmailVerificationService;
import com.ssafy.thorp.account.application.service.SignupService;
import com.ssafy.thorp.account.presentation.controller.EmailVerificationController;
import com.ssafy.thorp.account.presentation.controller.SignupController;
import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {SignupController.class, EmailVerificationController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AccountControllerContractTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SignupService signupService;
    @MockitoBean private EmailVerificationService emailVerificationService;

    @Test
    void emailVerificationOperationsUseInternalProxyPathsAnd204() throws Exception {
        mockMvc.perform(
                        post("/accounts/email-verifications")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\" MEMBER@Example.COM \"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        post("/accounts/email-verifications/confirm")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\" MEMBER@Example.COM \""
                                                + ",\"verificationCode\":\"123456\"}"))
                .andExpect(status().isNoContent());

        verify(emailVerificationService).createVerificationCode("member@example.com");
        verify(emailVerificationService).verifyCode("member@example.com", "123456");
    }

    @Test
    void signupUsesInternalProxyPathAnd201Envelope() throws Exception {
        when(signupService.signup(any()))
                .thenReturn(new SignupResult(1L, "member@example.com", "회원", "ACTIVE"));

        mockMvc.perform(
                        post("/accounts")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signupJson(" MEMBER@Example.COM ", "01012345678")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.email").value("member@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("회원"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.error").value(nullValue()));

        ArgumentCaptor<SignupCommand> command = ArgumentCaptor.forClass(SignupCommand.class);
        verify(signupService).signup(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().email())
                .isEqualTo("member@example.com");
    }

    @Test
    void signupWithoutPhoneNumberIsAccepted() throws Exception {
        when(signupService.signup(any()))
                .thenReturn(new SignupResult(1L, "member@example.com", "회원", "ACTIVE"));

        mockMvc.perform(
                        post("/accounts")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":"member@example.com","password":"password123","nickname":"회원"}
                                        """))
                .andExpect(status().isCreated());

        ArgumentCaptor<SignupCommand> command = ArgumentCaptor.forClass(SignupCommand.class);
        verify(signupService).signup(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().phoneNumber()).isNull();
    }

    @Test
    void legacyDoubleApiPathIsNotExposed() throws Exception {
        mockMvc.perform(
                        post("/api/accounts")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signupJson("01012345678")))
                .andExpect(status().isNotFound());
    }

    @Test
    void accountMutationsRequireCsrf() throws Exception {
        mockMvc.perform(
                        post("/accounts/email-verifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"member@example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-1234-5678", "0212345678", "010 1234 5678", "010123456"})
    void invalidPhoneNumbersUseStableInvalidInputCode(String phoneNumber) throws Exception {
        mockMvc.perform(
                        post("/accounts")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signupJson(phoneNumber)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void businessErrorsUseStableContractCodes() throws Exception {
        when(signupService.signup(any()))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));
        mockMvc.perform(
                        post("/accounts")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signupJson("01012345678")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"))
                .andExpect(jsonPath("$.error.message").value("이미 가입된 이메일입니다."));
    }

    private static String signupJson(String phoneNumber) {
        return signupJson("member@example.com", phoneNumber);
    }

    private static String signupJson(String email, String phoneNumber) {
        return """
                {
                  "email": "%s",
                  "password": "password123",
                  "nickname": "회원",
                  "phoneNumber": "%s"
                }
                """
                .formatted(email, phoneNumber);
    }
}
