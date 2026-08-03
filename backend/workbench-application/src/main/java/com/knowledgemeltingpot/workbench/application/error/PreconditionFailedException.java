package com.knowledgemeltingpot.workbench.application.error;

public class PreconditionFailedException extends RuntimeException {
    public PreconditionFailedException(String message) {
        super(message);
    }
}
