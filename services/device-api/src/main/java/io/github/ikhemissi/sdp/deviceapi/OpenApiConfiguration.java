package io.github.ikhemissi.sdp.deviceapi;

import io.github.ikhemissi.sdp.platform.security.RequiredRoleInterceptor;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document imported into API Management; each operation declares its role in {@code x-sdp-required-role}. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String REQUIRED_ROLE_EXTENSION = "x-sdp-required-role";

    @Bean
    OpenAPI deviceApiOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Smart Device Platform API").version("v1")
                        .description("Device catalog of the Smart Device Platform. Requires a Microsoft Entra ID access token "
                                + "for the platform API; operations declare the app role they need in " + REQUIRED_ROLE_EXTENSION + "."))
                .components(new Components().addSecuritySchemes("entra", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("entra"));
    }

    @Bean
    OperationCustomizer requiredRoleExtension() {
        return (operation, handler) -> {
            SdpRole role = RequiredRoleInterceptor.requiredRole(handler);
            if (role != null) {
                operation.addExtension(REQUIRED_ROLE_EXTENSION, role.openApiValue());
            }
            return operation;
        };
    }
}
