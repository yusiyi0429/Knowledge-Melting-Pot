package com.knowledgemeltingpot.workbench.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaterialUploadIntentTest {

    @Test
    void abortedIntentMayHaveCompletionTimeWithoutValidationJob() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        MaterialUploadIntent intent = new MaterialUploadIntent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "", now, now,
                "upload-1", "quarantine/object", 8L * 1024 * 1024, 1, now.plusSeconds(900),
                UploadState.ABORTED, 0);

        assertThat(intent.uploadState()).isEqualTo(UploadState.ABORTED);
        assertThat(intent.validationJobId()).isNull();
    }
}
