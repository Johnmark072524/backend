package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "${frontend.url}")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private BarangayRepository barangayRepository;

    @Autowired
    private NotificationService notificationService;

    // 🚀 INJECTED ACTIVITY LOG AUDIT SERVICE
    @Autowired
    private ActivityLogService activityLogService;

    private static final String UPLOAD_DIR = "uploads/";

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
    // 1. UPDATE TEXT PROFILE DETAILS
    // ==========================================
    @PutMapping("/{id}/profile")
    public ResponseEntity<?> updateProfile(
            @PathVariable Long id,
            @RequestBody Map<String, String> updates,
            HttpServletRequest request) {

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

        // ⏱️ AUDIT LOG: PROFILE UPDATE
        activityLogService.log(
                user,
                "USER",
                "USER_PROFILE_UPDATED",
                "#USR-" + user.getId(),
                "Updated personal profile information (contact/demographics).",
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }

    // ==========================================
    // 2. UPLOAD PROFILE PICTURE
    // ==========================================
    @PostMapping(value = "/{id}/profile-picture", consumes = {"multipart/form-data"})
    public ResponseEntity<?> uploadProfilePicture(
            @PathVariable Long id,
            @RequestParam("profilePicture") MultipartFile file,
            HttpServletRequest request) {
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

            // ⏱️ AUDIT LOG: AVATAR CHANGE
            activityLogService.log(
                    user,
                    "USER",
                    "USER_AVATAR_UPDATED",
                    "#USR-" + user.getId(),
                    "Updated account profile picture.",
                    "SUCCESS",
                    request
            );

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
        LocalDateTime lockoutTime = null;
    }

    private static final Map<Long, AttemptTracker> securityTracker = new ConcurrentHashMap<>();

    @PutMapping("/{id}/password")
    public ResponseEntity<?> updatePassword(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) return ResponseEntity.notFound().build();

        User user = userOpt.get();
        String currentPassword = payload.get("currentPassword");
        String newPassword = payload.get("newPassword");

        AttemptTracker tracker = securityTracker.computeIfAbsent(id, k -> new AttemptTracker());

        if (tracker.attempts >= 5 && tracker.lockoutTime != null) {
            Duration duration = Duration.between(tracker.lockoutTime, LocalDateTime.now());
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
                tracker.lockoutTime = LocalDateTime.now();

                // ⏱️ AUDIT LOG: PASSWORD LOCKOUT
                activityLogService.log(
                        user,
                        "AUTH",
                        "PASSWORD_CHANGE_LOCKED",
                        "#USR-" + user.getId(),
                        "Password update locked for 1 hour due to multiple incorrect password attempts.",
                        "WARNING",
                        request
                );

                return ResponseEntity.status(429).body(Map.of("error", "Maximum attempts reached! Account locked for 1 hour for security."));
            }
            int remaining = 5 - tracker.attempts;
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password. " + remaining + " attempt(s) remaining."));
        }

        securityTracker.remove(id);
        user.setPassword(newPassword);
        userRepository.save(user);

        // ⏱️ AUDIT LOG: PASSWORD UPDATE SUCCESS
        activityLogService.log(
                user,
                "AUTH",
                "USER_PASSWORD_UPDATED",
                "#USR-" + user.getId(),
                "User successfully changed account password.",
                "SUCCESS",
                request
        );

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
    // 5. PROVISION NEW BARANGAY OFFICIAL ACCOUNT (ADMIN)
    // ==========================================
    @PostMapping("/register")
    public ResponseEntity<?> registerOfficial(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

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

        // 🛡️ 1-OFFICIAL-PER-BARANGAY VALIDATION
        if (payload.get("barangayId") != null && !payload.get("barangayId").isEmpty()) {
            Long brgyId = Long.parseLong(payload.get("barangayId"));
            List<User> existingOfficials = userRepository.findByBarangayId(brgyId);

            // Check if another active/suspended official already occupies this barangay
            Optional<User> activeOfficial = existingOfficials.stream()
                    .filter(u -> u.getStatus() == null || !u.getStatus().equalsIgnoreCase("Deactivated"))
                    .findFirst();

            if (activeOfficial.isPresent()) {
                User occupiedBy = activeOfficial.get();
                return ResponseEntity.status(400).body(Map.of("error",
                        "This Barangay already has an assigned official (" + occupiedBy.getFirstName() + " " + occupiedBy.getLastName() + "). Only 1 official is allowed per Barangay."));
            }

            barangayRepository.findById(brgyId).ifPresent(newUser::setBarangay);
        }

        User savedUser = userRepository.save(newUser);

        // 🔔 NOTIFICATION TRIGGER
        notificationService.sendNotification(
                savedUser.getId(),
                "Account Provisioned",
                "Welcome to RoadWise! Your official account has been created by the City Admin. Please change your password for security.",
                "ACCOUNT"
        );

        // 🚀 EMAIL TRIGGER
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

        // ⏱️ AUDIT LOG: Attributed to Admin
        String adminIdStr = payload.get("adminId");
        Long adminId = (adminIdStr != null && !adminIdStr.isEmpty()) ? Long.valueOf(adminIdStr) : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);

        String brgyName = savedUser.getBarangay() != null ? savedUser.getBarangay().getBarangayName() : "City Central";
        activityLogService.log(
                adminActor,
                "USER",
                "USER_PROVISIONED",
                "#USR-" + savedUser.getId(),
                "Provisioned new " + savedUser.getRole() + " account for " + savedUser.getFirstName() + " " + savedUser.getLastName() + " (Assigned: " + brgyName + ").",
                "SUCCESS",
                request
        );

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
    public ResponseEntity<?> manageUserRecord(
            @PathVariable Long id,
            @RequestBody Map<String, String> updates,
            HttpServletRequest request) {

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        user.setFirstName(updates.get("firstName"));
        user.setMiddleName(updates.get("middleName"));
        user.setLastName(updates.get("lastName"));
        user.setEmail(updates.get("email"));

        String oldStatus = user.getStatus();
        String newStatus = updates.get("status");

        if (newStatus != null && !newStatus.isEmpty()) {
            user.setStatus(newStatus);

            if (oldStatus != null && !oldStatus.equals(newStatus)) {
                notificationService.sendNotification(
                        user.getId(),
                        "Account Status Update",
                        "Your account status has been updated to: " + newStatus + ".",
                        "ACCOUNT"
                );
            }
        }

        // 🛡️ 1-OFFICIAL-PER-BARANGAY VALIDATION ON REASSIGNMENT
        if (updates.get("barangayId") != null && !updates.get("barangayId").isEmpty()) {
            Long brgyId = Long.parseLong(updates.get("barangayId"));
            List<User> existingOfficials = userRepository.findByBarangayId(brgyId);

            // Check if ANOTHER user (not the one being edited) already actively occupies this barangay
            Optional<User> conflictOfficial = existingOfficials.stream()
                    .filter(u -> !u.getId().equals(id) && (u.getStatus() == null || !u.getStatus().equalsIgnoreCase("Deactivated")))
                    .findFirst();

            if (conflictOfficial.isPresent()) {
                User occupiedBy = conflictOfficial.get();
                return ResponseEntity.status(400).body(Map.of("error",
                        "Cannot reassign: This Barangay is already assigned to " + occupiedBy.getFirstName() + " " + occupiedBy.getLastName() + "."));
            }

            barangayRepository.findById(brgyId).ifPresent(user::setBarangay);
        }

        userRepository.save(user);

        // ⏱️ AUDIT LOG: Attributed to Admin
        String adminIdStr = updates.get("adminId");
        Long adminId = (adminIdStr != null && !adminIdStr.isEmpty()) ? Long.valueOf(adminIdStr) : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);

        String statusNotice = (newStatus != null && !newStatus.equalsIgnoreCase(oldStatus)) ? " Status changed to '" + newStatus + "'." : "";
        activityLogService.log(
                adminActor,
                "USER",
                "USER_RECORD_MANAGED",
                "#USR-" + user.getId(),
                "Updated official profile/jurisdiction for " + user.getFirstName() + " " + user.getLastName() + "." + statusNotice,
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(Map.of("message", "Official record updated successfully!"));
    }

    // ==========================================
    // 8. 🚨 ADMIN: EMERGENCY PASSWORD RESET
    // ==========================================
    @PutMapping("/{id}/emergency-reset")
    public ResponseEntity<?> emergencyPasswordReset(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> payload,
            HttpServletRequest request) {

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        user.setPassword("RoadWise2026!");
        userRepository.save(user);

        notificationService.sendNotification(
                user.getId(),
                "Security Alert",
                "Your password has been reset by the City Administrator. Please update it immediately.",
                "SECURITY"
        );

        // ⏱️ AUDIT LOG: Attributed to Admin
        String adminIdStr = (payload != null) ? payload.get("adminId") : null;
        Long adminId = (adminIdStr != null && !adminIdStr.isEmpty()) ? Long.valueOf(adminIdStr) : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);

        activityLogService.log(
                adminActor,
                "AUTH",
                "ADMIN_EMERGENCY_PASSWORD_RESET",
                "#USR-" + user.getId(),
                "Admin performed emergency default password reset for " + user.getFirstName() + " " + user.getLastName() + ".",
                "WARNING",
                request
        );

        return ResponseEntity.ok(Map.of("message", "Password successfully reset to default."));
    }
}