package com.roadwise.backend.repository;

import com.roadwise.backend.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 1. Fetches all notifications for a specific user, sorted by newest first
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId);

    // 2. Counts how many unread notifications a specific user has (for the red badge)
    long countByRecipientIdAndIsReadFalse(Long recipientId);
}