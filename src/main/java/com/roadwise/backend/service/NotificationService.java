package com.roadwise.backend.service;

import com.roadwise.backend.model.Notification;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.NotificationRepository;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class NotificationService {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    // ==========================================
    // 📨 THE MASTER FUNCTION TO SEND ALERTS
    // ==========================================
    public void sendNotification(Long recipientId, String title, String message, String type) {

        // 1. Find the user who is supposed to receive this alert
        Optional<User> recipientOpt = userRepository.findById(recipientId);

        if (recipientOpt.isPresent()) {
            // 2. Create the blank notification
            Notification notification = new Notification();

            // 3. Fill it with the details
            notification.setRecipient(recipientOpt.get());
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setType(type);
            notification.setRead(false); // Unread by default!

            // 4. Save it to the database (This triggers the red badge!)
            notificationRepository.save(notification);
        }
    }
}