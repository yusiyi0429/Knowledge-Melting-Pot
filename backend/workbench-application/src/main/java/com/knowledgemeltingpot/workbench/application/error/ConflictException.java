package com.knowledgemeltingpot.workbench.application.error;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
