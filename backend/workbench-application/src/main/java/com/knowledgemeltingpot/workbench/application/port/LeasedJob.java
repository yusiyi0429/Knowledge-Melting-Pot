package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Job;
import java.time.Instant;

public record LeasedJob(Job job, String workerId, Instant leaseUntil, int attempt) {
}
