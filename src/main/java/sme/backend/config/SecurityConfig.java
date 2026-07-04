package sme.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import sme.backend.security.CustomUserDetailsService;
import sme.backend.security.filter.JwtAuthEntryPoint;
import sme.backend.security.filter.JwtAuthenticationFilter;
import sme.backend.security.oauth2.CustomOAuth2UserService;
import sme.backend.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import sme.backend.security.oauth2.OAuth2AuthenticationFailureHandler;
import sme.backend.security.oauth2.OAuth2AuthenticationSuccessHandler;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true) 
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthEntryPoint jwtAuthEntryPoint;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final HttpCookieOAuth2AuthorizationRequestRepository httpCookieOAuth2AuthorizationRequestRepository;
    private final PasswordEncoder passwordEncoder;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder);
        provider.setUserDetailsService(userDetailsService);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Tắt CSRF (dùng JWT stateless)
                .csrf(AbstractHttpConfigurer::disable)

                // CORS xử lý bởi CorsConfig bean
                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                // Stateless session (JWT)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Entry point 401
                .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthEntryPoint))

                // OAuth2 Login
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(auth -> auth
                                .baseUri("/oauth2/authorize")
                                .authorizationRequestRepository(httpCookieOAuth2AuthorizationRequestRepository)
                        )
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler)
                )

                // ============================================================
                // PHÂN QUYỀN THEO ROLE (RBAC)
                // ============================================================
                .authorizeHttpRequests(auth -> auth

                        // Public endpoints
                        // Public endpoints
                        .requestMatchers("/auth/switch-branch", "/auth/unlock-user").hasRole("ADMIN")
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/payments/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/ws/**", "/ws/**").permitAll() // WebSocket handshake
                        .requestMatchers(HttpMethod.GET, "/products/**", "/products").permitAll()
                        .requestMatchers(HttpMethod.GET, "/categories/**", "/categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/authors/**", "/authors").permitAll()
                        .requestMatchers(HttpMethod.GET, "/banners/**", "/banners").permitAll()
                        .requestMatchers(HttpMethod.GET, "/reviews/**", "/reviews").permitAll()
                        .requestMatchers(HttpMethod.GET, "/articles/**", "/articles").permitAll()
                        .requestMatchers("/public/ai/**").permitAll()
                        .requestMatchers("/customers/recalculate-spent-all").permitAll()

                        .requestMatchers(HttpMethod.GET, "/orders/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/promotions/active").permitAll()
                        .requestMatchers(HttpMethod.POST, "/promotions/validate").permitAll()
                        .requestMatchers(HttpMethod.GET, "/provinces").permitAll()

                        // ── STOREFRONT (CUSTOMER) ──────────────────────────────
                        // Customer cần tạo đơn hàng online và hủy đơn hàng
                        .requestMatchers(HttpMethod.POST, "/orders", "/orders/*/cancel")
                        .hasAnyRole("CUSTOMER", "MANAGER", "ADMIN")
                        // Customer cần truy cập thông tin cá nhân & lịch sử mua hàng
                        .requestMatchers("/customers/me/**", "/customers/me")
                        .hasRole("CUSTOMER")

                        // ── MODULE 0: POS ──────────────────────────────────────
                        .requestMatchers("/pos/**")
                        .hasAnyRole("CASHIER", "MANAGER", "ADMIN")

                        // ── MODULE 1: INVENTORY ────────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/inventory/**")
                        .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                        .requestMatchers("/inventory/**")
                        .hasAnyRole("MANAGER", "ADMIN")

                        // ── MODULE 2: PURCHASE ORDERS ──────────────────────────
                        .requestMatchers("/purchase-orders/**")
                        .hasAnyRole("MANAGER", "ADMIN")

                        // ── MODULE 3: TRANSFERS ────────────────────────────────
                        .requestMatchers("/transfers/**")
                        .hasAnyRole("MANAGER", "ADMIN")

                        // ── MODULE 4: E-COMMERCE & ORDERS ─────────────────────
                        .requestMatchers(HttpMethod.GET, "/orders/**")
                        .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/orders/**")
                        .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                        .requestMatchers("/orders/**")
                        .hasAnyRole("MANAGER", "ADMIN")

                        // ── MODULE 5: CRM ──────────────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/customers/**")
                        .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/customers")
                        .hasAnyRole("CASHIER", "MANAGER", "ADMIN")
                        // Các thao tác nhạy cảm khác như sửa, xóa (PUT, DELETE) thì chỉ Manager, Admin
                        // làm
                        .requestMatchers("/customers/**")
                        .hasAnyRole("MANAGER", "ADMIN")

                        // ── MODULE 6: FINANCE ──────────────────────────────────
                        .requestMatchers("/finance/**")
                        .hasAnyRole("MANAGER", "ADMIN")

                        // ── MODULE 7: REPORTS ──────────────────────────────────
                        .requestMatchers("/reports/**")
                        .hasAnyRole("CASHIER", "MANAGER", "ADMIN")

                        // ── MODULE 8: ADMIN SETTINGS ───────────────────────────
                        .requestMatchers("/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/warehouses/**")
                        .hasAnyRole("MANAGER", "ADMIN")
                        // Các thao tác Thêm/Sửa/Xóa (POST, PUT, PATCH) vẫn bị chặn chỉ cho ADMIN
                        .requestMatchers("/warehouses/**")
                        .hasRole("ADMIN")

                        // ── MODULE AI ──────────────────────────────────────────
                        .requestMatchers("/ai/**")
                        .hasAnyRole("MANAGER", "ADMIN")

                        // ── NOTIFICATIONS ──────────────────────────────────────
                        .requestMatchers("/notifications/**")
                        .hasAnyRole("CASHIER", "MANAGER", "ADMIN")

                        // ── MODULE REVIEWS ─────────────────────────────────────
                        .requestMatchers(HttpMethod.POST, "/reviews")
                        .hasRole("CUSTOMER")
                        .requestMatchers("/reviews", "/reviews/**")
                        .hasAnyRole("MANAGER", "ADMIN")

                        // ── MODULE UPLOAD ──────────────────────────────────────
                        .requestMatchers("/upload/**")
                        .hasAnyRole("MANAGER", "ADMIN", "CUSTOMER")

                        // ── MODULE AUTHORS ─────────────────────────────────────
                        .requestMatchers("/authors", "/authors/**")
                        .hasAnyRole("MANAGER", "ADMIN")

                        // ── MODULE BANNERS ─────────────────────────────────────
                        .requestMatchers("/banners", "/banners/**")
                        .hasAnyRole("MANAGER", "ADMIN")
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/swagger-resources/**",
                                "/webjars/**")
                        .permitAll()
                        .anyRequest().authenticated())

                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
