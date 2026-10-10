package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.model.SystemSettings;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.repository.SystemSettingsRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "${frontend.url}")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SystemSettingsRepository systemSettingsRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ActivityLogService activityLogService;

    // ==========================================
    // 🚀 HELPER: DYNAMICALLY FIND ADMIN ID
    // ==========================================
    private Long getAdminId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null && (user.getRole().equalsIgnoreCase("CPDO Admin") || user.getRole().equalsIgnoreCase("Admin")))
                .map(User::getId)
                .findFirst()
                .orElse(1L);
    }

    // ==========================================
    // 1. IN-MEMORY THROTTLE (FOR UNKNOWN USERS ONLY)
    // ==========================================
    private static class LoginAttemptTracker {
        int attempts = 0;
        LocalDateTime lockoutTime = null;
    }
    private static final Map<String, LoginAttemptTracker> loginTracker = new ConcurrentHashMap<>();

    // ==========================================
    // 2. MFA & OTP TRACKER
    // ==========================================
    private static class MfaSession {
        String otp;
        LocalDateTime expiryTime;

        public MfaSession(String otp, LocalDateTime expiryTime) {
            this.otp = otp;
            this.expiryTime = expiryTime;
        }
    }
    private static final Map<Long, MfaSession> mfaTracker = new ConcurrentHashMap<>();
    private static final Map<Long, MfaSession> resetTracker = new ConcurrentHashMap<>();

    // ==========================================
    // 🕒 SERVER-SIDE SESSION LIFECYCLE TRACKER
    // ==========================================
    private static class ActiveSession {
        LocalDateTime loginTime;
        LocalDateTime lastActiveTime;

        public ActiveSession(LocalDateTime loginTime, LocalDateTime lastActiveTime) {
            this.loginTime = loginTime;
            this.lastActiveTime = lastActiveTime;
        }
    }
    private static final Map<Long, ActiveSession> activeSessionTracker = new ConcurrentHashMap<>();

    // ==========================================
    // 3. CREDENTIALS & PROGRESSIVE LOCKOUT
    // ==========================================
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials, HttpServletRequest request) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (loginTracker.size() > 2000) {
            loginTracker.clear();
        }

        Optional<User> userOpt = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username));

        // BRANCH A: USER DOES NOT EXIST
        if (userOpt.isEmpty()) {
            LoginAttemptTracker tracker = loginTracker.computeIfAbsent(username, k -> new LoginAttemptTracker());

            if (tracker.lockoutTime != null && LocalDateTime.now().isBefore(tracker.lockoutTime)) {
                return ResponseEntity.status(429).body(Map.of("error", "Too many attempts. Account temporarily rate-limited for security."));
            }

            tracker.attempts++;
            if (tracker.attempts > 5) {
                long penalty = Math.min((long) Math.pow(2, tracker.attempts - 5), 900);
                tracker.lockoutTime = LocalDateTime.now().plusSeconds(penalty);
            }

            activityLogService.log(null, "AUTH", "AUTH_LOGIN_FAILED", username, "Failed login attempt for unknown username: '" + username + "'", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password."));
        }

        // BRANCH B: USER EXISTS
        User user = userOpt.get();
        String formattedUserId = String.format("#USR-%04d", user.getId());

        if (user.getLockoutUntil() != null && LocalDateTime.now().isBefore(user.getLockoutUntil())) {
            activityLogService.log(null, "AUTH", "AUTH_ACCOUNT_LOCKED", formattedUserId, "Blocked login attempt for '" + username + "' during active progressive lockout penalty.", "WARNING", request);
            return ResponseEntity.status(429).body(Map.of("error", "Too many failed attempts. Account temporarily rate-limited for security."));
        }

        if (!user.getPassword().equals(password)) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);

            if (attempts > 5) {
                long penaltySeconds = (long) Math.pow(2, attempts - 5);
                penaltySeconds = Math.min(penaltySeconds, 900);
                user.setLockoutUntil(LocalDateTime.now().plusSeconds(penaltySeconds));

                if (attempts == 6 || attempts == 15) {
                    notificationService.sendNotification(
                            getAdminId(),
                            "Security Alert: Brute-Force Activity",
                            "Progressive lockout triggered for user: '" + username + "' due to " + attempts + " consecutive failed attempts.",
                            "SECURITY"
                    );
                }
            }

            userRepository.save(user);
            activityLogService.log(null, "AUTH", "AUTH_LOGIN_FAILED", formattedUserId, "Failed login attempt for '" + username + "'. Total consecutive failures: " + attempts, "FAILED", request);

            if (attempts > 5) {
                return ResponseEntity.status(429).body(Map.of("error", "Too many failed attempts. Account temporarily rate-limited for security."));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password."));
        }

        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);
        userRepository.save(user);
        loginTracker.remove(username);

        SystemSettings settings = systemSettingsRepository.findById(1L).orElse(null);
        if (settings != null && settings.isMaintenanceMode()) {
            if (!user.getRole().equalsIgnoreCase("Admin") && !user.getRole().equalsIgnoreCase("CPDO Admin")) {
                activityLogService.log(user, "AUTH", "AUTH_MAINTENANCE_BLOCKED", formattedUserId, "User attempted login while system was under maintenance mode.", "WARNING", request);
                return ResponseEntity.status(403).body(Map.of("error", "System is currently down for maintenance and updates. Please try again later.", "type", "MAINTENANCE_MODE"));
            }
        }

        if ("Suspended".equalsIgnoreCase(user.getStatus())) {
            activityLogService.log(user, "AUTH", "AUTH_SUSPENDED_BLOCKED", formattedUserId, "Suspended account attempted login.", "FAILED", request);
            return ResponseEntity.status(403).body(Map.of("error", "Your account is currently Suspended. Please contact the CPDO."));
        }

        if ("Deactivated".equalsIgnoreCase(user.getStatus())) {
            activityLogService.log(user, "AUTH", "AUTH_DEACTIVATED_BLOCKED", formattedUserId, "Deactivated account attempted login.", "FAILED", request);
            return ResponseEntity.status(403).body(Map.of("error", "Your account has been Deactivated. Access revoked."));
        }

        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "No official email is linked to this account. Cannot proceed with MFA. Contact Admin."));
        }

        String otp = String.format("%06d", new Random().nextInt(999999));
        mfaTracker.put(user.getId(), new MfaSession(otp, LocalDateTime.now().plusMinutes(5)));

        System.out.println("=================================================");
        System.out.println(">>> [AUTH] MFA CODE FOR " + user.getUsername() + ": " + otp);
        System.out.println(">>> [AUTH] RECIPIENT EMAIL: " + user.getEmail());
        System.out.println("=================================================");

        String subject = "RoadWise - Login Verification Code";
        String body = "Hello " + user.getFirstName() + ",\n\n" +
                "Your Multi-Factor Authentication (MFA) code is: " + otp + "\n\n" +
                "This code will expire in 5 minutes. Do not share this code with anyone.\n\n" +
                "If you did not attempt to log in, please contact the CPDO Administrator immediately.";

        try {
            emailService.sendEmail(user.getEmail(), subject, body);
        } catch (Exception e) {
            System.err.println(">>> [WARN] SMTP Delivery failed. Use terminal OTP above. Error: " + e.getMessage());
        }

        activityLogService.log(user, "AUTH", "AUTH_MFA_REQUESTED", formattedUserId, "Credentials validated. 6-digit MFA OTP generated and dispatched.", "SUCCESS", request);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("mfaRequired", true);
        responseData.put("userId", user.getId());
        responseData.put("message", "A 6-digit code has been sent to your email.");
        responseData.put("demoOtp", otp);

        return ResponseEntity.ok(responseData);
    }

    // ==========================================
    // 4. VERIFY OTP & GRANT ACCESS
    // ==========================================
    @PostMapping("/verify-mfa")
    public ResponseEntity<?> verifyMfa(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        Long userId = Long.valueOf(payload.get("userId").toString());
        String submittedOtp = payload.get("otp").toString();

        MfaSession session = mfaTracker.get(userId);

        if (session == null) {
            return ResponseEntity.status(400).body(Map.of("error", "No active login session found. Please go back and log in again."));
        }

        if (LocalDateTime.now().isAfter(session.expiryTime)) {
            mfaTracker.remove(userId);
            return ResponseEntity.status(400).body(Map.of("error", "Verification code expired. Please go back and log in again."));
        }

        if (!session.otp.equals(submittedOtp)) {
            User attemptedUser = userRepository.findById(userId).orElse(null);
            activityLogService.log(attemptedUser, "AUTH", "AUTH_MFA_FAILED", String.format("#USR-%04d", userId), "Invalid MFA verification code entered.", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid verification code. Please try again."));
        }

        mfaTracker.remove(userId);

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found."));
        }
        User user = userOpt.get();

        // 🕒 Initialize active server-side session clock
        activeSessionTracker.put(user.getId(), new ActiveSession(LocalDateTime.now(), LocalDateTime.now()));

        activityLogService.log(user, "AUTH", "AUTH_LOGIN_SUCCESS", String.format("#USR-%04d", user.getId()), "User successfully verified MFA and established active session for " + user.getRole() + " role.", "SUCCESS", request);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("userId", user.getId());
        responseData.put("username", user.getUsername());
        responseData.put("role", user.getRole());
        responseData.put("firstName", user.getFirstName());
        responseData.put("middleName", user.getMiddleName());
        responseData.put("lastName", user.getLastName());
        responseData.put("email", user.getEmail());
        responseData.put("phoneNumber", user.getPhoneNumber());
        responseData.put("birthday", user.getBirthday());
        responseData.put("gender", user.getGender());
        responseData.put("profilePicture", user.getProfilePicture());

        if (user.getBarangay() != null) {
            responseData.put("barangayId", user.getBarangay().getId());
            responseData.put("barangayName", user.getBarangay().getBarangayName());
        } else {
            responseData.put("barangayId", null);
            responseData.put("barangayName", "City Hall Central");
        }

        return ResponseEntity.ok(responseData);
    }

    // ==========================================
    // 5. FORGOT PASSWORD REQUEST OTP
    // ==========================================
    @PostMapping("/forgot-password/request")
    public ResponseEntity<?> requestPasswordReset(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        String email = payload.get("email");

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "If this email exists, a recovery code will be sent shortly."));
        }

        User user = userOpt.get();
        String formattedUserId = String.format("#USR-%04d", user.getId());

        String otp = String.format("%06d", new Random().nextInt(999999));
        resetTracker.put(user.getId(), new MfaSession(otp, LocalDateTime.now().plusMinutes(10)));

        System.out.println("=================================================");
        System.out.println(">>> [PASSWORD RESET] RECOVERY CODE FOR " + user.getUsername() + " (" + user.getEmail() + "): " + otp);
        System.out.println("=================================================");

        String subject = "RoadWise - Password Reset Code";
        String body = "Hello " + user.getFirstName() + ",\n\n" +
                "You requested a password reset for your RoadWise account.\n\n" +
                "Your 6-digit Password Reset Code is: " + otp + "\n\n" +
                "This code will expire in 10 minutes.\n\n" +
                "If you did not request this, please ignore this email and your password will remain unchanged.";

        try {
            emailService.sendEmail(user.getEmail(), subject, body);
        } catch (Exception e) {
            System.err.println(">>> [WARN] Password Reset SMTP Delivery failed. Use terminal OTP above. Error: " + e.getMessage());
        }

        notificationService.sendNotification(
                getAdminId(),
                "Support Request",
                user.getRole() + " " + user.getFirstName() + " " + user.getLastName() + " has requested a password reset. System has dispatched recovery email.",
                "SUPPORT"
        );

        activityLogService.log(user, "AUTH", "AUTH_PASSWORD_RESET_REQUEST", formattedUserId, "Password recovery OTP dispatched to email address: " + user.getEmail(), "SUCCESS", request);

        return ResponseEntity.ok(Map.of("message", "A 6-digit recovery code has been sent to your email.", "userId", user.getId()));
    }

    // ==========================================
    // 6. FORGOT PASSWORD VERIFY & RESET
    // ==========================================
    @PostMapping("/forgot-password/reset")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        Long userId = Long.valueOf(payload.get("userId").toString());
        String submittedOtp = payload.get("otp").toString();
        String newPassword = payload.get("newPassword").toString();

        MfaSession session = resetTracker.get(userId);

        if (session == null) {
            return ResponseEntity.status(400).body(Map.of("error", "No active password reset session. Please request a new code."));
        }

        if (LocalDateTime.now().isAfter(session.expiryTime)) {
            resetTracker.remove(userId);
            return ResponseEntity.status(400).body(Map.of("error", "Reset code has expired. Please request a new one."));
        }

        if (!session.otp.equals(submittedOtp)) {
            User attemptedUser = userRepository.findById(userId).orElse(null);
            activityLogService.log(attemptedUser, "AUTH", "AUTH_PASSWORD_RESET_FAILED", String.format("#USR-%04d", userId), "Invalid password reset recovery OTP submitted.", "FAILED", request);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid recovery code. Please try again."));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found."));
        }

        User user = userOpt.get();
        user.setPassword(newPassword);

        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);

        userRepository.save(user);
        resetTracker.remove(userId);

        activityLogService.log(user, "AUTH", "AUTH_PASSWORD_RESET_SUCCESS", String.format("#USR-%04d", user.getId()), "Password credentials successfully updated via recovery verification.", "SUCCESS", request);

        return ResponseEntity.ok(Map.of("message", "Password successfully reset! You can now log in."));
    }

    // ==========================================
    // 7. SERVER-SIDE SESSION VERIFICATION & TIMEOUT AUDIT
    // ==========================================
    @PostMapping("/verify-session")
    public ResponseEntity<?> verifySession(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        Object rawId = payload.get("userId");
        String claimedRole = (String) payload.get("role");

        if (rawId == null || claimedRole == null) {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Missing session identity."));
        }

        Long userId;
        try {
            userId = Long.valueOf(rawId.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "Invalid user ID format."));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "User record no longer exists."));
        }

        User user = userOpt.get();

        if ("Suspended".equalsIgnoreCase(user.getStatus()) || "Deactivated".equalsIgnoreCase(user.getStatus())) {
            activeSessionTracker.remove(userId);
            return ResponseEntity.status(403).body(Map.of("valid", false, "error", "Account access revoked."));
        }

        if (!user.getRole().equalsIgnoreCase(claimedRole)) {
            activeSessionTracker.remove(userId);
            activityLogService.log(
                    user,
                    "SECURITY",
                    "ROLE_SPOOF_ATTEMPT",
                    String.format("#USR-%04d", user.getId()),
                    "Client role mismatch detected. Claimed: '" + claimedRole + "', Actual DB: '" + user.getRole() + "'.",
                    "WARNING",
                    request
            );
            return ResponseEntity.status(403).body(Map.of("valid", false, "error", "Role mismatch detected."));
        }

        // 🕒 TIMEOUT AUDIT: 15-Min Inactivity & 8-Hour Absolute Shift Ceiling
        LocalDateTime now = LocalDateTime.now();
        ActiveSession session = activeSessionTracker.get(userId);

        if (session != null) {
            // Absolute check: 8 Hours
            if (Duration.between(session.loginTime, now).toHours() >= 8) {
                activeSessionTracker.remove(userId);
                activityLogService.log(user, "AUTH", "AUTH_SESSION_EXPIRED", String.format("#USR-%04d", user.getId()), "Session expired: Reached 8-hour maximum shift limit.", "INFO", request);
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "EXPIRED_ABSOLUTE", "message", "Your 8-hour shift session has ended. Please log in again."));
            }

            // Inactivity check: 15 Minutes
            if (Duration.between(session.lastActiveTime, now).toMinutes() >= 15) {
                activeSessionTracker.remove(userId);
                activityLogService.log(user, "AUTH", "AUTH_SESSION_TIMEOUT", String.format("#USR-%04d", user.getId()), "Session expired: Inactive for 15 minutes.", "INFO", request);
                return ResponseEntity.status(401).body(Map.of("valid", false, "error", "EXPIRED_IDLE", "message", "Session timed out due to 15 minutes of inactivity."));
            }

            // Active request received: advance sliding idle clock
            session.lastActiveTime = now;
        } else {
            // Cold restart recovery: initialize clock
            activeSessionTracker.put(userId, new ActiveSession(now, now));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("valid", true);
        result.put("role", user.getRole());
        result.put("status", user.getStatus());

        return ResponseEntity.ok(result);
    }

    // ==========================================
    // 8. SESSION LOGOUT & CLEANUP
    // ==========================================
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody(required = false) Map<String, Object> payload, HttpServletRequest request) {
        if (payload != null && payload.get("userId") != null) {
            try {
                Long userId = Long.valueOf(payload.get("userId").toString());
                activeSessionTracker.remove(userId);
                userRepository.findById(userId).ifPresent(user ->
                        activityLogService.log(user, "AUTH", "AUTH_LOGOUT", String.format("#USR-%04d", user.getId()), "User terminated session.", "SUCCESS", request)
                );
            } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully."));
    }
}