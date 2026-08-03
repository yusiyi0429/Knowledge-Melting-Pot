package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.Job;

public record JobSubmission(Job job, boolean replayed) {
}
