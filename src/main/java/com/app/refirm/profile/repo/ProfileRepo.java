package com.app.refirm.profile.repo;

import com.app.refirm.profile.entities.ProfileDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProfileRepo extends JpaRepository<ProfileDocument, UUID> {

    Optional<ProfileDocument> findByEmail(String email);

    Optional<ProfileDocument> findByClerkUserId(String clerkUserId);
    
    boolean existsByClerkUserId(String clerkUserId);

}
