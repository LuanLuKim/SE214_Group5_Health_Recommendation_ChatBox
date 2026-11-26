package accounts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.accounts")
public record AccountsProperties(
    String termsVersion, String googleClientId, String facebookAppId, String facebookAppSecret) {}
