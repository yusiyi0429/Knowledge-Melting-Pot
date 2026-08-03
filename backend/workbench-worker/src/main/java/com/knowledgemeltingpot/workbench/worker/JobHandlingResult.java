package com.knowledgemeltingpot.workbench.worker;

public record JobHandlingResult(boolean succeeded, String resultReference, String errorCode, String errorMessage) {
    public JobHandlingResult {
        resultReference = resultReference == null ? "" : resultReference;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static JobHandlingResult success(String resultReference) {
        return new JobHandlingResult(true, resultReference, "", "");
    }

    public static JobHandlingResult failure(String code, String message) {
        return new JobHandlingResult(false, "", code, message);
    }
}
