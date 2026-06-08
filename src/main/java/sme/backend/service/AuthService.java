package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import sme.backend.dto.request.ChangePasswordRequest;
import sme.backend.dto.request.CreateUserRequest;
import sme.backend.dto.request.LoginRequest;
import sme.backend.dto.response.AuthResponse;
import sme.backend.dto.response.UserResponse;
import sme.backend.entity.User;
import sme.backend.entity.Warehouse;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.UserRepository;
import sme.backend.repository.WarehouseRepository;
import sme.backend.repository.ShiftRepository;
import sme.backend.repository.CustomerRepository;
import sme.backend.security.UserPrincipal;
import sme.backend.security.jwt.JwtTokenProvider;
import org.springframework.data.domain.Sort;
import sme.backend.entity.Customer;
import sme.backend.dto.request.CustomerRegisterRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final WarehouseRepository warehouseRepository;
    private final ShiftRepository shiftRepository;
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RedisTokenService redisTokenService;

    @Autowired(required = false)
    private SecurityAuditService securityAuditService;

    // TTL Constants
    private static final long ADMIN_TTL = 10800000L; // 3 tiếng
    private static final long CUSTOMER_TTL = 604800000L; // 7 ngày

    private String getClientIp() {
        ServletRequestAttributes attribs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attribs != null) {
            HttpServletRequest request = attribs.getRequest();
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();
            }
            return ip;
        }
        return "UNKNOWN";
    }

    private String getUserAgent() {
        ServletRequestAttributes attribs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attribs != null) {
            return attribs.getRequest().getHeader("User-Agent");
        }
        return "UNKNOWN";
    }

    // ─────────────────────────────────────────────────────────
    // LOGIN CHO NHÂN VIÊN (POS & ADMIN)
    // ─────────────────────────────────────────────────────────
    public AuthResponse adminLogin(LoginRequest req) {
        String ip = getClientIp();
        String userAgent = getUserAgent();

        if (redisTokenService.isLockedOut(req.getUsername())) {
            // Fail Open comment: If Redis is down, isLockedOut returns false, allowing authentication to proceed (Fail Open).
            if (securityAuditService != null) {
                securityAuditService.logAccountLocked(req.getUsername(), ip, userAgent, "Blocked by Account Lockout policy");
            }
            long minutes = redisTokenService.getLockoutRemainingMinutes(req.getUsername());
            throw new BusinessException("ACCOUNT_LOCKED", "Tài khoản bị khóa do nhập sai nhiều lần. Vui lòng thử lại sau " + minutes + " phút.");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));
        } catch (org.springframework.security.core.AuthenticationException e) {
            int attempts = redisTokenService.recordFailedAttempt(req.getUsername());
            if (securityAuditService != null) {
                securityAuditService.logLoginFailed(req.getUsername(), ip, userAgent, "Invalid credentials");
            }
            if (attempts > 0) {
                int remaining = (RedisTokenService.MAX_FAILED_ATTEMPTS - (attempts % RedisTokenService.MAX_FAILED_ATTEMPTS)) % RedisTokenService.MAX_FAILED_ATTEMPTS;
                if (remaining == 0) remaining = RedisTokenService.MAX_FAILED_ATTEMPTS; // It just got locked, the lockout block will catch it next time, but for the immediate response say 0? No, if it's locked it will be 0.
                if (attempts % RedisTokenService.MAX_FAILED_ATTEMPTS == 0) {
                    long minutes = redisTokenService.getLockoutRemainingMinutes(req.getUsername());
                    throw new BusinessException("ACCOUNT_LOCKED", "Tài khoản bị khóa do nhập sai nhiều lần. Vui lòng thử lại sau " + minutes + " phút.");
                } else {
                    throw new BusinessException("BAD_CREDENTIALS", "Tên đăng nhập hoặc mật khẩu không đúng. Bạn còn " + remaining + " lần thử trước khi bị khóa.");
                }
            }
            throw new BusinessException("BAD_CREDENTIALS", "Tên đăng nhập hoặc mật khẩu không đúng.");
        }

        redisTokenService.clearFailedAttempts(req.getUsername());
        if (securityAuditService != null) {
            securityAuditService.logLoginSuccess(req.getUsername(), ip, userAgent);
        }

        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

        // Chặn khách hàng đăng nhập qua cổng Admin
        if (principal.getRole() == User.UserRole.ROLE_CUSTOMER) {
            throw new BusinessException("FORBIDDEN", "Khách hàng không được phép truy cập trang quản trị");
        }

        userRepository.findByUsernameAndIsActiveTrue(principal.getUsername())
                .ifPresent(u -> {
                    u.setLastLoginAt(Instant.now());
                    userRepository.save(u);
                });

        long ttl = ADMIN_TTL;
        String accessToken = jwtTokenProvider.generateAccessToken(principal, ttl);
        String refreshToken = jwtTokenProvider.generateRefreshToken(principal.getUsername());

        String warehouseName = null;
        if (principal.getWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(principal.getWarehouseId())
                    .map(Warehouse::getName).orElse(null);
        }

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(ttl)
                .user(toUserResponse(principal, warehouseName))
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // LOGIN CHO KHÁCH HÀNG (STOREFRONT)
    // ─────────────────────────────────────────────────────────
    public AuthResponse customerLogin(LoginRequest req) {
        String ip = getClientIp();
        String userAgent = getUserAgent();

        if (redisTokenService.isLockedOut(req.getUsername())) {
            if (securityAuditService != null) {
                securityAuditService.logAccountLocked(req.getUsername(), ip, userAgent, "Blocked by Account Lockout policy");
            }
            long minutes = redisTokenService.getLockoutRemainingMinutes(req.getUsername());
            throw new BusinessException("ACCOUNT_LOCKED", "Tài khoản bị khóa do nhập sai nhiều lần. Vui lòng thử lại sau " + minutes + " phút.");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));
        } catch (org.springframework.security.core.AuthenticationException e) {
            int attempts = redisTokenService.recordFailedAttempt(req.getUsername());
            if (securityAuditService != null) {
                securityAuditService.logLoginFailed(req.getUsername(), ip, userAgent, "Invalid credentials");
            }
            if (attempts > 0) {
                int remaining = (RedisTokenService.MAX_FAILED_ATTEMPTS - (attempts % RedisTokenService.MAX_FAILED_ATTEMPTS)) % RedisTokenService.MAX_FAILED_ATTEMPTS;
                if (attempts % RedisTokenService.MAX_FAILED_ATTEMPTS == 0) {
                    long minutes = redisTokenService.getLockoutRemainingMinutes(req.getUsername());
                    throw new BusinessException("ACCOUNT_LOCKED", "Tài khoản bị khóa do nhập sai nhiều lần. Vui lòng thử lại sau " + minutes + " phút.");
                } else {
                    throw new BusinessException("BAD_CREDENTIALS", "Tên đăng nhập hoặc mật khẩu không đúng. Bạn còn " + remaining + " lần thử trước khi bị khóa.");
                }
            }
            throw new BusinessException("BAD_CREDENTIALS", "Tên đăng nhập hoặc mật khẩu không đúng.");
        }

        redisTokenService.clearFailedAttempts(req.getUsername());
        if (securityAuditService != null) {
            securityAuditService.logLoginSuccess(req.getUsername(), ip, userAgent);
        }

        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

        // Chặn nhân viên đăng nhập qua cổng Khách hàng
        if (principal.getRole() != User.UserRole.ROLE_CUSTOMER) {
            throw new BusinessException("FORBIDDEN",
                    "Tài khoản nhân viên không đăng nhập qua cổng Khách hàng. Vui lòng vào trang quản trị.");
        }

        userRepository.findByUsernameAndIsActiveTrue(principal.getUsername())
                .ifPresent(u -> {
                    u.setLastLoginAt(Instant.now());
                    userRepository.save(u);
                });

        long ttl = CUSTOMER_TTL;
        String accessToken = jwtTokenProvider.generateAccessToken(principal, ttl);
        String refreshToken = jwtTokenProvider.generateRefreshToken(principal.getUsername());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(ttl)
                .user(toUserResponse(principal, null))
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // REFRESH TOKEN
    // ─────────────────────────────────────────────────────────
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BusinessException("INVALID_TOKEN", "Refresh token không hợp lệ hoặc đã hết hạn");
        }
        String jti = jwtTokenProvider.getJtiFromToken(refreshToken);
        if (redisTokenService.isJtiBlacklisted(jti)) {
            throw new BusinessException("INVALID_TOKEN", "Refresh token đã bị vô hiệu hóa (logged out)");
        }
        
        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);

        User user = userRepository.findByUsernameAndIsActiveTrue(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));

        UserPrincipal principal = UserPrincipal.build(user);
        long ttl = user.getRole() == User.UserRole.ROLE_CUSTOMER ? CUSTOMER_TTL : ADMIN_TTL;
        String newAccessToken = jwtTokenProvider.generateAccessToken(principal, ttl);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(username);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(ttl)
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // OAUTH2 TOKEN EXCHANGE
    // ─────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse exchangeOAuth2Token(String code) {
        String value = redisTokenService.getAndDeleteOAuth2Code(code);
        if (value == null) {
            throw new BusinessException("INVALID_CODE", "Mã xác thực không hợp lệ hoặc đã hết hạn");
        }

        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            throw new BusinessException("INVALID_CODE", "Dữ liệu mã xác thực bị lỗi");
        }

        String accessToken = parts[0];
        String userIdStr = parts[1];

        User user = userRepository.findById(UUID.fromString(userIdStr))
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Không tìm thấy tài khoản"));

        UserPrincipal principal = UserPrincipal.build(user);

        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getUsername());
        long ttl = user.getRole() == User.UserRole.ROLE_CUSTOMER ? CUSTOMER_TTL : ADMIN_TTL;

        String warehouseName = null;
        if (user.getWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(user.getWarehouseId())
                    .map(Warehouse::getName).orElse(null);
        }

        if (securityAuditService != null) {
            securityAuditService.logEvent(user.getUsername(), "OAUTH2_LOGIN_SUCCESS", getClientIp(), getUserAgent(), "User logged in via OAuth2");
        }

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(ttl)
                .user(toUserResponse(principal, warehouseName))
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // SWITCH BRANCH (ADMIN ONLY)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse switchBranch(UserPrincipal principal, UUID newWarehouseId) {
        if (principal.getRole() != User.UserRole.ROLE_ADMIN) {
            throw new BusinessException("FORBIDDEN", "Chỉ Admin mới có thể chuyển chi nhánh");
        }

        if (newWarehouseId != null) {
            Warehouse warehouse = warehouseRepository.findById(newWarehouseId)
                    .orElseThrow(() -> new BusinessException("WAREHOUSE_NOT_FOUND", "Chi nhánh không tồn tại"));
            if (!warehouse.getIsActive()) {
                throw new BusinessException("WAREHOUSE_INACTIVE", "Chi nhánh đã ngừng hoạt động");
            }
        }



        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", principal.getId()));

        user.setWarehouseId(newWarehouseId);
        user = userRepository.save(user);

        UserPrincipal newPrincipal = UserPrincipal.build(user);
        long ttl = user.getRole() == User.UserRole.ROLE_CUSTOMER ? CUSTOMER_TTL : ADMIN_TTL;
        String newAccessToken = jwtTokenProvider.generateAccessToken(newPrincipal, ttl);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getUsername());

        String warehouseName = null;
        if (newPrincipal.getWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(newPrincipal.getWarehouseId())
                    .map(Warehouse::getName).orElse(null);
        }

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(ttl)
                .user(toUserResponse(newPrincipal, warehouseName))
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // CUSTOMER REGISTRATION
    // ─────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse registerCustomer(CustomerRegisterRequest req) {
        if (userRepository.existsByUsername(req.getPhone())) {
            throw new BusinessException("DUPLICATE_PHONE", "Số điện thoại đã được đăng ký tài khoản");
        }

        // Tạo User account cho Customer
        User user = User.builder()
                .username(req.getPhone())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .fullName(req.getFullName())
                .email(req.getEmail())
                .phone(req.getPhone())
                .role(User.UserRole.ROLE_CUSTOMER)
                .isActive(true)
                .build();
        user = userRepository.save(user);

        // Check xem có CRM profile cũ (Khách Offline) chưa?
        final User finalUser = user;
        customerRepository.findByPhoneNumber(req.getPhone())
                .ifPresentOrElse(
                        existingCustomer -> {
                            // Link User ID vào Customer cũ
                            existingCustomer.setUserId(finalUser.getId());
                            customerRepository.save(existingCustomer);
                            log.info("Linked new online user {} to existing CRM customer {}", finalUser.getId(),
                                    existingCustomer.getId());
                        },
                        () -> {
                            // Tạo Customer mới
                            Customer customer = Customer.builder()
                                    .userId(finalUser.getId())
                                    .phoneNumber(req.getPhone())
                                    .fullName(req.getFullName())
                                    .email(req.getEmail())
                                    .acquisitionChannel(Customer.AcquisitionChannel.ONLINE)
                                    .isActive(true)
                                    .build();
                            customerRepository.save(customer);
                            log.info("Created new CRM customer for online user {}", finalUser.getId());
                        });

        // Tự động Login sau khi đăng ký
        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername(req.getPhone());
        loginReq.setPassword(req.getPassword());
        return customerLogin(loginReq);
    }

    // ─────────────────────────────────────────────────────────
    // USER MANAGEMENT (ADMIN)
    // ─────────────────────────────────────────────────────────
    @Transactional
    public UserResponse createUser(CreateUserRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BusinessException("DUPLICATE_USERNAME",
                    "Tên đăng nhập '" + req.getUsername() + "' đã tồn tại");
        }

        // Validate username: chỉ cho phép chữ không dấu, số, dấu chấm, gạch dưới
        if (!req.getUsername().matches("^[a-zA-Z0-9._]+$")) {
            throw new BusinessException("INVALID_USERNAME",
                    "Tên đăng nhập chỉ được chứa chữ cái không dấu, số, dấu chấm và gạch dưới");
        }

        User.UserRole role;
        try {
            role = User.UserRole.valueOf(req.getRole());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ROLE",
                    "Role không hợp lệ: " + req.getRole());
        }

        if (role != User.UserRole.ROLE_ADMIN && req.getWarehouseId() == null) {
            throw new BusinessException("WAREHOUSE_REQUIRED",
                    "Manager và Cashier phải được gán vào một chi nhánh");
        }

        User user = User.builder()
                .username(req.getUsername())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .fullName(req.getFullName())
                .email(req.getEmail())
                .phone(req.getPhone())
                .role(role)
                .warehouseId(req.getWarehouseId())
                .isActive(true)
                .build();

        user = userRepository.save(user);
        log.info("Created user: {} with role: {}", user.getUsername(), user.getRole());
        return mapToResponse(user);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> searchUsers(String keyword, String roleStr, UUID warehouseId) {
        return userRepository.findAll(Sort.by("fullName")).stream()
                .filter(u -> keyword == null || keyword.isBlank() ||
                        u.getFullName().toLowerCase().contains(keyword.toLowerCase()) ||
                        u.getUsername().toLowerCase().contains(keyword.toLowerCase()) ||
                        (u.getEmail() != null && u.getEmail().toLowerCase().contains(keyword.toLowerCase())))
                .filter(u -> roleStr == null || roleStr.isBlank() || u.getRole().name().equals(roleStr))
                .filter(u -> warehouseId == null || warehouseId.equals(u.getWarehouseId()))
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public UserResponse updateUser(UUID id, CreateUserRequest req) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));

        if (req.getFullName() != null)
            user.setFullName(req.getFullName());
        if (req.getEmail() != null)
            user.setEmail(req.getEmail());
        if (req.getPhone() != null)
            user.setPhone(req.getPhone());
        if (req.getRole() != null)
            user.setRole(User.UserRole.valueOf(req.getRole()));
        if (req.getWarehouseId() != null)
            user.setWarehouseId(req.getWarehouseId());

        // ĐÃ BỔ SUNG: Cập nhật cài đặt POS
        if (req.getPosSettings() != null)
            user.setPosSettings(req.getPosSettings());

        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        }

        return mapToResponse(userRepository.save(user));
    }

    // Trong hàm mapToResponse thêm dòng này:
    // .posSettings(user.getPosSettings())

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("WRONG_PASSWORD", "Mật khẩu hiện tại không đúng");
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);
        
        // Fix 2: Auto unlock if the user changed password successfully
        redisTokenService.unlockUser(user.getUsername());

        if (securityAuditService != null) {
            securityAuditService.logEvent(user.getUsername(), "PASSWORD_CHANGED", getClientIp(), getUserAgent(), "User changed password via profile");
        }
    }

    @Transactional
    public UserResponse toggleUserActive(UUID userId, boolean active) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setIsActive(active);
        return mapToResponse(userRepository.save(user));
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAllActive().stream()
                .map(this::mapToResponse).toList();
    }

    // ─────────────────────────────────────────────────────────
    // FORGOT / RESET PASSWORD & LOGOUT
    // ─────────────────────────────────────────────────────────
    public void forgotPassword(String email, String ip) {
        if (!redisTokenService.checkAndIncrementRateLimit(email, ip)) {
            throw new BusinessException("RATE_LIMIT", "Bạn đã yêu cầu gửi quá nhiều mã OTP. Vui lòng thử lại sau 15 phút.");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "Không tìm thấy tài khoản với email này"));

        if (!user.getIsActive()) {
            throw new BusinessException("INACTIVE_USER", "Tài khoản đã bị khóa");
        }

        // Generate 6-digit OTP
        String otp = String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
        
        redisTokenService.saveOtp(email, otp);
        emailService.sendForgotPasswordEmail(email, otp);
    }

    @Transactional
    public void resetPassword(String email, String otp, String newPassword) {
        if (!redisTokenService.validateAndRemoveOtp(email, otp)) {
            throw new BusinessException("INVALID_OTP", "Mã OTP không hợp lệ hoặc đã hết hạn");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "Không tìm thấy tài khoản với email này"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Fix 1: Auto unlock if the user reset password successfully
        redisTokenService.unlockUser(user.getUsername());

        if (securityAuditService != null) {
            securityAuditService.logEvent(user.getUsername(), "PASSWORD_CHANGED", getClientIp(), getUserAgent(), "User reset password via forgot password flow");
        }
    }

    public void logout(String accessToken, String refreshToken) {
        String username = null;
        
        // Blacklist Access Token
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                if (jwtTokenProvider.validateToken(accessToken)) {
                    String jti = jwtTokenProvider.getJtiFromToken(accessToken);
                    java.util.Date expiration = jwtTokenProvider.getExpirationFromToken(accessToken);
                    
                    long remainingTimeMs = expiration.getTime() - System.currentTimeMillis();
                    if (remainingTimeMs > 0) {
                        redisTokenService.blacklistJti(jti, remainingTimeMs);
                    }
                    username = jwtTokenProvider.getUsernameFromToken(accessToken);
                }
            } catch (Exception e) {
                log.warn("Error processing access token during logout: {}", e.getMessage());
            }
        }
        
        // Blacklist Refresh Token
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                if (jwtTokenProvider.validateToken(refreshToken)) {
                    String jti = jwtTokenProvider.getJtiFromToken(refreshToken);
                    java.util.Date expiration = jwtTokenProvider.getExpirationFromToken(refreshToken);
                    
                    long remainingTimeMs = expiration.getTime() - System.currentTimeMillis();
                    if (remainingTimeMs > 0) {
                        redisTokenService.blacklistJti(jti, remainingTimeMs);
                    }
                    if (username == null) {
                        username = jwtTokenProvider.getUsernameFromToken(refreshToken);
                    }
                }
            } catch (Exception e) {
                log.warn("Error processing refresh token during logout: {}", e.getMessage());
            }
        }

        if (username != null && securityAuditService != null) {
            securityAuditService.logEvent(username, "LOGOUT", getClientIp(), getUserAgent(), "User logged out successfully");
        }
    }

    @Transactional
    public void unlockUser(String targetUsername, String adminIp, String adminUserAgent) {
        redisTokenService.unlockUser(targetUsername);
        if (securityAuditService != null) {
            securityAuditService.logAccountUnlocked(targetUsername, adminIp, adminUserAgent, "Admin unlocked this account manually");
        }
    }

    // ─────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────
    private UserResponse toUserResponse(UserPrincipal p, String warehouseName) {
        return UserResponse.builder()
                .id(p.getId())
                .username(p.getUsername())
                .fullName(p.getFullName())
                .role(p.getRole().name())
                .warehouseId(p.getWarehouseId())
                .warehouseName(warehouseName)
                .isActive(p.isEnabled())
                .build();
    }

    public UserResponse getUserResponseById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        return mapToResponse(user);
    }

    public UserResponse mapToResponse(User user) {
        String warehouseName = null;
        if (user.getWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(user.getWarehouseId())
                    .map(Warehouse::getName).orElse(null);
        }

        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole().name())
                .warehouseId(user.getWarehouseId())
                .warehouseName(warehouseName)
                .isActive(user.getIsActive())
                .isLocked(redisTokenService.isLockedOut(user.getUsername()))
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .posSettings(user.getPosSettings())
                .build();
    }

    public void validateManagerAccessToUser(UUID managerWarehouseId, UUID targetUserId) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", targetUserId));
        if (targetUser.getRole() == User.UserRole.ROLE_ADMIN || targetUser.getRole() == User.UserRole.ROLE_MANAGER) {
            throw new BusinessException("FORBIDDEN", "Quản lý không có quyền sửa đổi tài khoản Admin hoặc Manager khác");
        }
        if (managerWarehouseId == null || !managerWarehouseId.equals(targetUser.getWarehouseId())) {
            throw new BusinessException("FORBIDDEN", "Bạn chỉ có quyền quản lý nhân viên thuộc chi nhánh của mình");
        }
    }
}