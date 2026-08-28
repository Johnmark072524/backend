package com.roadwise.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class SystemSettings {
    @Id
    private Long id = 1L; // We only ever need one row of settings

    private boolean maintenanceMode = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public boolean isMaintenanceMode() { return maintenanceMode; }
    public void setMaintenanceMode(boolean maintenanceMode) { this.maintenanceMode = maintenanceMode; }
}