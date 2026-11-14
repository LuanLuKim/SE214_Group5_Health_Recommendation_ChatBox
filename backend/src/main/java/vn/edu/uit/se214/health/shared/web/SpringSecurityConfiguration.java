package vn.edu.uit.se214.health.shared.web;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import vn.edu.uit.se214.health.accounts.auth.AccessTokenService;
import vn.edu.uit.se214.health.accounts.config.AuthTokenProperties;

@Configuration
public class SpringSecurityConfiguration {
  private static final Set<String> PUBLIC_GET_ROUTES =
      Set.of(
          "/healthz",
          "/api/v1/modules",
          "/api/v1/safety",
          "/api/v1/knowledge",
          "/api/v1/health",
          "/api/v1/chat",
          "/api/v1/accounts",
          "/api/v1/lifestyle",
          "/api/v1/knowledge/articles",
          "/api/v1/knowledge/facilities",
          "/api/v1/csrf");
  private static final Set<String> PUBLIC_POST_ROUTES =
      Set.of(
          "/api/v1/accounts/register",
          "/api/v1/accounts/register/verify",
          "/api/v1/accounts/register/resend",
          "/api/v1/accounts/login",
          "/api/v1/accounts/oauth",
          "/api/v1/accounts/refresh",
          "/api/v1/accounts/forgot-password",
          "/api/v1/accounts/reset-password",
          "/api/v1/accounts/confirm-deletion",
          "/api/v1/accounts/logout");
  private static final Set<String> PRIVATE_MODULES =
      Set.of(
          "/api/v1/safety",
          "/api/v1/knowledge",
          "/api/v1/health",
          "/api/v1/chat",
          "/api/v1/accounts",
          "/api/v1/lifestyle");

  @Bean
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http,
      ObjectMapper objectMapper,
      ObjectProvider<JwtDecoder> jwtDecoderProvider,
      Environment environment,
      CookieCsrfTokenRepository csrfTokenRepository)
      throws Exception {
    boolean postgresMode = environment.acceptsProfiles(Profiles.of("postgres"));
    AuthenticationEntryPoint entryPoint = authenticationEntryPoint(objectMapper, postgresMode);

    http.cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(SpringSecurityConfiguration::isPublicRequest)
                    .permitAll()
                    .requestMatchers(SpringSecurityConfiguration::requiresAuthentication)
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler(objectMapper)));

    if (postgresMode) {
      http.oauth2ResourceServer(
          resourceServer ->
              resourceServer
                  .bearerTokenResolver(accessTokenCookieResolver())
                  .jwt(jwt -> jwt.decoder(jwtDecoderProvider.getObject()))
                  .authenticationEntryPoint(entryPoint));
      http.csrf(
          csrf ->
              csrf.csrfTokenRepository(csrfTokenRepository)
                  .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
    } else {
      http.csrf(csrf -> csrf.disable());
    }

    SecurityFilterChain filterChain = http.build();
    if (postgresMode) {
      CsrfFilter csrfFilter =
          filterChain.getFilters().stream()
              .filter(CsrfFilter.class::isInstance)
              .map(CsrfFilter.class::cast)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("CSRF filter is missing."));
      // Resource Server normally skips CSRF for bearer tokens; this app carries them in cookies.
      csrfFilter.setRequireCsrfProtectionMatcher(
          request -> {
            String method = request.getMethod();
            return !("GET".equals(method)
                || "HEAD".equals(method)
                || "TRACE".equals(method)
                || "OPTIONS".equals(method));
          });
    }
    return filterChain;
  }

  @Bean
  CookieCsrfTokenRepository csrfTokenRepository() {
    CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfTokens.setCookiePath("/api/v1");
    csrfTokens.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
    return csrfTokens;
  }

  @Bean
  @Profile("postgres")
  JwtEncoder accessTokenEncoder(AuthTokenProperties properties) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
  }

  @Bean
  @Profile("postgres")
  JwtDecoder accessTokenDecoder(AuthTokenProperties properties) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(secretKey(properties))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(AccessTokenService.ISSUER));
    return decoder;
  }

  private static BearerTokenResolver accessTokenCookieResolver() {
    return request -> {
      if (!requiresAuthentication(request)) return null;
      if (request.getCookies() == null) return null;
      return Arrays.stream(request.getCookies())
          .filter(cookie -> "HA_ACCESS".equals(cookie.getName()))
          .map(jakarta.servlet.http.Cookie::getValue)
          .filter(value -> value != null && !value.isBlank())
          .findFirst()
          .orElse(null);
    };
  }

  private static boolean isPublicRequest(jakarta.servlet.http.HttpServletRequest request) {
    String path = requestPath(request);
    String method = request.getMethod();
    return (HttpMethod.GET.matches(method) && PUBLIC_GET_ROUTES.contains(path))
        || (HttpMethod.POST.matches(method) && PUBLIC_POST_ROUTES.contains(path))
        || "/swagger-ui.html".equals(path)
        || path.startsWith("/swagger-ui/")
        || path.startsWith("/v3/api-docs");
  }

  private static boolean requiresAuthentication(
      jakarta.servlet.http.HttpServletRequest request) {
    String path = requestPath(request);
    return !isPublicRequest(request)
        && PRIVATE_MODULES.stream()
            .anyMatch(module -> path.equals(module) || path.startsWith(module + "/"));
  }

  private static String requestPath(jakarta.servlet.http.HttpServletRequest request) {
    String path = request.getRequestURI();
    String contextPath = request.getContextPath();
    return contextPath.isEmpty() ? path : path.substring(contextPath.length());
  }

  private static AuthenticationEntryPoint authenticationEntryPoint(
      ObjectMapper objectMapper, boolean postgresMode) {
    return (request, response, exception) -> {
      ApiException error =
          postgresMode ? ApiException.sessionInvalid() : ApiException.authNotConfigured();
      writeFailure(objectMapper, request, response, error.status().value(), error.code(), error.getMessage());
    };
  }

  private static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
    return (request, response, exception) -> {
      boolean csrfFailure = exception instanceof CsrfException;
      writeFailure(
          objectMapper,
          request,
          response,
          403,
          csrfFailure ? "CSRF_INVALID" : "ACCESS_DENIED",
          csrfFailure ? "Yêu cầu bảo mật không hợp lệ hoặc đã hết hạn." : "Bạn không có quyền thực hiện yêu cầu này.");
    };
  }

  private static void writeFailure(
      ObjectMapper objectMapper,
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response,
      int status,
      String code,
      String message)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(
        response.getOutputStream(),
        new ApiResponse.Failure(new ApiResponse.Error(code, message, RequestIds.get(request))));
  }

  private static SecretKey secretKey(AuthTokenProperties properties) {
    String secret = properties.secret();
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalStateException("AUTH_TOKEN_SECRET must contain at least 32 bytes.");
    return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }
}
