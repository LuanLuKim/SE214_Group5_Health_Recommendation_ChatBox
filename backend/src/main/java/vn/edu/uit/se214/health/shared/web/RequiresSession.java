package vn.edu.uit.se214.health.shared.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a private route. Until M5 implements trusted sessions, {@link SessionGuard} rejects every
 * such request with 503 AUTH_NOT_CONFIGURED before the body is read. Never trust X-User-ID,
 * query parameters or a client-selected role as authentication.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresSession {}
