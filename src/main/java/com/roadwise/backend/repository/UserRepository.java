package com.roadwise.backend.repository;

import com.roadwise.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Looks up a user by their username for logging in
    Optional<User> findByUsername(String username);

    // 🚀 NEW: Finds a massive list of users based on their role!
    List<User> findByRole(String role);
    Optional<User> findByEmail(String email);
}