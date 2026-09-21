package com.roadwise.backend.service;

import com.roadwise.backend.model.ReportStatusLog;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.ReportStatusLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReportStatusLogService {

    @Autowired
    private ReportStatusLogRepository logRepository;

    /**
     * Records a status transition into the permanent audit log.
     * Automatically extracts actor name and role if the User object is present.
     */
    public void log(RoadReport report,
                    String action,
                    String previousStatus,
                    String newStatus,
                    String remarks,
                    User actor,
                    String fallbackName,
                    String fallbackRole,
                    String attachmentUrl) {

        String actorName = fallbackName;
        String actorRole = fallbackRole;

        if (actor != null) {
            actorName = ((actor.getFirstName() != null ? actor.getFirstName() : "") + " " +
                    (actor.getLastName() != null ? actor.getLastName() : "")).trim();
            actorRole = actor.getRole();
        }

        ReportStatusLog entry = new ReportStatusLog(
                report,
                action,
                previousStatus,
                newStatus,
                remarks,
                (actorName != null && !actorName.isEmpty()) ? actorName : "System",
                (actorRole != null && !actorRole.isEmpty()) ? actorRole : "SYSTEM",
                attachmentUrl
        );

        logRepository.save(entry);
    }

    /**
     * Returns the chronological lifecycle events for a given report ID.
     */
    public List<ReportStatusLog> getTimeline(Long reportId) {
        return logRepository.findByReport_IdOrderByCreatedAtAsc(reportId);
    }
}