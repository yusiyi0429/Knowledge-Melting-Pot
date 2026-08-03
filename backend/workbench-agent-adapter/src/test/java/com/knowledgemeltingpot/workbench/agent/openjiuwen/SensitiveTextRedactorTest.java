package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveTextRedactorTest {
    @Test
    void masksCommonCredentialShapes() {
        String redacted = SensitiveTextRedactor.redact(
                "Authorization: Bearer abc.def.ghi api_key=sk-1234567890 password=hunter2");

        assertFalse(redacted.contains("abc.def.ghi"));
        assertFalse(redacted.contains("sk-1234567890"));
        assertFalse(redacted.contains("hunter2"));
        assertTrue(redacted.contains("[REDACTED]"));
    }
}
