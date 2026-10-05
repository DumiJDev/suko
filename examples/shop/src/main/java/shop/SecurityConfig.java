package shop;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Mínimo para a parte 1/2: a loja não tem rotas autenticadas, por isso tudo é público
 * (sem isto o Spring Security por omissão pedia login em todas as páginas). O CSRF fica
 * ligado (por omissão). Os cabeçalhos (CSP estrita, Referrer-Policy, ...) entram na Task 13.
 */
@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
