package com.roadwise.backend.service;

import com.roadwise.backend.model.ActivityLog;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.ActivityLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ActivityLogService {

    @Autowired
    private ActivityLogRepository activityLogRepository;

    /**
     * Centralized logging trigger for User Actions
     */
    public ActivityLog log(User actor, String category, String action, String targetEntity, String description, String status, HttpServletRequest request) {
        ActivityLog log = new ActivityLog();
        log.setTimestamp(LocalDateTime.now());
        log.setCategory(category != null ? category.toUpperCase() : "SYSTEM");
        log.setAction(action != null ? action.toUpperCase() : "GENERAL_ACTION");
        log.setTargetEntity(targetEntity != null ? targetEntity : "N/A");
        log.setDescription(description);
        log.setStatus(status != null ? status.toUpperCase() : "SUCCESS");

        if (actor != null) {
            log.setActorId(actor.getId());
            log.setActorName(formatFullName(actor));
            log.setActorRole(actor.getRole() != null ? actor.getRole() : "User");
            log.setActorOffice(actor.getBarangay() != null ? actor.getBarangay().getBarangayName() : "City Hall Central");
        } else {
            log.setActorId(null);
            log.setActorName("System Automation");
            log.setActorRole("SYSTEM");
            log.setActorOffice("City Hall Central");
        }

        if (request != null) {
            log.setIpAddress(getClientIp(request));
            log.setHttpMethod(request.getMethod());
            log.setUserAgent(request.getHeader("User-Agent"));
        } else {
            log.setIpAddress("127.0.0.1");
            log.setHttpMethod("SYSTEM");
            log.setUserAgent("Internal System Service");
        }

        return activityLogRepository.save(log);
    }

    /**
     * Formats official full name with Middle Initial
     */
    public String formatFullName(User user) {
        if (user == null) return "System";
        String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String middle = user.getMiddleName() != null ? user.getMiddleName().trim() : "";
        String last = user.getLastName() != null ? user.getLastName().trim() : "";

        if (!middle.isEmpty()) {
            return (first + " " + middle.charAt(0) + ". " + last).trim();
        }
        return (first + " " + last).trim();
    }

    /**
     * Extracts true client IP behind reverse proxies/Ngrok
     */
    private String getClientIp(HttpServletRequest request) {
        if (request == null) return "127.0.0.1";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip != null && ip.contains(",") ? ip.split(",")[0].trim() : ip;
    }
}