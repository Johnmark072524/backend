package com.roadwise.backend.repository;

import com.roadwise.backend.model.CityRoad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CityRoadRepository extends JpaRepository<CityRoad, Long> {
    // Spring Data JPA automatically writes all the SQL for finding, saving, and deleting roads just by extending JpaRepository!
}