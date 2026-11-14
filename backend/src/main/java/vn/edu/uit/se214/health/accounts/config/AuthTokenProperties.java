package vn.edu.uit.se214.health.accounts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.accounts.auth-token")
public record AuthTokenProperties(String secret) {}
