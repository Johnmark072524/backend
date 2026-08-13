package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "${frontend.url}")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.roadwise.backend.service.EmailService emailService;

    @Autowired
    private com.roadwise.backend.repository.BarangayRepository barangayRepository;

    // 🚀 1. BRING IN THE NOTIFICATION SERVICE (THE "POST OFFICE")
    @Autowired
    private com.roadwise.backend.service.NotificationService notificationService;

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

        if (updates.containsKey("phoneNumber")) user.setPhoneNumber(updates.get("phoneNumber"));
        if (updates.containsKey("email")) user.setEmail(updates.get("email"));
        if (updates.containsKey("gender")) user.setGender(updates.get("gender"));
        if (updates.containsKey("birthday") && updates.get("birthday") != null && !updates.get("birthday").isEmpty()) {
            user.setBirthday(LocalDate.parse(updates.get("birthday")));
        }

        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }

    // ==========================================
    // 2. UPLOAD PROFILE PICTURE
    // ==========================================
    @PostMapping(value = "/{id}/profile-picture", consumes = {"multipart/form-data"})
    public ResponseEntity<?> uploadProfilePicture(
            @PathVariable Long id,
            @RequestParam("profilePicture") MultipartFile file) {
        try {
            Optional<User> userOpt = userRepository.findById(id);
            if (userOpt.isEmpty()) return ResponseEntity.notFound().build();

            User user = userOpt.get();
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);

            String uniqueFilename = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            Path filePath = uploadPath.resolve(uniqueFilename);
            Files.copy(file.getInputStream(), filePath);

            user.setProfilePicture(uniqueFilename);
            userRepository.save(user);

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
    private static class AttemptTracker {
        int attempts = 0;
        java.time.LocalDateTime lockoutTime = null;
    }

    private static final java.util.Map<Long, AttemptTracker> securityTracker = new java.util.concurrent.ConcurrentHashMap<>();

    @PutMapping("/{id}/password")
    public ResponseEntity<?> updatePassword(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) return ResponseEntity.notFound().build();

        User user = userOpt.get();
        String currentPassword = payload.get("currentPassword");
        String newPassword = payload.get("newPassword");

        AttemptTracker tracker = securityTracker.computeIfAbsent(id, k -> new AttemptTracker());

        if (tracker.attempts >= 5 && tracker.lockoutTime != null) {
            java.time.Duration duration = java.time.Duration.between(tracker.lockoutTime, java.time.LocalDateTime.now());
            if (duration.toMinutes() < 60) {
                long minutesLeft = 60 - duration.toMinutes();
                return ResponseEntity.status(429).body(Map.of("error", "Security lockout active. Please try again in " + minutesLeft + " minute(s)."));
            } else {
                tracker.attempts = 0;
                tracker.lockoutTime = null;
            }
        }

        if (!user.getPassword().equals(currentPassword)) {
            tracker.attempts++;
            if (tracker.attempts >= 5) {
                tracker.lockoutTime = java.time.LocalDateTime.now();
                return ResponseEntity.status(429).body(Map.of("error", "Maximum attempts reached! Account locked for 1 hour for security."));
            }
            int remaining = 5 - tracker.attempts;
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password. " + remaining + " attempt(s) remaining."));
        }

        securityTracker.remove(id);
        user.setPassword(newPassword);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password updated successfully!"));
    }

    // ==========================================
    // 4. FETCH ALL BARANGAY OFFICIALS (For Admin)
    // ==========================================
    @GetMapping("/officials")
    public ResponseEntity<List<User>> getBarangayOfficials() {
        List<User> officials = userRepository.findByRole("BARANGAY");
        return ResponseEntity.ok(officials);
    }

    // ==========================================
    // 5. PROVISION NEW BARANGAY OFFICIAL ACCOUNT
    // ==========================================
    @PostMapping("/register")
    public ResponseEntity<?> registerOfficial(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(400).body(Map.of("error", "Username already exists!"));
        }

        User newUser = new User();
        newUser.setFirstName(payload.get("firstName"));
        newUser.setMiddleName(payload.get("middleName"));
        newUser.setLastName(payload.get("lastName"));
        newUser.setEmail(payload.get("email"));
        newUser.setUsername(username);
        newUser.setPassword(payload.get("password"));
        newUser.setRole(payload.get("role"));
        newUser.setStatus("Active");

        if (payload.get("barangayId") != null && !payload.get("barangayId").isEmpty()) {
            Long brgyId = Long.parseLong(payload.get("barangayId"));
            barangayRepository.findById(brgyId).ifPresent(newUser::setBarangay);
        }

        // Save and store the generated user object to capture the ID
        User savedUser = userRepository.save(newUser);

        // ==========================================
        // 🔔 2. TRIGGER REAL IN-APP NOTIFICATION!
        // ==========================================
        notificationService.sendNotification(
                savedUser.getId(),
                "Account Provisioned",
                "Welcome to RoadWise! Your official account has been created by the City Admin. Please change your password for security.",
                "ACCOUNT"
        );

        // 🚀 EMAIL TRIGGER: Sends welcome credentials WITH the Vercel Link
        if (savedUser.getEmail() != null && !savedUser.getEmail().isEmpty()) {
            String subject = "Welcome to RoadWise - Your Account Credentials";
            String emailBody = "Hello " + savedUser.getFirstName() + ",\n\n" +
                    "Your official RoadWise Barangay Official account has been provisioned.\n\n" +
                    "Username: " + savedUser.getUsername() + "\n" +
                    "Temporary Password: " + savedUser.getPassword() + "\n\n" +
                    "Please log in here: https://frontend-capstone-fawn.vercel.app/login.html\n\n" +
                    "For security purposes, please change your password immediately after logging in.\n\n" +
                    "Best regards,\nCPDO Administrator - RoadWise SJDM";

            emailService.sendEmail(savedUser.getEmail(), subject, emailBody);
        }

        return ResponseEntity.ok(Map.of("message", "Official successfully provisioned!"));
    }

    // ==========================================
    // 6. GET SINGLE USER DATA (For Manage Modal)
    // ==========================================
    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ==========================================
    // 7. ADMIN: UPDATE OFFICIAL RECORD & STATUS
    // ==========================================
    @PutMapping("/{id}/manage")
    public ResponseEntity<?> manageUserRecord(@PathVariable Long id, @RequestBody Map<String, String> updates) {
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        user.setFirstName(updates.get("firstName"));
        user.setMiddleName(updates.get("middleName"));
        user.setLastName(updates.get("lastName"));
        user.setEmail(updates.get("email"));

        // Optional Status change
        if (updates.containsKey("status")) {
            String oldStatus = user.getStatus();
            String newStatus = updates.get("status");
            user.setStatus(newStatus);

            // 🔔 BONUS: Trigger an alert if Admin changes their account status!
            if (oldStatus != null && !oldStatus.equals(newStatus)) {
                notificationService.sendNotification(
                        user.getId(),
                        "Account Status Update",
                        "Your account status has been updated to: " + newStatus + ".",
                        "ACCOUNT"
                );
            }
        }

        if (updates.get("barangayId") != null && !updates.get("barangayId").isEmpty()) {
            Long brgyId = Long.parseLong(updates.get("barangayId"));
            barangayRepository.findById(brgyId).ifPresent(user::setBarangay);
        }

        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Official record updated successfully!"));
    }

    // ==========================================
    // 8. 🚨 ADMIN: EMERGENCY PASSWORD RESET
    // ==========================================
    @PutMapping("/{id}/emergency-reset")
    public ResponseEntity<?> emergencyPasswordReset(@PathVariable Long id) {
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        user.setPassword("RoadWise2026!");
        userRepository.save(user);

        // 🔔 NOTIFICATION TRIGGER: Let the official know their password was reset
        notificationService.sendNotification(
                user.getId(),
                "Security Alert",
                "Your password has been reset by the City Administrator. Please update it immediately.",
                "SECURITY"
        );

        return ResponseEntity.ok(Map.of("message", "Password successfully reset to default."));
    }
}