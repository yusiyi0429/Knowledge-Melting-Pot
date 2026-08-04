package com.knowledgemeltingpot.workbench.application.error;

public class UnprocessableEntityException extends RuntimeException {
    private final String code;

    public UnprocessableEntityException(String message) {
        this("document-validation", message);
    }

    public UnprocessableEntityException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
