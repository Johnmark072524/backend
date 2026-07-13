package com.roadwise.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "barangays")
// ⬇️ THIS IS THE MAGIC LINE THAT STOPS THE CRASH ⬇️
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Barangay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String barangayName;
    private String brgyCaptain;
    private String contactNumber;

    // NEW: Added Email Address for official notifications
    private String emailAddress;

    public Barangay() {
    }

    // --- GETTERS AND SETTERS ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBarangayName() { return barangayName; }
    public void setBarangayName(String barangayName) { this.barangayName = barangayName; }

    public String getBrgyCaptain() { return brgyCaptain; }
    public void setBrgyCaptain(String brgyCaptain) { this.brgyCaptain = brgyCaptain; }

    public String getContactNumber() { return contactNumber; }
    public void setContactNumber(String contactNumber) { this.contactNumber = contactNumber; }

    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String emailAddress) { this.emailAddress = emailAddress; }
}