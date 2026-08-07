package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "${frontend.url}")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    // ==========================================
    // SMART LOGIN TRACKER (BRUTE-FORCE PROTECTION)
    // ==========================================
    private static class LoginAttemptTracker {
        int attempts = 0;
        LocalDateTime lockoutTime = null;
    }

    // Tracks failed attempts by username
    private static final Map<String, LoginAttemptTracker> loginTracker = new ConcurrentHashMap<>();

    // 🚀 SINGLE UNIFIED LOGIN ENDPOINT
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        // 1. Grab or create the security tracker for this username
        LoginAttemptTracker tracker = loginTracker.computeIfAbsent(username, k -> new LoginAttemptTracker());

        // 2. Check if they are currently serving a 1-hour lockout
        if (tracker.attempts >= 5 && tracker.lockoutTime != null) {
            Duration duration = Duration.between(tracker.lockoutTime, LocalDateTime.now());

            if (duration.toMinutes() < 60) {
                long minutesLeft = 60 - duration.toMinutes();
                return ResponseEntity.status(429).body(Map.of("error", "Account locked due to multiple failed logins. Try again in " + minutesLeft + " minute(s)."));
            } else {
                // The 1-hour penalty is over. Reset their tracker!
                tracker.attempts = 0;
                tracker.lockoutTime = null;
            }
        }

        // 3. Find the user in the database
        Optional<User> userOpt = userRepository.findByUsername(username);

        // 4. Validate the user and password
        if (userOpt.isEmpty() || !userOpt.get().getPassword().equals(password)) {
            tracker.attempts++;

            // Did they just hit their 5th strike? Lock them out!
            if (tracker.attempts >= 5) {
                tracker.lockoutTime = LocalDateTime.now();
                return ResponseEntity.status(429).body(Map.of("error", "Maximum attempts reached! Account locked for 1 hour for security."));
            }

            int remaining = 5 - tracker.attempts;
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials. " + remaining + " attempt(s) remaining."));
        }

        // 5. Success! Clear their tracker and log them in
        loginTracker.remove(username);
        User user = userOpt.get();

        // 6. Build the "VIP Ticket" (Response Data)
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

        // If they are a Barangay Official, send their specific location!
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