package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "${frontend.url}")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.roadwise.backend.service.EmailService emailService;

    // 🚀 INJECTED NOTIFICATION SERVICE
    @Autowired
    private com.roadwise.backend.service.NotificationService notificationService;

    // ==========================================
    // 🚀 HELPER: DYNAMICALLY FIND ADMIN ID
    // ==========================================
    private Long getAdminId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null && (user.getRole().equalsIgnoreCase("CPDO Admin") || user.getRole().equalsIgnoreCase("Admin")))
                .map(User::getId)
                .findFirst()
                .orElse(1L); // Fallback to 1 if no admin is found
    }

    // ==========================================
    // 1. SMART LOGIN TRACKER (BRUTE-FORCE PROTECTION)
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

    // Temporarily stores MFA OTPs
    private static final Map<Long, MfaSession> mfaTracker = new ConcurrentHashMap<>();

    // Temporarily stores Password Reset OTPs
    private static final Map<Long, MfaSession> resetTracker = new ConcurrentHashMap<>();

    // ==========================================
    // 3. STEP 1: CREDENTIALS & OTP GENERATION
    // ==========================================
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        LoginAttemptTracker tracker = loginTracker.computeIfAbsent(username, k -> new LoginAttemptTracker());

        if (tracker.attempts >= 5 && tracker.lockoutTime != null) {
            Duration duration = Duration.between(tracker.lockoutTime, LocalDateTime.now());
            if (duration.toMinutes() < 60) {
                long minutesLeft = 60 - duration.toMinutes();
                return ResponseEntity.status(429).body(Map.of("error", "Account locked due to multiple failed logins. Try again in " + minutesLeft + " minute(s)."));
            } else {
                tracker.attempts = 0;
                tracker.lockoutTime = null;
            }
        }

        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty() || !userOpt.get().getPassword().equals(password)) {
            tracker.attempts++;
            if (tracker.attempts >= 5) {
                tracker.lockoutTime = LocalDateTime.now();

                // ==========================================
                // 🔔 NOTIFICATION TRIGGER: BRUTE-FORCE LOGIN
                // ==========================================
                Long adminId = getAdminId(); // 🚀 DYNAMIC ADMIN ID
                notificationService.sendNotification(
                        adminId,
                        "Security Alert: Account Locked",
                        "Multiple failed login attempts detected for username: '" + username + "'. Account has been temporarily locked for 1 hour.",
                        "SECURITY"
                );
                // ==========================================

                return ResponseEntity.status(429).body(Map.of("error", "Maximum attempts reached! Account locked for 1 hour for security."));
            }
            int remaining = 5 - tracker.attempts;
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials. " + remaining + " attempt(s) remaining."));
        }

        loginTracker.remove(username);
        User user = userOpt.get();

        if ("Suspended".equalsIgnoreCase(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("error", "Your account is currently Suspended. Please contact the CPDO."));
        }
        if ("Deactivated".equalsIgnoreCase(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("error", "Your account has been Deactivated. Access revoked."));
        }

        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "No official email is linked to this account. Cannot proceed with MFA. Contact Admin."));
        }

        // 🚀 GENERATE 6-DIGIT OTP
        String otp = String.format("%06d", new Random().nextInt(999999));
        mfaTracker.put(user.getId(), new MfaSession(otp, LocalDateTime.now().plusMinutes(5)));

        String subject = "RoadWise - Login Verification Code";
        String body = "Hello " + user.getFirstName() + ",\n\n" +
                "Your Multi-Factor Authentication (MFA) code is: " + otp + "\n\n" +
                "This code will expire in 5 minutes. Do not share this code with anyone.\n\n" +
                "If you did not attempt to log in, please contact the CPDO Administrator immediately.";

        emailService.sendEmail(user.getEmail(), subject, body);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("mfaRequired", true);
        responseData.put("userId", user.getId());
        responseData.put("message", "A 6-digit code has been sent to your email.");

        return ResponseEntity.ok(responseData);
    }

    // ==========================================
    // 4. STEP 2: VERIFY OTP & GRANT ACCESS
    // ==========================================
    @PostMapping("/verify-mfa")
    public ResponseEntity<?> verifyMfa(@RequestBody Map<String, Object> payload) {
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
            return ResponseEntity.status(401).body(Map.of("error", "Invalid verification code. Please try again."));
        }

        mfaTracker.remove(userId);

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found."));
        }
        User user = userOpt.get();

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
    // 5. 🚀 UPGRADED: FORGOT PASSWORD REQUEST OTP
    // ==========================================
    @PostMapping("/forgot-password/request")
    public ResponseEntity<?> requestPasswordReset(@RequestBody Map<String, String> payload) {
        String email = payload.get("email");

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "If this email exists, a recovery code will be sent shortly."));
        }

        User user = userOpt.get();

        String otp = String.format("%06d", new Random().nextInt(999999));
        resetTracker.put(user.getId(), new MfaSession(otp, LocalDateTime.now().plusMinutes(10)));

        String subject = "RoadWise - Password Reset Code";
        String body = "Hello " + user.getFirstName() + ",\n\n" +
                "You requested a password reset for your RoadWise account.\n\n" +
                "Your 6-digit Password Reset Code is: " + otp + "\n\n" +
                "This code will expire in 10 minutes.\n\n" +
                "If you did not request this, please ignore this email and your password will remain unchanged.";

        emailService.sendEmail(user.getEmail(), subject, body);

        // ==========================================
        // 🔔 NOTIFICATION TRIGGER: FORGOT PASSWORD
        // ==========================================
        Long adminId = getAdminId(); // 🚀 DYNAMIC ADMIN ID
        notificationService.sendNotification(
                adminId,
                "Support Request",
                "Barangay Official " + user.getFirstName() + " " + user.getLastName() + " has requested a password reset. System has dispatched recovery email.",
                "SUPPORT"
        );
        // ==========================================

        return ResponseEntity.ok(Map.of("message", "A 6-digit recovery code has been sent to your email.", "userId", user.getId()));
    }

    // ==========================================
    // 6. FORGOT PASSWORD VERIFY & RESET
    // ==========================================
    @PostMapping("/forgot-password/reset")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, Object> payload) {
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
            return ResponseEntity.status(401).body(Map.of("error", "Invalid recovery code. Please try again."));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found."));
        }

        User user = userOpt.get();
        user.setPassword(newPassword);
        userRepository.save(user);

        resetTracker.remove(userId);

        return ResponseEntity.ok(Map.of("message", "Password successfully reset! You can now log in."));
    }
}