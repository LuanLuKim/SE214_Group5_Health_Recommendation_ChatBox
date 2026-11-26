package accounts.auth;

public record AccountConsentRequest(
    Boolean termsAccepted,
    Boolean healthDataProcessingAccepted,
    Boolean nonDiagnosticAcknowledged,
    Boolean adultConfirmed,
    String termsVersion) {}
