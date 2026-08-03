package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import java.util.UUID;

public interface CredentialCipher {
    CredentialEnvelope seal(UUID modelConnectionId, char[] credential);

    char[] unseal(UUID modelConnectionId, CredentialEnvelope envelope);
}
