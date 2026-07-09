package com.roadwise.backend.repository;

import com.roadwise.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // This is our search tool! It looks up a user by their username.
    Optional<User> findByUsername(String username);

}