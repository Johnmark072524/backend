package com.roadwise.backend.repository;

import com.roadwise.backend.model.ReportStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportStatusLogRepository extends JpaRepository<ReportStatusLog, Long> {

    // Retrieves the complete event history for a report, from oldest to newest
    List<ReportStatusLog> findByReport_IdOrderByCreatedAtAsc(Long reportId);
}