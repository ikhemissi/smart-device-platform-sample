package io.github.ikhemissi.sdp.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission an API operation requires. Enforced in the backend by {@link RequiredRoleInterceptor} and
 * published as the {@code x-sdp-required-role} OpenAPI extension so that the gateway enforces the same rule. Platform
 * handlers without this annotation are refused (fail closed). A method-level annotation overrides the class level.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiredRole {

    SdpRole value();
}
