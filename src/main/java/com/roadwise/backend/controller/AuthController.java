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

    // ==========================================
    // 1. SMART LOGIN TRACKER (BRUTE-FORCE PROTECTION)
    // ==========================================
    private static class LoginAttemptTracker {
        int attempts = 0;
        LocalDateTime lockoutTime = null;
    }

    private static final Map<String, LoginAttemptTracker> loginTracker = new ConcurrentHashMap<>();

    // ==========================================
    // 2. MFA (2FA) OTP TRACKER
    // ==========================================
    private static class MfaSession {
        String otp;
        LocalDateTime expiryTime;

        public MfaSession(String otp, LocalDateTime expiryTime) {
            this.otp = otp;
            this.expiryTime = expiryTime;
        }
    }

    // Temporarily stores the OTPs in server memory (Linked to the User's ID)
    private static final Map<Long, MfaSession> mfaTracker = new ConcurrentHashMap<>();

    // ==========================================
    // 3. STEP 1: CREDENTIALS & OTP GENERATION
    // ==========================================
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        // Grab or create the security tracker for this username
        LoginAttemptTracker tracker = loginTracker.computeIfAbsent(username, k -> new LoginAttemptTracker());

        // Check if they are currently serving a 1-hour lockout
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

        // Find the user in the database
        Optional<User> userOpt = userRepository.findByUsername(username);

        // Validate the user and password
        if (userOpt.isEmpty() || !userOpt.get().getPassword().equals(password)) {
            tracker.attempts++;
            if (tracker.attempts >= 5) {
                tracker.lockoutTime = LocalDateTime.now();
                return ResponseEntity.status(429).body(Map.of("error", "Maximum attempts reached! Account locked for 1 hour for security."));
            }
            int remaining = 5 - tracker.attempts;
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials. " + remaining + " attempt(s) remaining."));
        }

        // Success! Clear their brute-force tracker
        loginTracker.remove(username);
        User user = userOpt.get();

        // Strict Account Status Check
        if ("Suspended".equalsIgnoreCase(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("error", "Your account is currently Suspended. Please contact the CPDO."));
        }
        if ("Deactivated".equalsIgnoreCase(user.getStatus())) {
            return ResponseEntity.status(403).body(Map.of("error", "Your account has been Deactivated. Access revoked."));
        }

        // Check if the user has an email set up for MFA
        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "No official email is linked to this account. Cannot proceed with MFA. Contact Admin."));
        }

        // 🚀 GENERATE 6-DIGIT OTP
        String otp = String.format("%06d", new Random().nextInt(999999));

        // Save the OTP in server memory, set to expire in 5 minutes
        mfaTracker.put(user.getId(), new MfaSession(otp, LocalDateTime.now().plusMinutes(5)));

        // 🚀 SEND THE OTP EMAIL
        String subject = "RoadWise - Login Verification Code";
        String body = "Hello " + user.getFirstName() + ",\n\n" +
                "Your Multi-Factor Authentication (MFA) code is: " + otp + "\n\n" +
                "This code will expire in 5 minutes. Do not share this code with anyone.\n\n" +
                "If you did not attempt to log in, please contact the CPDO Administrator immediately.";

        emailService.sendEmail(user.getEmail(), subject, body);

        // Tell the frontend to switch to the MFA screen
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
        // Safely parse the incoming JSON payload
        Long userId = Long.valueOf(payload.get("userId").toString());
        String submittedOtp = payload.get("otp").toString();

        MfaSession session = mfaTracker.get(userId);

        // 1. Check if the session exists
        if (session == null) {
            return ResponseEntity.status(400).body(Map.of("error", "No active login session found. Please go back and log in again."));
        }

        // 2. Check if the OTP is expired (Older than 5 minutes)
        if (LocalDateTime.now().isAfter(session.expiryTime)) {
            mfaTracker.remove(userId);
            return ResponseEntity.status(400).body(Map.of("error", "Verification code expired. Please go back and log in again."));
        }

        // 3. Check if the OTP is wrong
        if (!session.otp.equals(submittedOtp)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid verification code. Please try again."));
        }

        // 4. OTP IS CORRECT! Clear the tracker so the OTP cannot be reused
        mfaTracker.remove(userId);

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found."));
        }
        User user = userOpt.get();

        // 5. Build the "VIP Ticket" (Response Data) to finally let them into the dashboard
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

        // Attach Barangay location if applicable
        if (user.getBarangay() != null) {
            responseData.put("barangayId", user.getBarangay().getId());
            responseData.put("barangayName", user.getBarangay().getBarangayName());
        } else {
            responseData.put("barangayId", null);
            responseData.put("barangayName", "City Hall Central");
        }

        return ResponseEntity.ok(responseData);
    }
}