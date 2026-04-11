package com.edgeguardian.controller.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
@ConditionalOnProperty(prefix = "edgeguardian.controller.cors", name = "allowed-origins")
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class CorsConfig {

    private final CorsProperties corsProperties;

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(corsProperties.allowedOrigins());
        config.setAllowedMethods(corsProperties.allowedMethods());
        config.setAllowedHeaders(corsProperties.allowedHeaders());
        config.setAllowCredentials(corsProperties.allowCredentials());

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        var registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
