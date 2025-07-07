package com.example.videostream.repository;

import com.example.videostream.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for User entity operations.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * Find a user by username.
     * 
     * @param username The username to search for
     * @return An Optional containing the user if found
     */
    Optional<User> findByUsername(String username);
    
    /**
     * Find a user by email.
     * 
     * @param email The email to search for
     * @return An Optional containing the user if found
     */
    Optional<User> findByEmail(String email);
    
    /**
     * Check if a username is already taken.
     * 
     * @param username The username to check
     * @return True if the username exists
     */
    boolean existsByUsername(String username);
    
    /**
     * Check if an email is already registered.
     * 
     * @param email The email to check
     * @return True if the email exists
     */
    boolean existsByEmail(String email);
    
    /**
     * Find active content creators with the most uploaded videos.
     * 
     * @param limit The maximum number of users to return
     * @return A list of users ordered by video count
     */
    @Query("SELECT u FROM User u WHERE u.active = true AND u.role = 'CONTENT_CREATOR' " +
           "ORDER BY SIZE(u.uploadedVideos) DESC")
    java.util.List<User> findTopContentCreators(int limit);
}