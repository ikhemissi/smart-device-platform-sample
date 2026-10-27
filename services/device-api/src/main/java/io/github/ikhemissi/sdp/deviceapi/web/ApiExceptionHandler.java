package io.github.ikhemissi.sdp.deviceapi.web;

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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
