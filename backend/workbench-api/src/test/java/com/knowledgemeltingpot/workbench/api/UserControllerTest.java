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
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

class UserControllerTest {

    @Test
    void everyEndpointIsRestrictedToAdministratorsAtTheControllerBoundary() {
        PreAuthorize authorization = UserController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void passwordResetReturnsNoContentAndRevokesOnlyTheTargetSessions() {
        CurrentUser currentUser = mock(CurrentUser.class);
        UserAccountService service = mock(UserAccountService.class);
        AuditService auditService = mock(AuditService.class);
        SessionRevocationService revocationService = mock(SessionRevocationService.class);
        UUID administratorId = UUID.randomUUID();
        UserAccount target = account(UserRole.OPERATOR);
        Authentication authentication = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");
        when(currentUser.id(authentication)).thenReturn(administratorId);
        when(service.resetPassword(administratorId, target.id(), "replacement password value")).thenReturn(target);
        UserController controller = new UserController(service, currentUser, auditService, revocationService);

        var response = controller.resetPassword(target.id(),
                new UserController.ResetUserPasswordRequest("replacement password value"), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(auditService).record(eq(administratorId), eq("USER_PASSWORD_RESET"), eq("USER"), eq(target.id()),
                anyMap(), anyString());
        verify(revocationService).revokeAll(target.username());
    }

    @Test
    void passwordResetRequestDiagnosticsAreRedacted() {
        assertThat(new UserController.ResetUserPasswordRequest("replacement password value").toString())
                .contains("[REDACTED]")
                .doesNotContain("replacement password value");
    }

    @Test
    void updateRequestAcceptsPartialChangesForSessionAwareRevocation() {
        assertThat(new UserController.UpdateUserRequest(null, null, Set.of(UserRole.PUBLISHER)).hasChanges()).isTrue();
        assertThat(new UserController.UpdateUserRequest("New name", null, null).hasChanges()).isTrue();
        assertThat(new UserController.UpdateUserRequest(null, null, null).hasChanges()).isFalse();
    }

    private UserAccount account(UserRole role) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        return new UserAccount(UUID.randomUUID(), "operator", "Operator", "{bcrypt}hash", UserStatus.ACTIVE,
                Set.of(role), false, now, now);
    }
}
