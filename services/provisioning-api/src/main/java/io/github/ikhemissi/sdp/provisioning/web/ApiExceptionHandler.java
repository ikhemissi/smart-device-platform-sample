package io.github.ikhemissi.sdp.provisioning.web;

import io.github.ikhemissi.sdp.provisioning.certificates.CertificateRejectedException;
import io.github.ikhemissi.sdp.provisioning.sites.ProvisioningUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 9457 problem details for every API error; validation failures carry per-field {@code errors}. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        if (!exception.errors().isEmpty()) {
            problem.setProperty("errors", new TreeMap<>(exception.errors()));
        }
        return ResponseEntity.status(exception.status()).body(problem);
    }

    @ExceptionHandler(CertificateRejectedException.class)
    ResponseEntity<ProblemDetail> handleRejectedCertificate(CertificateRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Certificate rejected");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ProvisioningUnavailableException.class)
    ResponseEntity<ProblemDetail> handleUnavailable(ProvisioningUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The device provisioning service could not be updated; nothing was changed. Try again in a moment.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "30").body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The file is larger than 64 KB; upload a single certificate.");
        problem.setTitle("Certificate rejected");
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, List<String>> errors = new TreeMap<>();
        exception.getParameterValidationResults().forEach(result -> errors
                .computeIfAbsent(result.getMethodParameter().getParameterName(), name -> new java.util.ArrayList<>())
                .addAll(result.getResolvableErrors().stream().map(MessageSourceResolvable::getDefaultMessage).toList()));
        return validationProblem(errors, headers);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, List<String>> errors = new TreeMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> errors
                .computeIfAbsent(error.getField(), name -> new java.util.ArrayList<>()).add(error.getDefaultMessage()));
        return validationProblem(errors, headers);
    }

    /** Anything unexpected: logged with its stack trace, answered without internal details. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        LoggerFactory.getLogger(ApiExceptionHandler.class).error("Unexpected error", exception);
        return ResponseEntity.internalServerError().body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Try again; if the problem persists, contact your administrator."));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = exception instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : String.valueOf(exception.getPropertyName());
        String type = exception.getRequiredType() != null ? exception.getRequiredType().getSimpleName() : "value";
        return validationProblem(Map.of(name, List.of("must be a valid " + type.toLowerCase())), headers);
    }

    private static ResponseEntity<Object> validationProblem(Map<String, List<String>> errors, HttpHeaders headers) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, summary(errors));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    static String summary(Map<String, List<String>> errors) {
        return errors.entrySet().stream()
                .map(entry -> entry.getKey() + " " + String.join(", ", entry.getValue()))
                .collect(Collectors.joining("; ", "Invalid request: ", "."));
    }
}
