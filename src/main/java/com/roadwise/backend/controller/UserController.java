package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile; // 🚀 NEW: Required for heavy files

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID; // 🚀 NEW: Required for unique filenames

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "${frontend.url}")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    // Defines exactly where the pictures will be saved on your server
    private static final String UPLOAD_DIR = "uploads/";

    // ==========================================
    // 1. UPDATE TEXT PROFILE DETAILS
    // ==========================================
    @PutMapping("/{id}/profile")
    public ResponseEntity<?> updateProfile(@PathVariable Long id, @RequestBody Map<String, String> updates) {
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();

        if (updates.containsKey("phoneNumber")) {
            user.setPhoneNumber(updates.get("phoneNumber"));
        }
        if (updates.containsKey("email")) {
            user.setEmail(updates.get("email"));
        }
        if (updates.containsKey("gender")) {
            user.setGender(updates.get("gender"));
        }

        if (updates.containsKey("birthday") && updates.get("birthday") != null && !updates.get("birthday").isEmpty()) {
            user.setBirthday(LocalDate.parse(updates.get("birthday")));
        }

        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }

    // ==========================================
    // 2. 🚀 NEW: UPLOAD PROFILE PICTURE
    // ==========================================
    @PostMapping(value = "/{id}/profile-picture", consumes = {"multipart/form-data"})
    public ResponseEntity<?> uploadProfilePicture(
            @PathVariable Long id,
            @RequestParam("profilePicture") MultipartFile file) {

        try {
            // 1. Find the exact user
            Optional<User> userOpt = userRepository.findById(id);
            if (userOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            User user = userOpt.get();

            // 2. Ensure the 'uploads' folder exists
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 3. Generate a secure, unique filename and save to hard drive
            String uniqueFilename = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            Path filePath = uploadPath.resolve(uniqueFilename);
            Files.copy(file.getInputStream(), filePath);

            // 4. Save the filename permanently to PostgreSQL
            user.setProfilePicture(uniqueFilename);
            userRepository.save(user);

            // 5. Return the filename so the frontend can instantly display it!
            return ResponseEntity.ok(Map.of(
                    "message", "Profile picture updated successfully!",
                    "profilePicture", uniqueFilename
            ));

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to upload profile picture."));
        }
    }

    // ==========================================
    // 3. SECURE PASSWORD UPDATE (1-HOUR LOCKOUT)
    // ==========================================

    // Smart tracker that remembers attempt counts AND the exact time of lockout
    private static class AttemptTracker {
        int attempts = 0;
        java.time.LocalDateTime lockoutTime = null;
    }

    private static final java.util.Map<Long, AttemptTracker> securityTracker = new java.util.concurrent.ConcurrentHashMap<>();

    @PutMapping("/{id}/password")
    public ResponseEntity<?> updatePassword(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        String currentPassword = payload.get("currentPassword");
        String newPassword = payload.get("newPassword");

        // 1. Grab or create the security tracker for this specific user
        AttemptTracker tracker = securityTracker.computeIfAbsent(id, k -> new AttemptTracker());

        // 2. Check if they are currently serving a 1-hour lockout
        if (tracker.attempts >= 5 && tracker.lockoutTime != null) {
            java.time.Duration duration = java.time.Duration.between(tracker.lockoutTime, java.time.LocalDateTime.now());

            if (duration.toMinutes() < 60) {
                // Still locked out! Calculate remaining minutes
                long minutesLeft = 60 - duration.toMinutes();
                return ResponseEntity.status(429).body(Map.of("error", "Security lockout active. Please try again in " + minutesLeft + " minute(s)."));
            } else {
                // The 1-hour penalty is over. Reset their tracker!
                tracker.attempts = 0;
                tracker.lockoutTime = null;
            }
        }

        // 3. Verify the old password against the database
        if (!user.getPassword().equals(currentPassword)) {
            tracker.attempts++;

            // Did they just hit their 5th strike? Lock them out and start the timer!
            if (tracker.attempts >= 5) {
                tracker.lockoutTime = java.time.LocalDateTime.now();
                return ResponseEntity.status(429).body(Map.of("error", "Maximum attempts reached! Account locked for 1 hour for security."));
            }

            int remaining = 5 - tracker.attempts;
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password. " + remaining + " attempt(s) remaining."));
        }

        // 4. Success! Clear their tracker entirely and save the new password
        securityTracker.remove(id);
        user.setPassword(newPassword);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password updated successfully!"));
    }

}