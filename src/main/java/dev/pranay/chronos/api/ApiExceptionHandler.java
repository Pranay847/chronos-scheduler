package dev.pranay.chronos.api;

import dev.pranay.chronos.security.InvalidTargetException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 7807 {@code application/problem+json} for every error the API can produce.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means Spring's own failures — malformed
 * JSON, wrong content type, unknown enum value — come back in the same shape as ours instead of
 * a servlet error page, so a client needs one parser rather than two.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI INVALID_JOB = URI.create("https://chronos.dev/problems/invalid-job");
    private static final URI JOB_NOT_FOUND = URI.create("https://chronos.dev/problems/job-not-found");
    private static final URI VALIDATION_FAILED = URI.create("https://chronos.dev/problems/validation-failed");
    private static final URI RATE_LIMITED = URI.create("https://chronos.dev/problems/rate-limited");
    private static final URI FORBIDDEN_TARGET = URI.create("https://chronos.dev/problems/forbidden-target");

    @ExceptionHandler(InvalidJobException.class)
    public ProblemDetail handleInvalidJob(InvalidJobException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid job definition");
        problem.setType(INVALID_JOB);
        return problem;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ProblemDetail handleRateLimit(RateLimitExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setTitle("Rate limit exceeded");
        problem.setType(RATE_LIMITED);
        problem.setProperty("limitPerMinute", e.getLimitPerMinute());
        return problem;
    }

    /**
     * A target the SSRF guard refused.
     *
     * <p>400 rather than 403: the request is malformed in the sense that this URL is not one the
     * service will ever accept. The message names the range so a legitimate user with a
     * misconfigured DNS record can work out what happened, without being specific enough to be a
     * useful network-probing oracle.
     */
    @ExceptionHandler(InvalidTargetException.class)
    public ProblemDetail handleInvalidTarget(InvalidTargetException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Forbidden webhook target");
        problem.setType(FORBIDDEN_TARGET);
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleConflict(IllegalStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflicting request");
        return problem;
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ProblemDetail handleNotFound(JobNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Job not found");
        problem.setType(JOB_NOT_FOUND);
        return problem;
    }

    /**
     * Bean Validation failures, flattened to field → message.
     *
     * <p>Returning every violation at once rather than the first one means a client fixing a
     * malformed request needs one round trip instead of one per mistake.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        problem.setTitle("Validation failed");
        problem.setType(VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }
}
