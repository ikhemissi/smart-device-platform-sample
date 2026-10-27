package io.github.ikhemissi.sdp.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiredRole} on platform controllers. Handlers in the platform's packages without the annotation
 * are refused, so a forgotten annotation can never expose data to users without a role.
 */
public final class RequiredRoleInterceptor implements HandlerInterceptor {

    static final String PLATFORM_PACKAGE = "io.github.ikhemissi.sdp.";
    private static final Logger LOG = LoggerFactory.getLogger(RequiredRoleInterceptor.class);

    private final AccessDeniedHandler accessDeniedHandler;

    RequiredRoleInterceptor(AccessDeniedHandler accessDeniedHandler) {
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method) || !method.getBeanType().getName().startsWith(PLATFORM_PACKAGE)) {
            return true;
        }
        SdpRole role = requiredRole(method);
        if (role == null) {
            LOG.error("Refusing {}: handler {} has no @RequiredRole", request.getRequestURI(), method.getShortLogMessage());
            accessDeniedHandler.handle(request, response, new AccessDeniedException("Operation without declared permission"));
            return false;
        }
        if (!SdpUser.has(SecurityContextHolder.getContext().getAuthentication(), role)) {
            accessDeniedHandler.handle(request, response, new MissingRoleException(role));
            return false;
        }
        return true;
    }

    /** Method-level annotation first, then the controller class. */
    public static SdpRole requiredRole(HandlerMethod method) {
        RequiredRole annotation = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiredRole.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiredRole.class);
        }
        return annotation != null ? annotation.value() : null;
    }
}
