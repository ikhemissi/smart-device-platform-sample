package io.github.ikhemissi.sdp.platform.security;

import org.springframework.security.access.AccessDeniedException;

/** The caller is authenticated but lacks the permission an operation requires. */
public class MissingRoleException extends AccessDeniedException {

    private final transient SdpRole requiredRole;

    public MissingRoleException(SdpRole requiredRole) {
        super("Missing permission " + requiredRole.appRole());
        this.requiredRole = requiredRole;
    }

    public SdpRole requiredRole() {
        return requiredRole;
    }
}
