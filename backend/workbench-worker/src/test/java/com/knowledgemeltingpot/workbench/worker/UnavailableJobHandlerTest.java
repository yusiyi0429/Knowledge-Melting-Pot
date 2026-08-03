package com.knowledgemeltingpot.workbench.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgemeltingpot.workbench.domain.JobType;
import org.junit.jupiter.api.Test;

class UnavailableJobHandlerTest {
    @Test
    void fallbackSupportsEveryDurableJobTypeAtLowestPriority() {
        UnavailableJobHandler handler = new UnavailableJobHandler();

        assertThat(JobType.values()).allMatch(handler::supports);
        assertThat(handler.order()).isEqualTo(Integer.MAX_VALUE);
    }
}
