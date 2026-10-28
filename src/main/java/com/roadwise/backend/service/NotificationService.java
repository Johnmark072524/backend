package com.roadwise.backend.service;

import com.roadwise.backend.model.Notification;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.NotificationRepository;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class NotificationService {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    // 🚀 CONTROL 22: Injected WebSocket Broadcaster for real-time telemetry
    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    // ==========================================
    // 📨 THE MASTER FUNCTION TO SEND ALERTS
    // ==========================================
    public void sendNotification(Long recipientId, String title, String message, String type) {

        // 1. Find the user who is supposed to receive this alert
        Optional<User> recipientOpt = userRepository.findById(recipientId);

        if (recipientOpt.isPresent()) {
            // 2. Create the notification entity
            Notification notification = new Notification();
            notification.setRecipient(recipientOpt.get());
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setType(type);
            notification.setRead(false); // Unread by default

            // 3. Save it to PostgreSQL (persists audit and unread count)
            notificationRepository.save(notification);

            // 4. ⚡ Real-Time WebSocket broadcast (Updates the Admin notification bell instantly)
            if (messagingTemplate != null) {
                try {
                    messagingTemplate.convertAndSend("/topic/updates", "REFRESH_DASHBOARDS");
                } catch (Exception e) {
                    System.err.println("Notification WebSocket pulse failed: " + e.getMessage());
                }
            }
        }
    }
}