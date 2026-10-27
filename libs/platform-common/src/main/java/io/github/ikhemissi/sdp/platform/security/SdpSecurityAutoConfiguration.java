package io.github.ikhemissi.sdp.platform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Secures platform HTTP APIs as an OAuth2 resource server for Microsoft Entra ID (ADR-0007): every request needs a
 * valid v2 access token for the platform API except health probes; operations declare their permission with
 * {@link RequiredRole}; failures are answered with problem details. Backends re-validate tokens even behind the
 * gateway because Container Apps ingress is reachable directly.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, JwtDecoder.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "sdp.security", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SdpSecurityProperties.class)
@EnableWebSecurity
public class SdpSecurityAutoConfiguration {

    private final ProblemResponses problems = new ProblemResponses();

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder sdpJwtDecoder(SdpSecurityProperties properties) {
        properties.validate();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.effectiveJwkSetUri().toString()).build();
        decoder.setJwtValidator(SdpTokens.validator(properties));
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter sdpJwtAuthenticationConverter() {
        return SdpTokens.authenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain sdpSecurityFilterChain(HttpSecurity http, SdpSecurityProperties properties,
                                               JwtAuthenticationConverter converter) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(properties.publicPaths().toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .build();
    }

    @Bean
    WebMvcConfigurer sdpRequiredRoleConfigurer() {
        var interceptor = new RequiredRoleInterceptor(problems);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
