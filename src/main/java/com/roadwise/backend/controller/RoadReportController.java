package com.roadwise.backend.controller;

import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.repository.RoadReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "*") // This is crucial! It allows your React frontend to talk to this backend without security blocks.
public class RoadReportController {

    @Autowired
    private RoadReportRepository repository;

    // 1. POST: Receive a new report from React and save it to PostgreSQL
    @PostMapping
    public RoadReport createReport(@RequestBody RoadReport report) {
        // Automatically set the status to "Pending" when a new report comes in
        report.setStatus("Pending");
        return repository.save(report);
    }

    // 2. GET: Send all reports back to React so the Admin can see them on the map
    @GetMapping
    public List<RoadReport> getAllReports() {
        return repository.findAll();
    }
}