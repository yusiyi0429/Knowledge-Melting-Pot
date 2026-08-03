package com.knowledgemeltingpot.workbench.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AccountPolicyFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requiredPasswordChangeBlocksWorkbenchEndpoints() throws Exception {
        FilterFixture fixture = fixture(account(UserStatus.ACTIVE, Set.of(UserRole.ADMIN), true),
                List.of("ROLE_ADMIN"));

        fixture.filter().doFilter(fixture.request("POST", "/api/v1/scenes"), fixture.response(), fixture.chain());

        assertThat(fixture.response().getStatus()).isEqualTo(403);
        assertThat(fixture.response().getContentAsString()).contains("password-change-required");
        verifyNoInteractions(fixture.chain());
    }

    @Test
    void passwordEndpointRemainsAvailable() throws Exception {
        FilterFixture fixture = fixture(account(UserStatus.ACTIVE, Set.of(UserRole.ADMIN), true),
                List.of("ROLE_ADMIN"));
        MockHttpServletRequest request = fixture.request("POST", "/api/v1/auth/password");

        fixture.filter().doFilter(request, fixture.response(), fixture.chain());

        verify(fixture.chain()).doFilter(request, fixture.response());
    }

    @Test
    void disabledAccountIsRejectedEvenIfOldSessionStillExists() throws Exception {
        FilterFixture fixture = fixture(account(UserStatus.DISABLED, Set.of(UserRole.ADMIN), false),
                List.of("ROLE_ADMIN"));

        fixture.filter().doFilter(fixture.request("GET", "/api/v1/users"), fixture.response(), fixture.chain());

        assertThat(fixture.response().getStatus()).isEqualTo(401);
        assertThat(fixture.response().getContentAsString()).contains("account-disabled");
        verifyNoInteractions(fixture.chain());
    }

    @Test
    void staleSessionRolesAreRejectedBeforeAuthorization() throws Exception {
        FilterFixture fixture = fixture(account(UserStatus.ACTIVE, Set.of(UserRole.OPERATOR), false),
                List.of("ROLE_ADMIN"));

        fixture.filter().doFilter(fixture.request("GET", "/api/v1/users"), fixture.response(), fixture.chain());

        assertThat(fixture.response().getStatus()).isEqualTo(401);
        assertThat(fixture.response().getContentAsString()).contains("session-stale");
        verifyNoInteractions(fixture.chain());
    }

    private FilterFixture fixture(UserAccount account, List<String> roles) {
        CurrentUser currentUser = mock(CurrentUser.class);
        var authorities = roles.stream().map(SimpleGrantedAuthority::new).toList();
        var authentication = new UsernamePasswordAuthenticationToken("admin", "n/a", authorities);
        when(currentUser.account(authentication)).thenReturn(account);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return new FilterFixture(new AccountPolicyFilter(currentUser, new SecurityProblemWriter(new ObjectMapper())),
                new MockHttpServletResponse(), mock(FilterChain.class));
    }

    private UserAccount account(UserStatus status, Set<UserRole> roles, boolean mustChangePassword) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        return new UserAccount(UUID.randomUUID(), "admin", "Administrator", "{bcrypt}hash", status,
                roles, mustChangePassword, now, now);
    }

    private record FilterFixture(AccountPolicyFilter filter, MockHttpServletResponse response, FilterChain chain) {
        MockHttpServletRequest request(String method, String path) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            request.setRequestURI(path);
            return request;
        }
    }
}
