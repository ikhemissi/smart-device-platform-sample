package io.github.ikhemissi.sdp.provisioning.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A request that cannot be served, answered as RFC 9457 problem details (with per-field {@code errors} if any). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, List<String>> errors;

    public ApiException(HttpStatus status, String detail) {
        this(status, detail, Map.of());
    }

    public ApiException(HttpStatus status, String detail, Map<String, List<String>> errors) {
        super(detail);
        this.status = status;
        this.errors = Map.copyOf(errors);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException invalid(Map<String, List<String>> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiExceptionHandler.summary(errors), errors);
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, List<String>> errors() {
        return errors;
    }
}
