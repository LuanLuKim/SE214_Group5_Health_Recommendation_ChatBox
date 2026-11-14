package vn.edu.uit.se214.health.accounts.registration;

import vn.edu.uit.se214.health.accounts.config.EmailOtpProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.edu.uit.se214.health.shared.web.ApiException;

@Component
@Profile("postgres")
public class OtpHasher {
  private static final String ALGORITHM = "HmacSHA256";
  private static final int MIN_SECRET_BYTES = 32;

  private final EmailOtpProperties properties;

  public OtpHasher(EmailOtpProperties properties) {
    this.properties = properties;
  }

  public String hash(String email, UUID salt, String code) {
    byte[] secret = configuredSecret();
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      byte[] input = (salt + "\n" + email + "\n" + code).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(mac.doFinal(input));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("OTP_HASH_UNAVAILABLE", e);
    }
  }

  public boolean matches(String email, UUID salt, String code, String expectedHash) {
    try {
      byte[] expected = HexFormat.of().parseHex(expectedHash);
      byte[] actual = HexFormat.of().parseHex(hash(email, salt, code));
      return MessageDigest.isEqual(expected, actual);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private byte[] configuredSecret() {
    String value = properties.hashSecret();
    if (value == null || value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES)
      throw ApiException.emailVerificationUnavailable();
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
