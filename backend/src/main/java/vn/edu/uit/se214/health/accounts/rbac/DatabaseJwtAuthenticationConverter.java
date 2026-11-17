package vn.edu.uit.se214.health.accounts.rbac;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres")
public class DatabaseJwtAuthenticationConverter
    implements Converter<Jwt, Collection<GrantedAuthority>> {
  private final AccountRbacRepository repository;

  public DatabaseJwtAuthenticationConverter(AccountRbacRepository repository) {
    this.repository = repository;
  }

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    try {
      UUID userId = UUID.fromString(jwt.getSubject());
      if (!repository.userExists(userId)) throw new BadCredentialsException("The account was deleted.");
      return repository.findAuthorities(userId).stream()
          .map(SimpleGrantedAuthority::new)
          .map(GrantedAuthority.class::cast)
          .toList();
    } catch (IllegalArgumentException | NullPointerException exception) {
      return List.of();
    }
  }
}
