package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisTokenService {

    private final StringRedisTemplate redisTemplate;

    // Constants for Redis Keys
    public static final String KEY_BLACKLIST_JTI = "auth:blacklist:jti:";
    public static final String KEY_FORGOT_PWD_OTP = "auth:forgot_pwd:otp:";
    public static final String KEY_RATE_LIMIT_EMAIL = "rl:forgot_pwd:email:";
    public static final String KEY_RATE_LIMIT_IP = "rl:forgot_pwd:ip:";
    public static final String KEY_LOGIN_ATTEMPTS_USERNAME = "auth:login_attempts:username:";
    public static final String KEY_LOGIN_LOCKOUT_USERNAME = "auth:lockout:username:";

    // Configuration
    public static final long OTP_TTL_MINUTES = 10;
    public static final long RATE_LIMIT_TTL_MINUTES = 15;
    public static final int MAX_REQUESTS_PER_15_MIN = 3;
    public static final int MAX_FAILED_ATTEMPTS = 5;

    // SHA-256 Hashing for OTP
    public String hashOtp(String otp, String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String salt = email.toLowerCase().trim();
            String input = otp + salt;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    // --- Blacklist Operations ---
    public void blacklistJti(String jti, long remainingTtlMs) {
        if (jti == null || remainingTtlMs <= 0) return;
        try {
            redisTemplate.opsForValue().set(KEY_BLACKLIST_JTI + jti, "true", remainingTtlMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Redis down or error while blacklisting JTI {}: {}", jti, e.getMessage());
            // Fail Open for filter/background ops: do not throw
        }
    }

    public boolean isJtiBlacklisted(String jti) {
        if (jti == null) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_BLACKLIST_JTI + jti));
        } catch (Exception e) {
            log.warn("Redis down or error while checking JTI blacklist {}: {}", jti, e.getMessage());
            // Fail Open: if Redis is down, allow the request to pass the filter
            return false;
        }
    }

    // --- OTP Operations ---
    public void saveOtp(String email, String rawOtp) {
        try {
            String hashedOtp = hashOtp(rawOtp, email);
            redisTemplate.opsForValue().set(KEY_FORGOT_PWD_OTP + email, hashedOtp, OTP_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis down or error while saving OTP for email {}: {}", email, e.getMessage());
            throw new RuntimeException("Service Unavailable: Could not save OTP due to Redis issue"); // Fail Closed
        }
    }

    public boolean validateAndRemoveOtp(String email, String rawOtp) {
        try {
            String hashedOtp = hashOtp(rawOtp, email);
            String storedHash = redisTemplate.opsForValue().get(KEY_FORGOT_PWD_OTP + email);
            
            if (storedHash != null && storedHash.equals(hashedOtp)) {
                redisTemplate.delete(KEY_FORGOT_PWD_OTP + email);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Redis down or error while validating OTP for email {}: {}", email, e.getMessage());
            throw new RuntimeException("Service Unavailable: Could not validate OTP due to Redis issue"); // Fail Closed
        }
    }

    // --- Rate Limit Operations ---
    public boolean checkAndIncrementRateLimit(String email, String ip) {
        try {
            String emailKey = KEY_RATE_LIMIT_EMAIL + email;
            String ipKey = KEY_RATE_LIMIT_IP + ip;

            Long emailCount = redisTemplate.opsForValue().increment(emailKey);
            if (emailCount != null && emailCount == 1) {
                redisTemplate.expire(emailKey, RATE_LIMIT_TTL_MINUTES, TimeUnit.MINUTES);
            }

            Long ipCount = redisTemplate.opsForValue().increment(ipKey);
            if (ipCount != null && ipCount == 1) {
                redisTemplate.expire(ipKey, RATE_LIMIT_TTL_MINUTES, TimeUnit.MINUTES);
            }

            if (emailCount != null && emailCount > MAX_REQUESTS_PER_15_MIN) return false;
            if (ipCount != null && ipCount > MAX_REQUESTS_PER_15_MIN) return false;

            return true;
        } catch (Exception e) {
            log.warn("Redis down or error while rate limiting: {}", e.getMessage());
            throw new RuntimeException("Service Unavailable: Rate limiting failed due to Redis issue"); // Fail Closed
        }
    }

    // --- Account Lockout Operations ---
    public long calculateLockoutDuration(int lockoutCount) {
        // Lần 1 (Sai 5 lần): Khóa 5 phút.
        // Lần 2 (Sai thêm 5 lần nữa): Khóa 15 phút.
        // Lần 3 trở đi: Khóa 1 giờ.
        if (lockoutCount <= 1) return 5;
        if (lockoutCount == 2) return 15;
        return 60;
    }

    public int recordFailedAttempt(String username) {
        try {
            String attemptKey = KEY_LOGIN_ATTEMPTS_USERNAME + username;
            Long attempts = redisTemplate.opsForValue().increment(attemptKey);
            
            // Đặt thời gian reset cho đếm số lần sai (ví dụ 24h)
            if (attempts != null && attempts == 1) {
                redisTemplate.expire(attemptKey, 24, TimeUnit.HOURS);
            }

            if (attempts != null && attempts % MAX_FAILED_ATTEMPTS == 0) {
                int lockoutCount = (int) (attempts / MAX_FAILED_ATTEMPTS);
                long lockoutMinutes = calculateLockoutDuration(lockoutCount);
                String lockoutKey = KEY_LOGIN_LOCKOUT_USERNAME + username;
                redisTemplate.opsForValue().set(lockoutKey, "LOCKED", lockoutMinutes, TimeUnit.MINUTES);
                log.warn("Account {} locked for {} minutes due to {} failed attempts.", username, lockoutMinutes, attempts);
            }
            return attempts != null ? attempts.intValue() : 0;
        } catch (Exception e) {
            log.warn("Redis down while recording failed attempt for {}: {}", username, e.getMessage());
            // Fail Open: Không throw exception
            return 0;
        }
    }

    public boolean isLockedOut(String username) {
        try {
            String lockoutKey = KEY_LOGIN_LOCKOUT_USERNAME + username;
            return Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey));
        } catch (Exception e) {
            log.warn("Redis down while checking lockout for {}: {}", username, e.getMessage());
            // Fail Open: Nếu Redis sập, bỏ qua check lockout, cho phép tiến hành login
            return false;
        }
    }

    public long getLockoutRemainingMinutes(String username) {
        try {
            String lockoutKey = KEY_LOGIN_LOCKOUT_USERNAME + username;
            Long expire = redisTemplate.getExpire(lockoutKey, TimeUnit.MINUTES);
            return (expire != null && expire > 0) ? expire : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    public void clearFailedAttempts(String username) {
        try {
            redisTemplate.delete(KEY_LOGIN_ATTEMPTS_USERNAME + username);
            redisTemplate.delete(KEY_LOGIN_LOCKOUT_USERNAME + username);
        } catch (Exception e) {
            log.warn("Redis down while clearing attempts for {}: {}", username, e.getMessage());
            // Fail Open
        }
    }

    public void unlockUser(String username) {
        clearFailedAttempts(username);
    }

    public String getAndDeleteOAuth2Code(String code) {
        try {
            String key = "oauth2:code:" + code;
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                redisTemplate.delete(key);
            }
            return value;
        } catch (Exception e) {
            log.warn("Redis error while getting OAuth2 code {}: {}", code, e.getMessage());
            return null;
        }
    }
}
