package com.example.videostream.model.dto;

import com.example.videostream.model.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for user information.
 * 
 * This DTO is used for API responses and contains the essential
 * information about a user without exposing sensitive details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {
    
    private Long id;
    private String username;
    private String email;
    private LocalDateTime createdAt;
    private boolean active;
    private User.UserRole role;
    
    /**
     * Convert a User entity to a UserDTO.
     * 
     * @param user The User entity to convert
     * @return A UserDTO with the user's data
     */
    public static UserDTO fromEntity(User user) {
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .createdAt(user.getCreatedAt())
                .active(user.isActive())
                .role(user.getRole())
                .build();
    }
}