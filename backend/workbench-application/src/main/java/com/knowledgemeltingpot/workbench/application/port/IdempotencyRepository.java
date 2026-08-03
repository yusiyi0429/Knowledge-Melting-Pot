package com.knowledgemeltingpot.workbench.application.port;

import java.util.Optional;

public interface IdempotencyRepository {
    Optional<IdempotencyRecord> find(String scope, String key);

    boolean tryReserve(IdempotencyRecord record);
}
