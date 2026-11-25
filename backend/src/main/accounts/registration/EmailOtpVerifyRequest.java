package accounts.registration;

public record EmailOtpVerifyRequest(String email, String code) {}
