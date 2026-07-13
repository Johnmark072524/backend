package com.roadwise.backend.repository;

import java.util.List;
import com.roadwise.backend.model.RoadReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoadReportRepository extends JpaRepository<RoadReport, Long> {
    // Spring Boot automatically writes all the SQL for finding, saving, and deleting reports just by extending JpaRepository!

    List<RoadReport> findByBarangay_Id(Long barangayId);
}
