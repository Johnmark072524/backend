package com.roadwise.backend.controller;

import com.roadwise.backend.model.SystemSettings;
import com.roadwise.backend.repository.SystemSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
@CrossOrigin(origins = "*")
public class SystemSettingsController {

    @Autowired
    private SystemSettingsRepository settingsRepository;

    // Get current settings (creates default if it doesn't exist)
    @GetMapping
    public SystemSettings getSettings() {
        return settingsRepository.findById(1L).orElseGet(() -> {
            SystemSettings defaultSettings = new SystemSettings();
            return settingsRepository.save(defaultSettings);
        });
    }

    // Toggle Maintenance Mode
    @PostMapping("/toggle-maintenance")
    public ResponseEntity<SystemSettings> toggleMaintenance(@RequestParam boolean status) {
        SystemSettings settings = getSettings();
        settings.setMaintenanceMode(status);
        settingsRepository.save(settings);
        return ResponseEntity.ok(settings);
    }
}