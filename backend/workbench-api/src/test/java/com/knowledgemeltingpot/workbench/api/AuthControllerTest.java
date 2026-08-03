package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.api.security.SessionRevocationService;
import com.knowledgemeltingpot.workbench.application.service.AuditService;
import com.knowledgemeltingpot.workbench.application.service.UserAccountService;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class AuthControllerTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void meReturnsCompletePublicContractWithoutCredentialMaterial() {
        CurrentUser currentUser = mock(CurrentUser.class);
        UserAccount account = account(true);
        Authentication authentication = new TestingAuthenticationToken("operator", "n/a", "ROLE_OPERATOR");
        when(currentUser.account(authentication)).thenReturn(account);
        AuthController controller = controller(currentUser, mock(UserAccountService.class), mock(AuditService.class),
                mock(SessionRevocationService.class));

        UserResponse response = controller.me(authentication).getBody();

        assertThat(response).isNotNull();
        assertThat(response.username()).isEqualTo("operator");
        assertThat(response.displayName()).isEqualTo("Operator");
        assertThat(response.enabled()).isTrue();
        assertThat(response.mustChangePassword()).isTrue();
        assertThat(response.roles()).containsExactly("OPERATOR");
    }

    @Test
    void successfulPasswordChangeRevokesAllSessions() {
        CurrentUser currentUser = mock(CurrentUser.class);
        UserAccountService service = mock(UserAccountService.class);
        AuditService auditService = mock(AuditService.class);
        SessionRevocationService revocationService = mock(SessionRevocationService.class);
        UserAccount account = account(true);
        Authentication authentication = new TestingAuthenticationToken("operator", "n/a", "ROLE_OPERATOR");
        when(currentUser.account(authentication)).thenReturn(account);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        AuthController controller = controller(currentUser, service, auditService, revocationService);

        controller.changePassword(new AuthController.ChangePasswordRequest(
                "current password value", "replacement password value"), authentication, request);

        verify(service).changePassword(account.id(), "current password value", "replacement password value");
        verify(auditService).record(eq(account.id()), eq("USER_PASSWORD_CHANGED"), eq("USER"), eq(account.id()),
                anyMap(), anyString());
        verify(session).invalidate();
        verify(revocationService).revokeAll("operator");
    }

    @Test
    void credentialRequestDiagnosticStringsAreRedacted() {
        assertThat(new AuthController.LoginRequest("operator", "login-password").toString())
                .contains("[REDACTED]")
                .doesNotContain("login-password");
        assertThat(new AuthController.ChangePasswordRequest("current-password", "replacement-password").toString())
                .contains("[REDACTED]")
                .doesNotContain("current-password", "replacement-password");
    }

    private AuthController controller(CurrentUser currentUser, UserAccountService service, AuditService auditService,
            SessionRevocationService revocationService) {
        return new AuthController(mock(AuthenticationManager.class), currentUser, service, auditService,
                revocationService);
    }

    private UserAccount account(boolean mustChangePassword) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        return new UserAccount(UUID.randomUUID(), "operator", "Operator", "{bcrypt}hash", UserStatus.ACTIVE,
                Set.of(UserRole.OPERATOR), mustChangePassword, now, now);
    }
}
