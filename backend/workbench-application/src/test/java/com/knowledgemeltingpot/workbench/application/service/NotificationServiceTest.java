package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.JobRepository;
import com.knowledgemeltingpot.workbench.application.port.NotificationRepository;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock NotificationRepository notifications;
    @Mock JobRepository jobs;

    @Test
    void terminalNotificationUsesStableErrorCodeAndNeverRawProviderMessage() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        UUID jobId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Job job = new Job(jobId, JobType.SCENE_EXPLORE, "EXPLORATION", UUID.randomUUID(), JobStatus.FAILED,
                42, 1, "{}", "", "MODEL_JSON_INVALID", "secret provider detail", actorId, now, now);
        when(jobs.find(jobId)).thenReturn(Optional.of(job));
        NotificationService service = new NotificationService(notifications, jobs,
                Clock.fixed(now, ZoneOffset.UTC));

        service.notifyTerminalJob(jobId);

        verify(notifications).insert(argThat(value -> {
            assertThat(value.userId()).isEqualTo(actorId);
            assertThat(value.message()).contains("MODEL_JSON_INVALID").doesNotContain("secret provider detail");
            return true;
        }));
    }
}
