package vn.edu.uit.se214.health.accounts.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AccountPasswordConfig {
  @Bean
  PasswordEncoder accountPasswordEncoder() {
    return new BCryptPasswordEncoder(12);
  }
}
