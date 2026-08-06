package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.application.service.OperationReadinessService;
import com.knowledgemeltingpot.workbench.application.service.OperationReadinessService.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operation-readiness")
@PreAuthorize("hasAnyRole('OPERATOR','PUBLISHER','ADMIN')")
public class OperationReadinessController {
    private final OperationReadinessService service;

    public OperationReadinessController(OperationReadinessService service) {
        this.service = service;
    }

    @GetMapping
    public OperationReadinessService.Report get(@RequestParam Operation operation,
            @RequestParam(required = false) UUID explorationSessionId,
            @RequestParam(required = false) UUID sceneId,
            @RequestParam(required = false) UUID subSceneId,
            @RequestParam(required = false) UUID roundId) {
        return service.check(operation, explorationSessionId, sceneId, subSceneId, roundId);
    }
}
