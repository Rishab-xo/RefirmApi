package com.app.refirm.profile.repo;

import com.app.refirm.profile.entities.UserCredits;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserCreditsRepo extends JpaRepository<UserCredits, String> {

        boolean existsByClerkUserId(String clerkUserId);

        Optional<UserCredits> findByClerkUserId(String clerkUserId);

}
