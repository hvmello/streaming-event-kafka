package com.example.videostream.service.user;

import com.example.videostream.model.dto.UserDTO;
import com.example.videostream.model.entity.User;
import com.example.videostream.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Service for managing users.
 * 
 * This service provides methods for creating, retrieving, updating, and
 * deleting users. It also handles user authentication and authorization.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    
    /**
     * Get a user by ID.
     * 
     * @param userId The ID of the user
     * @return The user DTO
     * @throws ResponseStatusException if the user is not found
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "users", key = "#userId")
    public UserDTO getUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserDTO::fromEntity)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
    }
    
    /**
     * Get a user by username.
     * 
     * @param username The username
     * @return The user DTO
     * @throws ResponseStatusException if the user is not found
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "usersByUsername", key = "#username")
    public UserDTO getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(UserDTO::fromEntity)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
    }
    
    /**
     * Get top content creators based on the number of uploaded videos.
     * 
     * @param limit The maximum number of users to return
     * @return A list of user DTOs
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "topContentCreators", key = "#limit")
    public List<UserDTO> getTopContentCreators(int limit) {
        return userRepository.findTopContentCreators(limit).stream()
                .map(UserDTO::fromEntity)
                .collect(Collectors.toList());
    }
    
    /**
     * Create a new user.
     * 
     * @param username The username
     * @param email The email
     * @param password The password
     * @param role The user role
     * @return The created user DTO
     * @throws ResponseStatusException if the username or email is already taken
     */
    @Transactional
    public UserDTO createUser(String username, String email, String password, User.UserRole role) {
        // Check if username is already taken
        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(BAD_REQUEST, "Username already taken");
        }
        
        // Check if email is already registered
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(BAD_REQUEST, "Email already registered");
        }
        
        // Create the user
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setCreatedAt(LocalDateTime.now());
        user.setActive(true);
        user.setRole(role);
        
        User savedUser = userRepository.save(user);
        log.info("Created new user: {}", savedUser.getId());
        
        return UserDTO.fromEntity(savedUser);
    }
    
    /**
     * Update a user's profile.
     * 
     * @param userId The ID of the user
     * @param email The new email (or null to keep the current value)
     * @param password The new password (or null to keep the current value)
     * @return The updated user DTO
     * @throws ResponseStatusException if the user is not found or the email is already taken
     */
    @Transactional
    @CacheEvict(value = {"users", "usersByUsername"}, key = "#userId")
    public UserDTO updateUser(Long userId, String email, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        
        // Update email if provided
        if (email != null && !email.equals(user.getEmail())) {
            // Check if email is already registered
            if (userRepository.existsByEmail(email)) {
                throw new ResponseStatusException(BAD_REQUEST, "Email already registered");
            }
            user.setEmail(email);
        }
        
        // Update password if provided
        if (password != null) {
            user.setPassword(passwordEncoder.encode(password));
        }
        
        User updatedUser = userRepository.save(user);
        log.info("Updated user: {}", userId);
        
        return UserDTO.fromEntity(updatedUser);
    }
    
    /**
     * Update a user's role.
     * 
     * @param userId The ID of the user
     * @param role The new role
     * @return The updated user DTO
     * @throws ResponseStatusException if the user is not found
     */
    @Transactional
    @CacheEvict(value = {"users", "usersByUsername", "topContentCreators"}, allEntries = true)
    public UserDTO updateUserRole(Long userId, User.UserRole role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        
        user.setRole(role);
        User updatedUser = userRepository.save(user);
        log.info("Updated user role: {} -> {}", userId, role);
        
        return UserDTO.fromEntity(updatedUser);
    }
    
    /**
     * Deactivate a user.
     * 
     * @param userId The ID of the user
     * @throws ResponseStatusException if the user is not found
     */
    @Transactional
    @CacheEvict(value = {"users", "usersByUsername", "topContentCreators"}, allEntries = true)
    public void deactivateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        
        user.setActive(false);
        userRepository.save(user);
        log.info("Deactivated user: {}", userId);
    }
    
    /**
     * Authenticate a user.
     * 
     * @param username The username
     * @param password The password
     * @return The authenticated user DTO
     * @throws ResponseStatusException if the authentication fails
     */
    @Transactional(readOnly = true)
    public UserDTO authenticateUser(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Invalid credentials"));
        
        if (!user.isActive()) {
            throw new ResponseStatusException(BAD_REQUEST, "Account is deactivated");
        }
        
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid credentials");
        }
        
        log.info("User authenticated: {}", username);
        return UserDTO.fromEntity(user);
    }
}