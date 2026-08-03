package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.application.port.AuditRepository;
import com.knowledgemeltingpot.workbench.domain.AuditEvent;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
    private final AuditRepository auditRepository;

    public AuditController(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditEvent> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        return auditRepository.findRecent(safeSize, Math.multiplyExact(safePage, safeSize));
    }
}
