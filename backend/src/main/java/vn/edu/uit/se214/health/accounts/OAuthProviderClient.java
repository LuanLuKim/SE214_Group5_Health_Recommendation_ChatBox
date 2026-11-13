package vn.edu.uit.se214.health.accounts;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import vn.edu.uit.se214.health.shared.web.ApiException;

@Component
@Profile("postgres")
public class OAuthProviderClient implements OAuthProviderVerifier {
  private static final String GOOGLE_TOKEN_INFO = "https://oauth2.googleapis.com/tokeninfo";
  private static final String FACEBOOK_GRAPH = "https://graph.facebook.com";

  private final RestClient http;
  private final AccountsProperties properties;

  public OAuthProviderClient(RestClient.Builder builder, AccountsProperties properties) {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var requestFactory = new JdkClientHttpRequestFactory(client);
    requestFactory.setReadTimeout(Duration.ofSeconds(5));
    this.http = builder.requestFactory(requestFactory).build();
    this.properties = properties;
  }

  @Override
  public VerifiedOAuthIdentity verify(OAuthProvider provider, String token) {
    return switch (provider) {
      case GOOGLE -> verifyGoogle(token);
      case FACEBOOK -> verifyFacebook(token);
    };
  }

  private VerifiedOAuthIdentity verifyGoogle(String token) {
    if (isBlank(properties.googleClientId())) throw ApiException.oauthProviderUnavailable();
    URI uri =
        UriComponentsBuilder.fromUriString(GOOGLE_TOKEN_INFO)
            .queryParam("id_token", token)
            .build()
            .encode()
            .toUri();
    JsonNode claims = get(uri);
    long expiresAt = longClaim(claims, "exp");
    String issuer = textClaim(claims, "iss");
    boolean verifiedEmail =
        "true".equalsIgnoreCase(textClaim(claims, "email_verified"));
    if (!properties.googleClientId().equals(textClaim(claims, "aud"))
        || !(issuer.equals("accounts.google.com") || issuer.equals("https://accounts.google.com"))
        || expiresAt <= Instant.now().getEpochSecond()
        || !verifiedEmail) {
      throw ApiException.oauthVerificationFailed();
    }
    return new VerifiedOAuthIdentity(
        OAuthProvider.GOOGLE,
        textClaim(claims, "sub"),
        textClaim(claims, "email"),
        textClaim(claims, "name"));
  }

  private VerifiedOAuthIdentity verifyFacebook(String token) {
    if (isBlank(properties.facebookAppId()) || isBlank(properties.facebookAppSecret()))
      throw ApiException.oauthProviderUnavailable();
    String appAccessToken = properties.facebookAppId() + "|" + properties.facebookAppSecret();
    URI debugUri =
        UriComponentsBuilder.fromUriString(FACEBOOK_GRAPH + "/debug_token")
            .queryParam("input_token", token)
            .queryParam("access_token", appAccessToken)
            .build()
            .encode()
            .toUri();
    JsonNode debug = get(debugUri);
    JsonNode data = debug.path("data");
    String userId = textClaim(data, "user_id");
    if (!data.path("is_valid").asBoolean(false)
        || !properties.facebookAppId().equals(textClaim(data, "app_id"))
        || userId.isBlank()
        || (longClaim(data, "expires_at") != 0
            && longClaim(data, "expires_at") <= Instant.now().getEpochSecond())) {
      throw ApiException.oauthVerificationFailed();
    }

    URI profileUri =
        UriComponentsBuilder.fromUriString(FACEBOOK_GRAPH + "/me")
            .queryParam("fields", "id,name,email")
            .queryParam("access_token", token)
            .build()
            .encode()
            .toUri();
    JsonNode profile = get(profileUri);
    String subject = textClaim(profile, "id");
    if (!userId.equals(subject)) throw ApiException.oauthVerificationFailed();
    return new VerifiedOAuthIdentity(
        OAuthProvider.FACEBOOK,
        subject,
        textClaim(profile, "email"),
        textClaim(profile, "name"));
  }

  private JsonNode get(URI uri) {
    try {
      JsonNode response = http.get().uri(uri).retrieve().body(JsonNode.class);
      if (response == null) throw ApiException.oauthVerificationFailed();
      return response;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().is4xxClientError()) throw ApiException.oauthVerificationFailed();
      throw ApiException.oauthProviderUnavailable();
    } catch (ResourceAccessException e) {
      throw ApiException.oauthProviderUnavailable();
    } catch (RestClientException e) {
      throw ApiException.oauthProviderUnavailable();
    }
  }

  private static String textClaim(JsonNode node, String name) {
    JsonNode claim = node.path(name);
    return claim.isMissingNode() || claim.isNull() ? "" : claim.asText("");
  }

  private static long longClaim(JsonNode node, String name) {
    try {
      return Long.parseLong(textClaim(node, name));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
