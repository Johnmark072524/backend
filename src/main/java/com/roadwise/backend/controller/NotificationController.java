package com.roadwise.backend.controller;

import com.roadwise.backend.model.Notification;
import com.roadwise.backend.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "*") // Adjust based on your security settings
public class NotificationController {

    @Autowired
    private NotificationRepository notificationRepository;

    // ==========================================
    // 1. GET ALL NOTIFICATIONS FOR A USER
    // ==========================================
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Notification>> getUserNotifications(@PathVariable Long userId) {
        List<Notification> notifications = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(userId);
        return ResponseEntity.ok(notifications);
    }

    // ==========================================
    // 2. GET UNREAD BADGE COUNT
    // ==========================================
    @GetMapping("/user/{userId}/unread-count")
    public ResponseEntity<Long> getUnreadCount(@PathVariable Long userId) {
        long count = notificationRepository.countByRecipientIdAndIsReadFalse(userId);
        return ResponseEntity.ok(count);
    }

    // ==========================================
    // 3. MARK A SINGLE NOTIFICATION AS READ
    // ==========================================
    @PutMapping("/{id}/read")
    public ResponseEntity<?> markAsRead(@PathVariable Long id) {
        Optional<Notification> notifOpt = notificationRepository.findById(id);
        if (notifOpt.isPresent()) {
            Notification notif = notifOpt.get();
            notif.setRead(true);
            notificationRepository.save(notif);
            return ResponseEntity.ok("Notification marked as read.");
        }
        return ResponseEntity.notFound().build();
    }

    // ==========================================
    // 4. MARK ALL AS READ (For the Dropdown Button)
    // ==========================================
    @PutMapping("/user/{userId}/read-all")
    public ResponseEntity<?> markAllAsRead(@PathVariable Long userId) {
        // Fetch all notifications, filter for only unread ones, and mark them read
        List<Notification> unreadNotifs = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(userId)
                .stream()
                .filter(n -> !n.isRead())
                .collect(Collectors.toList());

        for (Notification notif : unreadNotifs) {
            notif.setRead(true);
        }

        notificationRepository.saveAll(unreadNotifs);
        return ResponseEntity.ok("All notifications marked as read.");
    }
}