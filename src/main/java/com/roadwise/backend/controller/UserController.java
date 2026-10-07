package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.SupabaseStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    @Autowired
    private ActivityLogService activityLogService;

    // 🚀 INJECTED SUPABASE CLOUD STORAGE SERVICE
    @Autowired
    private SupabaseStorageService supabaseStorageService;

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
    // 2. UPLOAD PROFILE PICTURE (SUPABASE STORAGE)
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

            // ☁️ UPLOAD DIRECTLY TO SUPABASE OBJECT STORAGE
            String cloudAvatarUrl = supabaseStorageService.uploadImage(file);

            user.setProfilePicture(cloudAvatarUrl);
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
                    "profilePicture", cloudAvatarUrl
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to upload profile picture: " + e.getMessage()));
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
    // 0. GET ALL SYSTEM USERS
    // ==========================================
    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        List<User> users = userRepository.findAll();
        return ResponseEntity.ok(users);
    }

    // ==========================================
    // 4. FETCH ALL BARANGAY OFFICIALS
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

        if (payload.get("barangayId") != null && !payload.get("barangayId").isEmpty()) {
            Long brgyId = Long.parseLong(payload.get("barangayId"));
            List<User> existingOfficials = userRepository.findByBarangayId(brgyId);

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

        notificationService.sendNotification(
                savedUser.getId(),
                "Account Provisioned",
                "Welcome to RoadWise! Your official account has been created by the City Admin. Please change your password for security.",
                "ACCOUNT"
        );

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
    // 6. GET SINGLE USER DATA
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

        if (updates.get("barangayId") != null && !updates.get("barangayId").isEmpty()) {
            Long brgyId = Long.parseLong(updates.get("barangayId"));
            List<User> existingOfficials = userRepository.findByBarangayId(brgyId);

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
    // 8. ADMIN: EMERGENCY PASSWORD RESET
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

    // ==========================================
    // 9. CPDO ADMIN: FORMAL OFFICE TURNOVER / SUCCESSION
    // ==========================================
    @PostMapping("/handover-admin")
    public ResponseEntity<?> handoverAdminOffice(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        String currentAdminIdStr = payload.get("currentAdminId");
        String currentPassword = payload.get("currentPassword");
        String newFirstName = payload.get("firstName");
        String newMiddleName = payload.get("middleName");
        String newLastName = payload.get("lastName");
        String newEmail = payload.get("email");
        String newUsername = payload.get("username");
        String newPhone = payload.get("phoneNumber");
        String memoNumber = payload.get("memoNumber"); // e.g. "EO-2026-04"

        if (currentAdminIdStr == null || currentPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current administrator credentials are required."));
        }

        Long currentAdminId = Long.valueOf(currentAdminIdStr);
        Optional<User> currentAdminOpt = userRepository.findById(currentAdminId);
        if (currentAdminOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Current administrator account not found."));
        }

        User currentAdmin = currentAdminOpt.get();

        // 🛡️ 1. SECURITY CHECK: Verify Outgoing Admin Password
        if (!currentAdmin.getPassword().equals(currentPassword)) {
            activityLogService.log(
                    currentAdmin,
                    "SECURITY",
                    "ADMIN_HANDOVER_UNAUTHORIZED",
                    "#USR-" + currentAdmin.getId(),
                    "Unauthorized turnover attempt: Incorrect current password submitted.",
                    "FAILED",
                    request
            );
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password. Turnover authorization rejected."));
        }

        // 🛡️ 2. UNIQUE CONFLICT CHECKS
        if (userRepository.findByUsername(newUsername).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username '" + newUsername + "' is already taken."));
        }

        if (userRepository.findByEmail(newEmail).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Official email '" + newEmail + "' is already registered to another user."));
        }

        // 🛡️ 3. GENERATE SECURE TEMPORARY CREDENTIALS
        String tempPassword = "RoadWise@" + (1000 + new Random().nextInt(9000)) + "!";

        // 4. PROVISION INCOMING SUCCESSOR
        User newAdmin = new User();
        newAdmin.setFirstName(newFirstName != null ? newFirstName.trim() : "");
        newAdmin.setMiddleName(newMiddleName != null ? newMiddleName.trim() : "");
        newAdmin.setLastName(newLastName != null ? newLastName.trim() : "");
        newAdmin.setEmail(newEmail != null ? newEmail.trim() : "");
        newAdmin.setUsername(newUsername != null ? newUsername.trim() : "");
        newAdmin.setPhoneNumber(newPhone != null ? newPhone.trim() : "");
        newAdmin.setPassword(tempPassword);
        newAdmin.setRole("CPDO Admin");
        newAdmin.setStatus("Active");
        newAdmin.setFailedLoginAttempts(0);
        newAdmin.setProfilePicture("no_image.jpg");

        User savedNewAdmin = userRepository.save(newAdmin);

        // 5. DEACTIVATE OUTGOING PREDECESSOR (Preserve audit integrity)
        currentAdmin.setStatus("Deactivated");
        userRepository.save(currentAdmin);

        // 📋 6. WRITE PERMANENT SYSTEM ACTIVITY LOG
        String memoText = (memoNumber != null && !memoNumber.trim().isEmpty()) ? " [Memo Ref: " + memoNumber.trim() + "]" : "";
        activityLogService.log(
                currentAdmin,
                "AUTH",
                "OFFICIAL_OFFICE_TURNOVER",
                "#USR-" + savedNewAdmin.getId(),
                "CPDO Admin " + currentAdmin.getFirstName() + " " + currentAdmin.getLastName() +
                        " officially turned over administrative office to " + savedNewAdmin.getFirstName() + " " + savedNewAdmin.getLastName() + memoText + ".",
                "SUCCESS",
                request
        );

        // 📧 7. SEND SUCCESSION DISPATCH EMAIL TO NEW ADMIN
        if (savedNewAdmin.getEmail() != null && !savedNewAdmin.getEmail().isEmpty()) {
            String subject = "RoadWise - Administrative Succession Credentials";
            String body = "Hello " + savedNewAdmin.getFirstName() + ",\n\n" +
                    "Administrative authority for the RoadWise System has been formally transferred to you by " +
                    currentAdmin.getFirstName() + " " + currentAdmin.getLastName() + ".\n\n" +
                    "Login Credentials:\n" +
                    "• Username: " + savedNewAdmin.getUsername() + "\n" +
                    "• Temporary Password: " + tempPassword + "\n\n" +
                    "Please log in and update your security credentials.\n\n" +
                    "Best regards,\nRoadWise Administration - City of San Jose del Monte";
            try {
                emailService.sendEmail(savedNewAdmin.getEmail(), subject, body);
            } catch (Exception e) {
                System.err.println("Turnover email delivery failed: " + e.getMessage());
            }
        }

        // 8. RETURN RESPONSE (Includes temp credentials for demo convenience)
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("message", "Administrative office successfully turned over!");
        responseData.put("successorName", savedNewAdmin.getFirstName() + " " + savedNewAdmin.getLastName());
        responseData.put("successorUsername", savedNewAdmin.getUsername());
        responseData.put("tempPassword", tempPassword);

        return ResponseEntity.ok(responseData);
    }

    // ==========================================
    // 10. CITY ENGINEER (CEO): FORMAL OFFICE TURNOVER
    // ==========================================
    @PostMapping("/handover-ceo")
    public ResponseEntity<?> handoverCeoOffice(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        String currentCeoIdStr = payload.get("currentCeoId");
        String currentPassword = payload.get("currentPassword");
        String newFirstName = payload.get("firstName");
        String newMiddleName = payload.get("middleName");
        String newLastName = payload.get("lastName");
        String newEmail = payload.get("email");
        String newUsername = payload.get("username");
        String newPhone = payload.get("phoneNumber");
        String memoNumber = payload.get("memoNumber");

        if (currentCeoIdStr == null || currentPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current City Engineer credentials are required."));
        }

        Long currentCeoId = Long.valueOf(currentCeoIdStr);
        Optional<User> currentCeoOpt = userRepository.findById(currentCeoId);
        if (currentCeoOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Current City Engineer account not found."));
        }

        User currentCeo = currentCeoOpt.get();

        // 🛡️ 1. SECURITY CHECK: Verify Outgoing CEO Password
        if (!currentCeo.getPassword().equals(currentPassword)) {
            activityLogService.log(
                    currentCeo,
                    "SECURITY",
                    "CEO_HANDOVER_UNAUTHORIZED",
                    "#USR-" + currentCeo.getId(),
                    "Unauthorized turnover attempt: Incorrect current password submitted.",
                    "FAILED",
                    request
            );
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password. Turnover authorization rejected."));
        }

        // 🛡️ 2. UNIQUE CONFLICT CHECKS
        if (userRepository.findByUsername(newUsername).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username '" + newUsername + "' is already taken."));
        }

        if (userRepository.findByEmail(newEmail).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Official email '" + newEmail + "' is already registered to another user."));
        }

        // 🛡️ 3. GENERATE SECURE TEMPORARY CREDENTIALS
        String tempPassword = "RoadWise@" + (1000 + new Random().nextInt(9000)) + "!";

        // 4. PROVISION INCOMING SUCCESSOR (Inherits current CEO role)
        User newCeo = new User();
        newCeo.setFirstName(newFirstName != null ? newFirstName.trim() : "");
        newCeo.setMiddleName(newMiddleName != null ? newMiddleName.trim() : "");
        newCeo.setLastName(newLastName != null ? newLastName.trim() : "");
        newCeo.setEmail(newEmail != null ? newEmail.trim() : "");
        newCeo.setUsername(newUsername != null ? newUsername.trim() : "");
        newCeo.setPhoneNumber(newPhone != null ? newPhone.trim() : "");
        newCeo.setPassword(tempPassword);
        newCeo.setRole(currentCeo.getRole() != null ? currentCeo.getRole() : "ENGINEER");
        newCeo.setStatus("Active");
        newCeo.setFailedLoginAttempts(0);
        newCeo.setProfilePicture("no_image.jpg");

        User savedNewCeo = userRepository.save(newCeo);

        // 5. DEACTIVATE OUTGOING PREDECESSOR (Preserve audit integrity)
        currentCeo.setStatus("Deactivated");
        userRepository.save(currentCeo);

        // 📋 6. WRITE SYSTEM ACTIVITY LOG
        String memoText = (memoNumber != null && !memoNumber.trim().isEmpty()) ? " [Memo Ref: " + memoNumber.trim() + "]" : "";
        activityLogService.log(
                currentCeo,
                "AUTH",
                "OFFICIAL_OFFICE_TURNOVER",
                "#USR-" + savedNewCeo.getId(),
                "City Engineer " + currentCeo.getFirstName() + " " + currentCeo.getLastName() +
                        " officially turned over engineering office to " + savedNewCeo.getFirstName() + " " + savedNewCeo.getLastName() + memoText + ".",
                "SUCCESS",
                request
        );

        // 📧 7. SEND SUCCESSION CREDENTIALS VIA EMAIL
        if (savedNewCeo.getEmail() != null && !savedNewCeo.getEmail().isEmpty()) {
            String subject = "RoadWise - City Engineer Succession Credentials";
            String body = "Hello " + savedNewCeo.getFirstName() + ",\n\n" +
                    "Engineering administration for the RoadWise System has been formally transferred to you by Engr. " +
                    currentCeo.getFirstName() + " " + currentCeo.getLastName() + ".\n\n" +
                    "Login Credentials:\n" +
                    "• Username: " + savedNewCeo.getUsername() + "\n" +
                    "• Temporary Password: " + tempPassword + "\n\n" +
                    "Please log in and update your security credentials.\n\n" +
                    "Best regards,\nCity Engineering Office - City of San Jose del Monte";
            try {
                emailService.sendEmail(savedNewCeo.getEmail(), subject, body);
            } catch (Exception e) {
                System.err.println("Turnover email delivery failed: " + e.getMessage());
            }
        }

        // 8. RETURN RESPONSE (With demo credentials)
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("message", "City Engineering Office successfully turned over!");
        responseData.put("successorName", savedNewCeo.getFirstName() + " " + savedNewCeo.getLastName());
        responseData.put("successorUsername", savedNewCeo.getUsername());
        responseData.put("tempPassword", tempPassword);

        return ResponseEntity.ok(responseData);
    }

}