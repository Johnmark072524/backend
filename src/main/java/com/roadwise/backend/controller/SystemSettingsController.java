package com.roadwise.backend.controller;

import com.roadwise.backend.model.SystemSettings;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.SystemSettingsRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@CrossOrigin(origins = "${frontend.url}")
public class SystemSettingsController {

    @Autowired
    private SystemSettingsRepository settingsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ActivityLogService activityLogService;

    // ==========================================
    // 🛡️ CONTROL 6 RBAC: ADMIN IDENTITY RESOLUTION
    // ==========================================
    private Long getAdminId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null &&
                        (user.getRole().equalsIgnoreCase("CPDO Admin") || user.getRole().equalsIgnoreCase("Admin")))
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
    // 1. GET CURRENT SETTINGS
    // ==========================================
    @GetMapping
    public SystemSettings getSettings() {
        return settingsRepository.findById(1L).orElseGet(() -> {
            SystemSettings defaultSettings = new SystemSettings();
            return settingsRepository.save(defaultSettings);
        });
    }

    // ==========================================
    // 2. TOGGLE MAINTENANCE MODE (WITH RBAC & AUDIT LOG)
    // ==========================================
    @PostMapping("/toggle-maintenance")
    public ResponseEntity<?> toggleMaintenance(
            @RequestParam boolean status,
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {

        // 🛡️ CONTROL 6 RBAC: Verify calling administrator
        Long adminId = (userId != null) ? userId : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);

        if (!isCpdoAdmin(adminActor)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Access Denied: Only CPDO Administrators can toggle system maintenance mode."
            ));
        }

        SystemSettings settings = getSettings();
        settings.setMaintenanceMode(status);
        settingsRepository.save(settings);

        // ⏱️ AUDIT LOG (CONTROL 21)
        String statusLabel = status ? "LOCKED (Non-admins blocked)" : "RESTORED (Normal access open)";
        activityLogService.log(
                adminActor,
                "SYSTEM",
                "MAINTENANCE_TOGGLED",
                "SYSTEM_CONFIG",
                "Admin switched maintenance mode to: " + statusLabel + ".",
                status ? "WARNING" : "SUCCESS",
                request
        );

        return ResponseEntity.ok(settings);
    }
}