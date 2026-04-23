package com.edgeguardian.controller.config;

import com.edgeguardian.controller.api.ApiPaths;
import com.edgeguardian.controller.security.ApiKeyAuthenticationFilter;
import com.edgeguardian.controller.security.DeviceTokenAuthFilter;
import com.edgeguardian.controller.security.JwtTenantConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final DeviceTokenAuthFilter deviceTokenAuthFilter;
    private final JwtTenantConverter jwtTenantConverter;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String expectedIssuer;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Bean
    public JwtDecoder jwtDecoder() {
        log.info("JwtDecoder using jwk-set-uri={} expected issuer={}", jwkSetUri, expectedIssuer);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuerValidator = new JwtIssuerValidator(expectedIssuer);
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), issuerValidator);
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthenticationFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(deviceTokenAuthFilter, BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(ApiPaths.AGENT_ENROLL,
                                ApiPaths.AGENT_INSTALLER,
                                ApiPaths.AGENT_BINARY).permitAll()
                        .requestMatchers(ApiPaths.PKI_CRL_PATTERN,
                                ApiPaths.PKI_CA_BUNDLE,
                                ApiPaths.PKI_BROKER_CA).permitAll()
                        .requestMatchers(ApiPaths.API_V1_PATTERN).authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtTenantConverter)));

        return http.build();
    }
}
