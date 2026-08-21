package com.roadwise.backend.repository;

import java.util.List;
import com.roadwise.backend.model.RoadReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RoadReportRepository extends JpaRepository<RoadReport, Long> {

    List<RoadReport> findByBarangay_Id(Long barangayId);

    // ==========================================
    // ⚡ HIGH-EFFICIENCY ANNUAL ROLLOVER QUERY
    // ==========================================
    // Archives everything EXCEPT active repairs: 'Dispatched to CEO', 'In Progress', 'Completed' (Pending QA)
    @Modifying
    @Transactional
    @Query("UPDATE RoadReport r SET r.status = 'Archived' " +
            "WHERE LOWER(TRIM(r.status)) NOT IN ('dispatched to ceo', 'in progress', 'completed', 'archived')")
    int archiveAnnualCycleReports();
}