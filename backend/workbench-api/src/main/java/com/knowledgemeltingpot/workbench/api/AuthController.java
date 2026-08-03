package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.api.security.SessionRevocationService;
import com.knowledgemeltingpot.workbench.application.service.AuditService;
import com.knowledgemeltingpot.workbench.application.service.UserAccountService;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final CurrentUser currentUser;
    private final UserAccountService userAccountService;
    private final AuditService auditService;
    private final SessionRevocationService sessionRevocationService;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, CurrentUser currentUser,
            UserAccountService userAccountService, AuditService auditService,
            SessionRevocationService sessionRevocationService) {
        this.authenticationManager = authenticationManager;
        this.currentUser = currentUser;
        this.userAccountService = userAccountService;
        this.auditService = auditService;
        this.sessionRevocationService = sessionRevocationService;
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        request.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        return ResponseEntity.ok(UserResponse.from(currentUser.account(authentication)));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body,
            Authentication authentication, HttpServletRequest request) {
        UserAccount account = currentUser.account(authentication);
        userAccountService.changePassword(account.id(), body.currentPassword(), body.newPassword());
        auditService.record(account.id(), "USER_PASSWORD_CHANGED", "USER", account.id(),
                Map.of("initialPasswordChange", account.mustChangePassword()), RequestIdFilter.currentTraceId());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        sessionRevocationService.revokeAll(account.username());
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String username,
                               @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=[REDACTED]]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[currentPassword=[REDACTED], newPassword=[REDACTED]]";
        }
    }

    public record CsrfResponse(String headerName, String parameterName, String token) {
    }
}
