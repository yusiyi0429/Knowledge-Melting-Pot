package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.AuditEvent;
import java.util.List;

public interface AuditRepository {
    void append(AuditEvent event);

    List<AuditEvent> findRecent(int limit, int offset);
}
