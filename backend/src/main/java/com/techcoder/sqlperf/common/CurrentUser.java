package com.techcoder.sqlperf.common;

import java.util.function.Supplier;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the acting user for audit columns: the authenticated principal, else the {@code X-User}
 * header (local dev without an identity provider), else {@code anonymous}.
 */
public final class CurrentUser {

    /** Set while background work runs on behalf of the system (e.g. automatic diagnostics capture). */
    private static final ThreadLocal<String> ACTING = new ThreadLocal<>();

    private CurrentUser() {
    }

    /** Runs {@code work} with {@link #name()} returning {@code name} on this thread (background jobs). */
    public static <T> T runAs(String name, Supplier<T> work) {
        String before = ACTING.get();
        ACTING.set(name);
        try {
            return work.get();
        } finally {
            if (before == null) {
                ACTING.remove();
            } else {
                ACTING.set(before);
            }
        }
    }

    public static String name() {
        String acting = ACTING.get();
        if (acting != null) {
            return acting;
        }
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
