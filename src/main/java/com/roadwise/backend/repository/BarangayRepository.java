package com.roadwise.backend.repository;

import com.roadwise.backend.model.Barangay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BarangayRepository extends JpaRepository<Barangay, Long> {
    // JpaRepository automatically gives us the findById(Long) method for free!

    boolean existsByBarangayNameIgnoreCase(String barangayName);
    boolean existsByBarangayNameIgnoreCaseAndIdNot(String barangayName, Long id);
}