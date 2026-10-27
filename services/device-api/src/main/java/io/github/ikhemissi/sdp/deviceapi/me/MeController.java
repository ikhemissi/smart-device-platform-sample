package io.github.ikhemissi.sdp.deviceapi.me;

import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Current user")
public class MeController {

    @GetMapping("/api/v1/me")
    @RequiredRole(SdpRole.AUTHENTICATED)
    @Operation(operationId = "getCurrentUser", summary = "Signed-in user and effective permissions",
            description = "Available without any platform role so that clients can explain which permission to request.")
    public SdpUser me() {
        return SdpUser.current();
    }
}
