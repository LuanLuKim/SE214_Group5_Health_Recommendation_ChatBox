package vn.edu.uit.se214.health.accounts;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.accounts.email-otp")
public record EmailOtpProperties(String hashSecret, String fromAddress) {}
