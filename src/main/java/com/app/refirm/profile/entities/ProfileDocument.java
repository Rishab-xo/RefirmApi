package com.app.refirm.profile.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Entity
@Table(name = "profiles")
public class ProfileDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "clerk_user_id", unique = true, nullable = false)
    private String clerkUserId;

    @Column(name = "email")
    private String email;

    @Column(name = "role")
    private String role; // CITIZEN, ADVOCATE, CA

}
