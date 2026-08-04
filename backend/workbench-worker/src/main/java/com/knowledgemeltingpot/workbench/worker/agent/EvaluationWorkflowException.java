package com.knowledgemeltingpot.workbench.worker.agent;

/** Stable, non-provider diagnostic propagated to the durable evaluation run. */
public final class EvaluationWorkflowException extends RuntimeException {
    private final String code;

    public EvaluationWorkflowException(String code) {
        super(code);
        this.code = code != null && code.matches("[A-Z0-9_:-]{1,100}")
                ? code : "SKILL_RUNTIME_FAILED";
    }

    public String code() {
        return code;
    }
}
