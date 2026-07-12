package com.roadwise.backend.repository;

import com.roadwise.backend.model.CityRoad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CityRoadRepository extends JpaRepository<CityRoad, Long> {

    List<CityRoad> findByBarangayId(Long barangayId);

}