package io.github.ikhemissi.sdp.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * RFC 9457 problem details for authentication and authorization failures, keeping the RFC 6750
 * {@code WWW-Authenticate} header set by the bearer-token handlers.
 */
final class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler accessDenied = new BearerTokenAccessDeniedHandler();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        entryPoint.commence(request, response, exception);
        write(request, response, 401, "Unauthorized",
                "A valid Microsoft Entra ID access token for the platform API is required.", null);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        accessDenied.handle(request, response, exception);
        SdpRole role = exception instanceof MissingRoleException missing ? missing.requiredRole() : null;
        String detail = role != null && role.appRole() != null
                ? "This operation requires the " + role.appRole() + " permission."
                : "This operation is not permitted.";
        write(request, response, 403, "Forbidden", detail, role != null ? role.appRole() : null);
    }

    private static void write(HttpServletRequest request, HttpServletResponse response, int status, String title,
                              String detail, String requiredRole) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var json = new StringBuilder("{\"type\":\"about:blank\",\"title\":").append(quote(title))
                .append(",\"status\":").append(status)
                .append(",\"detail\":").append(quote(detail))
                .append(",\"instance\":").append(quote(request.getRequestURI()));
        if (requiredRole != null) {
            json.append(",\"requiredRole\":").append(quote(requiredRole));
        }
        response.getWriter().write(json.append('}').toString());
    }

    private static String quote(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
