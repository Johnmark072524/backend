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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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

    @Autowired
    private SupabaseStorageService supabaseStorageService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${frontend.url:https://roadwise-csjdm.com}")
    private String frontendUrl;

    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#]).{8,}$");

    private boolean isValidPassword(String password) {
        return password != null && PASSWORD_PATTERN.matcher(password).matches();
    }

    // ==========================================
    // 🛡️ CONTROL 6 RBAC: CPDO ADMIN VERIFICATION
    // ==========================================
    private Long getAdminId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null && (user.getRole().equalsIgnoreCase("CPDO Admin") || user.getRole().equalsIgnoreCase("Admin")))
                .map(User::getId)
                .findFirst()
                .orElse(1L);
    }

    private boolean isCpdoAdmin(User user) {
        if (user == null || user.getRole() == null) return false;
        String r = user.getRole().trim();
        return r.equalsIgnoreCase("CPDO Admin") || r.equalsIgnoreCase("Admin");
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

            String cloudAvatarUrl = supabaseStorageService.uploadImage(file);

            user.setProfilePicture(cloudAvatarUrl);
            userRepository.save(user);

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
    // 3. SECURE PASSWORD UPDATE (1-HOUR LOCKOUT & COMPLEXITY ENFORCEMENT)
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

        if (!isValidPassword(newPassword)) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "Password does not meet complexity requirements. It must be at least 8 characters long and contain at least one uppercase letter, one digit, and one special character (@$!%*?&#)."));
        }

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

        boolean currentMatches = false;
        String storedPassword = user.getPassword();

        if (storedPassword != null && (storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$") || storedPassword.startsWith("$2y$"))) {
            currentMatches = passwordEncoder.matches(currentPassword, storedPassword);
        } else {
            if (storedPassword != null && storedPassword.equals(currentPassword)) {
                currentMatches = true;
            }
        }

        if (!currentMatches) {
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
        user.setPassword(passwordEncoder.encode(newPassword));
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
    // 5. PROVISION NEW BARANGAY OFFICIAL ACCOUNT (CPDO ADMIN ONLY)
    // ==========================================
    @PostMapping("/register")
    public ResponseEntity<?> registerOfficial(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        String adminIdStr = payload.get("adminId");
        if (adminIdStr == null || adminIdStr.trim().isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Access Denied: Administrative authorization is required to provision municipal accounts."));
        }

        Long adminId;
        try {
            adminId = Long.valueOf(adminIdStr.trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Access Denied: Invalid administrator identity format."));
        }

        Optional<User> adminOpt = userRepository.findById(adminId);
        if (adminOpt.isEmpty() || !isCpdoAdmin(adminOpt.get())) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Access Denied: Only CPDO Administrators are authorized to provision official accounts."));
        }

        User adminActor = adminOpt.get();

        String rawPassword = payload.get("password");
        if (!isValidPassword(rawPassword)) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "Password does not meet complexity requirements. It must be at least 8 characters long and contain at least one uppercase letter, one digit, and one special character (@$!%*?&#)."));
        }

        String username = payload.get("username");
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(400).body(Map.of("error", "Username already exists!"));
        }

        String email = payload.get("email");
        if (email != null && !email.trim().isEmpty() && userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(400).body(Map.of("error", "Email is already registered to another user!"));
        }

        User newUser = new User();
        newUser.setFirstName(payload.get("firstName"));
        newUser.setMiddleName(payload.get("middleName"));
        newUser.setLastName(payload.get("lastName"));
        newUser.setEmail(email);
        newUser.setUsername(username);
        newUser.setPassword(passwordEncoder.encode(rawPassword));
        newUser.setRole(payload.get("role") != null && !payload.get("role").trim().isEmpty() ? payload.get("role") : "BARANGAY");
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
                    "Temporary Password: " + rawPassword + "\n\n" +
                    "Please log in here: " + frontendUrl + "/login.html\n\n" +
                    "For security purposes, please change your password immediately after logging in.\n\n" +
                    "Best regards,\nCPDO Administrator - RoadWise SJDM";

            emailService.sendEmail(savedUser.getEmail(), subject, emailBody);
        }

        String brgyName = savedUser.getBarangay() != null ? savedUser.getBarangay().getBarangayName() : "City Central";
        activityLogService.log(
                adminActor,
                "USER",
                "USER_PROVISIONED",
                "#USR-" + savedUser.getId(),
                "CPDO Admin " + adminActor.getFirstName() + " " + adminActor.getLastName() +
                        " provisioned new " + savedUser.getRole() + " account for " + savedUser.getFirstName() + " " + savedUser.getLastName() + " (Assigned: " + brgyName + ").",
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
    // 7. ADMIN: UPDATE OFFICIAL RECORD & STATUS (CONTROL 6 RBAC PROTECTED)
    // ==========================================
    @PutMapping("/{id}/manage")
    public ResponseEntity<?> manageUserRecord(
            @PathVariable Long id,
            @RequestBody Map<String, String> updates,
            HttpServletRequest request) {

        // 🛡️ CONTROL 6 RBAC: Validate calling administrator
        String adminIdStr = updates.get("adminId");
        Long adminId = (adminIdStr != null && !adminIdStr.trim().isEmpty()) ? Long.valueOf(adminIdStr.trim()) : null;
        User adminActor = (adminId != null) ? userRepository.findById(adminId).orElse(null) : null;

        if (!isCpdoAdmin(adminActor)) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Access Denied: Only CPDO Administrators are authorized to update official records."));
        }

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
    // 8. ADMIN: EMERGENCY PASSWORD RESET (CONTROL 5 & 6 RBAC PROTECTED)
    // ==========================================
    @PutMapping("/{id}/emergency-reset")
    public ResponseEntity<?> emergencyPasswordReset(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> payload,
            HttpServletRequest request) {

        // 🛡️ CONTROL 6 RBAC: Validate calling administrator BEFORE changing any data
        String adminIdStr = (payload != null) ? payload.get("adminId") : null;
        Long adminId = (adminIdStr != null && !adminIdStr.trim().isEmpty()) ? Long.valueOf(adminIdStr.trim()) : null;
        User adminActor = (adminId != null) ? userRepository.findById(adminId).orElse(null) : null;

        if (!isCpdoAdmin(adminActor)) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Access Denied: Only CPDO Administrators are authorized to execute emergency credential resets."));
        }

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        user.setPassword(passwordEncoder.encode("RoadWise2026!"));
        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);
        userRepository.save(user);

        // Bell notification
        notificationService.sendNotification(
                user.getId(),
                "Security Alert",
                "Your password has been reset by the City Administrator. Please update it immediately.",
                "SECURITY"
        );

        // 🚀 Email alert to the official with their temporary access key
        if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
            String subject = "RoadWise - Emergency Password Reset Notice";
            String body = "Hello " + user.getFirstName() + ",\n\n" +
                    "Your RoadWise account password has been reset by the CPDO Administrator.\n\n" +
                    "• Temporary Password: RoadWise2026!\n\n" +
                    "Please log in here: " + frontendUrl + "/login.html\n\n" +
                    "For security purposes, you must change your password immediately upon login.\n\n" +
                    "Best regards,\nCPDO Administrator - City of San Jose del Monte";
            try {
                emailService.sendEmail(user.getEmail(), subject, body);
            } catch (Exception e) {
                System.err.println("Emergency reset email notification failed: " + e.getMessage());
            }
        }

        activityLogService.log(
                adminActor,
                "AUTH",
                "ADMIN_EMERGENCY_PASSWORD_RESET",
                "#USR-" + user.getId(),
                "Admin performed emergency default password reset and lockout clearance for " + user.getFirstName() + " " + user.getLastName() + ".",
                "WARNING",
                request
        );

        return ResponseEntity.ok(Map.of("message", "Password successfully reset to default and account unlocked."));
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
        String memoNumber = payload.get("memoNumber");

        if (currentAdminIdStr == null || currentPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current administrator credentials are required."));
        }

        Long currentAdminId = Long.valueOf(currentAdminIdStr);
        Optional<User> currentAdminOpt = userRepository.findById(currentAdminId);
        if (currentAdminOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Current administrator account not found."));
        }

        User currentAdmin = currentAdminOpt.get();

        if (!isCpdoAdmin(currentAdmin)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only CPDO Administrators can execute administrative turnover."));
        }

        boolean adminMatches = false;
        String storedAdminPass = currentAdmin.getPassword();

        if (storedAdminPass != null && (storedAdminPass.startsWith("$2a$") || storedAdminPass.startsWith("$2b$") || storedAdminPass.startsWith("$2y$"))) {
            adminMatches = passwordEncoder.matches(currentPassword, storedAdminPass);
        } else {
            if (storedAdminPass != null && storedAdminPass.equals(currentPassword)) {
                adminMatches = true;
            }
        }

        if (!adminMatches) {
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

        if (userRepository.findByUsername(newUsername).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username '" + newUsername + "' is already taken."));
        }

        if (userRepository.findByEmail(newEmail).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Official email '" + newEmail + "' is already registered to another user."));
        }

        String tempPassword = "RoadWise@" + (1000 + new Random().nextInt(9000)) + "!";

        User newAdmin = new User();
        newAdmin.setFirstName(newFirstName != null ? newFirstName.trim() : "");
        newAdmin.setMiddleName(newMiddleName != null ? newMiddleName.trim() : "");
        newAdmin.setLastName(newLastName != null ? newLastName.trim() : "");
        newAdmin.setEmail(newEmail != null ? newEmail.trim() : "");
        newAdmin.setUsername(newUsername != null ? newUsername.trim() : "");
        newAdmin.setPhoneNumber(newPhone != null ? newPhone.trim() : "");
        newAdmin.setPassword(passwordEncoder.encode(tempPassword));
        newAdmin.setRole("CPDO Admin");
        newAdmin.setStatus("Active");
        newAdmin.setFailedLoginAttempts(0);
        newAdmin.setProfilePicture("no_image.jpg");

        User savedNewAdmin = userRepository.save(newAdmin);

        currentAdmin.setStatus("Deactivated");
        userRepository.save(currentAdmin);

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

        if (savedNewAdmin.getEmail() != null && !savedNewAdmin.getEmail().isEmpty()) {
            String subject = "RoadWise - Administrative Succession Credentials";
            String body = "Hello " + savedNewAdmin.getFirstName() + ",\n\n" +
                    "Administrative authority for the RoadWise System has been formally transferred to you by " +
                    currentAdmin.getFirstName() + " " + currentAdmin.getLastName() + ".\n\n" +
                    "Login Credentials:\n" +
                    "• Username: " + savedNewAdmin.getUsername() + "\n" +
                    "• Temporary Password: " + tempPassword + "\n\n" +
                    "Please log in here: " + frontendUrl + "/login.html\n\n" +
                    "Please log in and update your security credentials.\n\n" +
                    "Best regards,\nRoadWise Administration - City of San Jose del Monte";
            try {
                emailService.sendEmail(savedNewAdmin.getEmail(), subject, body);
            } catch (Exception e) {
                System.err.println("Turnover email delivery failed: " + e.getMessage());
            }
        }

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

        boolean ceoMatches = false;
        String storedCeoPass = currentCeo.getPassword();

        if (storedCeoPass != null && (storedCeoPass.startsWith("$2a$") || storedCeoPass.startsWith("$2b$") || storedCeoPass.startsWith("$2y$"))) {
            ceoMatches = passwordEncoder.matches(currentPassword, storedCeoPass);
        } else {
            if (storedCeoPass != null && storedCeoPass.equals(currentPassword)) {
                ceoMatches = true;
            }
        }

        if (!ceoMatches) {
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

        if (userRepository.findByUsername(newUsername).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username '" + newUsername + "' is already taken."));
        }

        if (userRepository.findByEmail(newEmail).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Official email '" + newEmail + "' is already registered to another user."));
        }

        String tempPassword = "RoadWise@" + (1000 + new Random().nextInt(9000)) + "!";

        User newCeo = new User();
        newCeo.setFirstName(newFirstName != null ? newFirstName.trim() : "");
        newCeo.setMiddleName(newMiddleName != null ? newMiddleName.trim() : "");
        newCeo.setLastName(newLastName != null ? newLastName.trim() : "");
        newCeo.setEmail(newEmail != null ? newEmail.trim() : "");
        newCeo.setUsername(newUsername != null ? newUsername.trim() : "");
        newCeo.setPhoneNumber(newPhone != null ? newPhone.trim() : "");
        newCeo.setPassword(passwordEncoder.encode(tempPassword));
        newCeo.setRole(currentCeo.getRole() != null ? currentCeo.getRole() : "ENGINEER");
        newCeo.setStatus("Active");
        newCeo.setFailedLoginAttempts(0);
        newCeo.setProfilePicture("no_image.jpg");

        User savedNewCeo = userRepository.save(newCeo);

        currentCeo.setStatus("Deactivated");
        userRepository.save(currentCeo);

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

        if (savedNewCeo.getEmail() != null && !savedNewCeo.getEmail().isEmpty()) {
            String subject = "RoadWise - City Engineer Succession Credentials";
            String body = "Hello " + savedNewCeo.getFirstName() + ",\n\n" +
                    "Engineering administration for the RoadWise System has been formally transferred to you by Engr. " +
                    currentCeo.getFirstName() + " " + currentCeo.getLastName() + ".\n\n" +
                    "Login Credentials:\n" +
                    "• Username: " + savedNewCeo.getUsername() + "\n" +
                    "• Temporary Password: " + tempPassword + "\n\n" +
                    "Please log in here: " + frontendUrl + "/login.html\n\n" +
                    "Please log in and update your security credentials.\n\n" +
                    "Best regards,\nCity Engineering Office - City of San Jose del Monte";
            try {
                emailService.sendEmail(savedNewCeo.getEmail(), subject, body);
            } catch (Exception e) {
                System.err.println("Turnover email delivery failed: " + e.getMessage());
            }
        }

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("message", "City Engineering Office successfully turned over!");
        responseData.put("successorName", savedNewCeo.getFirstName() + " " + savedNewCeo.getLastName());
        responseData.put("successorUsername", savedNewCeo.getUsername());
        responseData.put("tempPassword", tempPassword);

        return ResponseEntity.ok(responseData);
    }
}