package com.knowledgemeltingpot.workbench.api.security;

import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AccountPolicyFilter extends OncePerRequestFilter {
    private static final Set<String> POLICY_EXEMPT_PATHS = Set.of(
            "/api/v1/auth/csrf",
            "/api/v1/auth/login",
            "/api/v1/auth/logout");
    private static final Set<String> PASSWORD_CHANGE_PATHS = Set.of(
            "/api/v1/auth/me",
            "/api/v1/auth/password");

    private final CurrentUser currentUser;
    private final SecurityProblemWriter problemWriter;

    public AccountPolicyFilter(CurrentUser currentUser, SecurityProblemWriter problemWriter) {
        this.currentUser = currentUser;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestUri = applicationPath(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isAuthenticatedApiRequest(authentication, requestUri) || POLICY_EXEMPT_PATHS.contains(requestUri)) {
            filterChain.doFilter(request, response);
            return;
        }

        UserAccount account;
        try {
            account = currentUser.account(authentication);
        } catch (NotFoundException exception) {
            problemWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Account unavailable",
                    "The authenticated account no longer exists", "account-unavailable");
            return;
        }

        if (account.status() != UserStatus.ACTIVE) {
            problemWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Account unavailable",
                    "The account is not active", "account-disabled");
            return;
        }
        if (!sessionRolesMatch(authentication, account)) {
            problemWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Session is stale",
                    "Sign in again after an account role change", "session-stale");
            return;
        }
        if (account.mustChangePassword() && !PASSWORD_CHANGE_PATHS.contains(requestUri)) {
            problemWriter.write(response, HttpServletResponse.SC_FORBIDDEN, "Password change required",
                    "Change the initial password before using the workbench", "password-change-required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAuthenticatedApiRequest(Authentication authentication, String requestUri) {
        return requestUri.startsWith("/api/v1/")
                && authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private String applicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath.isEmpty() ? requestUri : requestUri.substring(contextPath.length());
    }

    private boolean sessionRolesMatch(Authentication authentication, UserAccount account) {
        Set<String> sessionRoles = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> currentRoles = account.roles().stream()
                .map(role -> "ROLE_" + role.name())
                .collect(Collectors.toUnmodifiableSet());
        return sessionRoles.equals(currentRoles);
    }
}
