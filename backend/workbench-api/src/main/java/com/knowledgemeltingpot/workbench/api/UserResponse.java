package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String displayName,
        List<String> roles,
        boolean enabled,
        boolean mustChangePassword) {

    public static UserResponse from(UserAccount account) {
        return new UserResponse(account.id(), account.username(), account.displayName(),
                account.roles().stream().map(Enum::name).sorted().toList(),
                account.status() == UserStatus.ACTIVE, account.mustChangePassword());
    }
}
