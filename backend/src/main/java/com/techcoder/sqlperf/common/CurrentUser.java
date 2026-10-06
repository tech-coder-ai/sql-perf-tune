package com.techcoder.sqlperf.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the acting user for audit columns: the authenticated principal, else the {@code X-User}
 * header (local dev without an identity provider), else {@code anonymous}.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String header = attrs.getRequest().getHeader("X-User");
            if (header != null && !header.isBlank()) {
                return header.trim();
            }
        }
        return "anonymous";
    }
}
