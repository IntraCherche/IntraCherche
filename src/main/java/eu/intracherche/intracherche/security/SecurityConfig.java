package eu.intracherche.intracherche.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.mapping.NullAuthoritiesMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.*;

/**
 * This file handles the Authentication Logic.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${spring.ldap.urls}")
    private String ldapUrls;

    @Value("${spring.ldap.base}")
    private String ldapBase;

    @Value("${spring.ldap.username}")
    private String ldapUsername;

    @Value("${spring.ldap.password}")
    private String ldapPassword;

    @Value("${spring.security.ldap.user.search-filter}")
    private String searchFilter;

    @Value("${spring.security.ldap.user.search-base}")
    private String searchBase;

    private final SidAuthoritiesPopulator sidPopulator;
    private final ContextSource contextSource;

//    @Bean
//    public DefaultSpringSecurityContextSource contextSource() {
//        DefaultSpringSecurityContextSource contextSource =
//                new DefaultSpringSecurityContextSource(
//                        List.of(ldapUrls),
//                        ldapBase
//                );
//        contextSource.setUserDn(ldapUsername);
//        contextSource.setPassword(ldapPassword);
//
//        return contextSource;
//    }

    // Spring will automatically inject these from your LdapSecurityConfig
    public SecurityConfig(SidAuthoritiesPopulator sidPopulator, ContextSource contextSource) {
        this.sidPopulator = sidPopulator;
        this.contextSource = contextSource;
    }


    @Bean
    @SuppressWarnings("deprecation")
    public PasswordEncoder passwordEncoder() {
        // NoOpPasswordEncoder is intentional for LDAP: passwords must be passed as-is to AD, not pre-hashed.
        return NoOpPasswordEncoder.getInstance();
    }

    @Bean
    public AuthenticationManager authManager(HttpSecurity http,
                                             BaseLdapPathContextSource contextSource,
                                             SidAuthoritiesPopulator sidPopulator) throws Exception {

        AuthenticationManagerBuilder authBuilder =
                http.getSharedObject(AuthenticationManagerBuilder.class);

        authBuilder
                .ldapAuthentication()
                .userSearchBase(searchBase + "," + ldapBase)   // e.g., "CN=Users,DC=corp,DC=local"
                .userSearchFilter(searchFilter) // e.g., "(sAMAccountName={0})"
                .contextSource(contextSource)
                .ldapAuthoritiesPopulator(sidPopulator) // This links the SID logic
                // Keep the SIDs verbatim. The default mapper prefixes every authority with
                // "ROLE_", so the login response compared "ROLE_S-1-5-21-…" against
                // adminGroupSid / sherlockGroupSid and reported isAdmin and canUseSherlock
                // as false for everyone (the JWT filter path, which /me uses, was unaffected).
                .authoritiesMapper(new NullAuthoritiesMapper());

        return authBuilder.build();
    }

//    @Bean
//    public AuthenticationManager authManager(HttpSecurity http,
//                                             BaseLdapPathContextSource contextSource) throws Exception {
//
//        AuthenticationManagerBuilder auth =
//                http.getSharedObject(AuthenticationManagerBuilder.class);
//
//        auth
//                .ldapAuthentication()
//                .userSearchBase(searchBase)
//                .userSearchFilter(searchFilter)
//                .contextSource(contextSource)
//                .ldapAuthoritiesPopulator((ctx, username) -> {
//                    // Disable authorities lookup to avoid referral issues
//                    return Collections.emptyList();
//                });
//
//
//        return auth.build();
//    }

    // CORS: only allow cross-origin requests when cors.allowed-origin is explicitly set.
    // In production the Vue build is served from the same origin — no CORS needed.
    // For local development with the Vite dev server add to application-local.yaml:
    //   cors:
    //     allowed-origin: http://localhost:5173
    @Value("${cors.allowed-origin:}")
    private String corsAllowedOrigin;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (corsAllowedOrigin != null && !corsAllowedOrigin.isBlank()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of(corsAllowedOrigin));
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            source.registerCorsConfiguration("/**", config);
        }
        return source;
    }

    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            // Use DeferredCsrfToken directly — the most reliable path in Spring Security 7.
            // Calling .get() forces the repository to load (or generate) the token and write
            // the XSRF-TOKEN cookie to the response, making it readable by Axios.
            DeferredCsrfToken deferredCsrfToken =
                    (DeferredCsrfToken) request.getAttribute(DeferredCsrfToken.class.getName());
            if (deferredCsrfToken != null) {
                deferredCsrfToken.get();
            }
            filterChain.doFilter(request, response);
        }
    }

    @Configuration
    public class WebConfig implements WebMvcConfigurer {
        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            // This regex says: Forward to index.html ONLY IF:
            // 1. It does not start with /api
            // 2. It does not start with /error
            // 3. It does not have a dot (extension)
            registry.addViewController("/{path:^(?!api|error)[^\\.]*}")
                    .setViewName("forward:/index.html");
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**")
                    .addResourceLocations("classpath:/static/");
        }
    }

    /**
     * What the browser may load and run on these pages: this server only, no plugin, no framing.
     * Inline styles stay allowed — Vue and the guided tour set them — scripts never. blob: is the
     * tab a document opens in (see SearchResultItem.openFile). Violations are reported to
     * /api/csp-report and logged, so a page the policy breaks shows up in app.log with its cause.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "frame-src 'self' blob:",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "report-uri /api/csp-report");

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${server.port:8080}")
    private int serverPort;

    /** Port of the plain-HTTP connector that only redirects to HTTPS (see HttpsConfig); 0 = none. */
    @Value("${app.https.redirect-from-port:8080}")
    private int redirectFromPort;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthTokenFilter jwtFilter,
                                           SessionCookies sessionCookies, OidcLogin oidcLogin,
                                           ObjectProvider<ClientRegistrationRepository> oidcClients) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName(sessionCookies.csrfName());
        csrfRepository.setCookieCustomizer(cookie -> cookie.secure(sessionCookies.secure()).sameSite("Strict"));

        http
                // 1. CSRF protection — cookie-based so Axios handles it automatically.
                // The XSRF-TOKEN cookie is JS-readable; Axios reads it and sends X-XSRF-TOKEN.
                // Login is excluded: the user has no cookie yet at that point.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/auth/login", "/api/csp-report")
                        // Never rotate the token. STATELESS makes Spring add a SessionManagementFilter,
                        // which treats every JWT-authenticated request as a fresh login and, through the
                        // default CsrfAuthenticationStrategy, deletes the XSRF-TOKEN cookie without
                        // writing a new one. The next POST then went out with no token and got 403,
                        // and only the retry (whose 403 response set a new cookie) went through.
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                )

                // 2. Enable CORS (So Vue at port 5173 can talk to Spring at 8080)
                .cors(Customizer.withDefaults())

                // We disable default login
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                // 3. Set Session to STATELESS (We are using JWT, not JSESSIONID)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // 4. Define the "Rules"
                .authorizeHttpRequests(auth -> auth
                        // 1. Allow the Login API
                        .requestMatchers("/api/auth/**").permitAll()
                        // CSP violation reports: sent by the browser without credentials.
                        .requestMatchers("/api/csp-report").permitAll()
                        // OpenID Connect login: the way out to the provider and the way back (see OidcLogin).
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()

                        // 2. Allow Static Files (Vue/Vite Build) and SPA frontend routes
                        // Frontend routes must be permitted at the HTTP level so that direct URL
                        // access and page refresh work — Vue Router enforces auth client-side.
                        .requestMatchers("/", "/index.html", "/login", "/admin", "/abonnements", "/*.svg", "/*.ico", "/*.png", "/assets/**").permitAll()

                        // 3. The /error path is critical—if something crashes,
                        // Spring redirects to /error, which will 403 if not permitted!
                        .requestMatchers("/error").permitAll()

                        // 4. Allow download endpoint for authenticated users
                        .requestMatchers("/api/download").authenticated()

                        // 5. Everything else (Search API) requires JWT
                        .anyRequest().authenticated()
                );




        // This is the CRITICAL part for SPAs:
        // If an entry point is triggered, DO NOT redirect. Just send 401.
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    String path = request.getServletPath();

                    // 1. If it's an API call, return JSON so Axios can catch it
                    if (path.startsWith("/api")) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write("{\"message\": \"Unauthorized\", \"status\": 401}");
                    }
                    // 2. If the user typed a random URL in the browser bar
                    else {
                        // Simply let the request proceed to the forward logic
                        // or redirect to home so Vue Router takes over.
                        response.sendRedirect("/");
                    }
                })
        );
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));

        // HTTPS served by the application itself: anything reaching the plain-HTTP port is
        // redirected to the same address in HTTPS. HSTS (Spring Security's default on HTTPS
        // responses) then keeps the browser on HTTPS once the certificate is trusted.
        if (sslEnabled && redirectFromPort > 0) {
            http.portMapper(ports -> ports.http(redirectFromPort).mapsTo(serverPort));
            http.redirectToHttps(Customizer.withDefaults());
        }

        // OpenID Connect login, only when switched on. The handshake (state, nonce, PKCE) is kept in a
        // short server session that OidcLogin ends as soon as the login does; the session that follows
        // is the JWT cookie, as for the password login. loginPage("/login") is the Vue page, which
        // keeps Spring from generating a login page of its own at that address.
        if (oidcLogin.enabled()) {
            if (oidcClients.getIfAvailable() == null) {
                throw new IllegalStateException("auth.oidc.enabled is true but no provider is configured: "
                        + "set spring.security.oauth2.client.registration.google.client-id and client-secret");
            }
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .successHandler(oidcLogin)
                    .failureHandler(oidcLogin));
        }

        // This makes sure your JWT is checked for every search request
        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        // Force the deferred CSRF token to be loaded on every request so that
        // CookieCsrfTokenRepository writes the XSRF-TOKEN cookie in the response.
        // Without this, Spring Security 6's lazy loading never sets the cookie for
        // a pure REST backend (no server-side templates), and all POST requests get 403.
        http.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }
}
