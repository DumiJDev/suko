package shop;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Spring Security da loja: nenhuma rota autenticada, CSRF ligado (repositório de sessão por omissão;
 * o token entra nos formulários por {@code shop.web.ShopModelAdvice}) e cabeçalhos de segurança.
 *
 * <p>A CSP é estrita a sério — sem {@code 'unsafe-inline'} — porque nenhum componente Suko da loja
 * usa {@code style=}, {@code on*} ou {@code <script>} inline ({@code suko.security.strictCsp}).
 */
@Configuration
class SecurityConfig {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        + "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    /** Extra além do brief: a loja não usa nenhuma destas APIs do browser. */
    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(Customizer.withDefaults())
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(f -> f.deny())
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY)));
        return http.build();
    }

    /**
     * Nenhum utilizador. Sem este bean o Spring Boot criava o utilizador {@code user} com uma password
     * gerada e escrevia-a no log ("Using generated security password").
     */
    @Bean
    UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }
}
