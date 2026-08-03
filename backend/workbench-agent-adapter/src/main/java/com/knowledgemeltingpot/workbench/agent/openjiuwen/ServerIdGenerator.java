package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import java.util.UUID;
import java.util.function.Supplier;

final class ServerIdGenerator {
    private ServerIdGenerator() {
    }

    static Supplier<String> prefixed(String prefix) {
        return () -> prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
