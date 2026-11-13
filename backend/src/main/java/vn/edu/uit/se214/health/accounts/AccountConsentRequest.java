package vn.edu.uit.se214.health.accounts;

public record AccountConsentRequest(
    Boolean termsAccepted,
    Boolean healthDataProcessingAccepted,
    Boolean nonDiagnosticAcknowledged,
    Boolean adultConfirmed,
    String termsVersion) {}
