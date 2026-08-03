package com.knowledgemeltingpot.workbench.api.http;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.error.PayloadTooLargeException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionRequiredException;
import com.knowledgemeltingpot.workbench.application.error.UnprocessableEntityException;
import java.net.URI;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> authentication(AuthenticationException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", "Invalid username or password",
                "authentication-failed");
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage(), "not-found");
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ProblemDetail> payloadTooLarge(PayloadTooLargeException exception) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large", exception.getMessage(),
                "material-too-large");
    }

    @ExceptionHandler({ConflictException.class, DataIntegrityViolationException.class})
    ResponseEntity<ProblemDetail> conflict(Exception exception) {
        String detail = exception instanceof ConflictException
                ? exception.getMessage()
                : "The request conflicts with current persisted state";
        return problem(HttpStatus.CONFLICT, "Conflict", detail, "conflict");
    }

    @ExceptionHandler(PreconditionFailedException.class)
    ResponseEntity<ProblemDetail> preconditionFailed(PreconditionFailedException exception) {
        return problem(HttpStatus.PRECONDITION_FAILED, "Precondition failed", exception.getMessage(),
                "precondition-failed");
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ResponseEntity<ProblemDetail> preconditionRequired(PreconditionRequiredException exception) {
        return problem(HttpStatus.valueOf(428), "Precondition required", exception.getMessage(),
                "precondition-required");
    }

    @ExceptionHandler(UnprocessableEntityException.class)
    ResponseEntity<ProblemDetail> unprocessable(UnprocessableEntityException exception) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Document validation failed", exception.getMessage(),
                "document-validation");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception) {
        List<ValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid", "validation");
        response.getBody().setProperty("errors", errors);
        return response;
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception exception) {
        String detail = exception instanceof IllegalArgumentException ? exception.getMessage() : "Malformed request body";
        return problem(HttpStatus.BAD_REQUEST, "Bad request", detail, "bad-request");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://knowledge-melting-pot.local/problems/" + code));
        problem.setProperty("code", code);
        problem.setProperty("traceId", RequestIdFilter.currentTraceId());
        return ResponseEntity.status(status).body(problem);
    }

    private ValidationError formatFieldError(FieldError error) {
        return new ValidationError(error.getField(), error.getDefaultMessage());
    }

    private record ValidationError(String field, String message) {
    }
}
