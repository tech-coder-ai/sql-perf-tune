package com.techcoder.sqlperf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * {@code spt.security.mode=NONE} (local dev) leaves the API open; {@code JWT} turns the service into an
 * OAuth2 resource server (configure {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SptProperties props) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (props.security().mode() == SptProperties.SecurityMode.JWT) {
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/api/ui-config").permitAll()
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SptProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors().allowedOrigins());
        cfg.addAllowedMethod("*");
        cfg.addAllowedHeader("*");
        cfg.addExposedHeader("Content-Disposition");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
