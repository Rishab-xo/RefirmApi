//package com.app.refirm.profile.service;
//
//import com.app.refirm.profile.entities.ProfileDocument;
//import com.app.refirm.profile.dto.ProfileDTO;
//import com.app.refirm.profile.repo.ProfileRepo;
//import lombok.RequiredArgsConstructor;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.core.userdetails.UsernameNotFoundException;
//import org.springframework.stereotype.Service;
//
//import java.time.Instant;
//
//@Service
//@RequiredArgsConstructor
//public class ProfileService {
//
//    private final ProfileRepo profileRepo;
//
//    public ProfileDTO createProfile(ProfileDTO dto){
//
//        if (profileRepo.existsByClerkId(dto.getClerkId())){
//            return updateProfile(dto);
//        }
//
//        ProfileDocument profile = ProfileDocument.builder()
//                .clerkId(dto.getClerkId())
//                .email(dto.getEmail())
//                .firstName(dto.getFirstName())
//                .lastName(dto.getLastName())
//                .photoUrl(dto.getPhotoUrl())
//                .credits(5)
//                .createdAt(Instant.now())
//                .build();
//
//        profile = profileRepo.save(profile);
//
//        return ProfileDTO.builder()
//                .id(profile.getId())
//                .clerkId(profile.getClerkId())
//                .email(profile.getEmail())
//                .firstName(profile.getFirstName())
//                .lastName(profile.getLastName())
//                .photoUrl(profile.getPhotoUrl())
//                .credits(profile.getCredits())
//                .createdAt(profile.getCreatedAt())
//                .build();
//    }
//
//    public ProfileDTO updateProfile(ProfileDTO dto){
//
//        ProfileDocument existingProfile = profileRepo.findByClerkId(dto.getClerkId());
//
//        if (existingProfile != null){
//
//            if (dto.getEmail()!=null && !dto.getEmail().isEmpty()){
//                existingProfile.setEmail(dto.getEmail());
//
//            }if (dto.getFirstName()!=null && !dto.getFirstName().isEmpty()){
//                existingProfile.setFirstName(dto.getFirstName());
//
//            }if (dto.getLastName()!=null && !dto.getLastName().isEmpty()){
//                existingProfile.setLastName(dto.getLastName());
//
//            }if (dto.getPhotoUrl()!=null && !dto.getPhotoUrl().isEmpty()){
//                existingProfile.setPhotoUrl(dto.getPhotoUrl());
//            }
//            profileRepo.save(existingProfile);
//
//            return ProfileDTO.builder()
//                    .id(existingProfile.getId())
//                    .email(existingProfile.getEmail())
//                    .clerkId(existingProfile.getClerkId())
//                    .firstName(existingProfile.getFirstName())
//                    .lastName(existingProfile.getLastName())
//                    .credits(existingProfile.getCredits())
//                    .createdAt(existingProfile.getCreatedAt())
//                    .photoUrl(existingProfile.getPhotoUrl())
//                    .build();
//        }
//        return null;
//    }
//
//    public boolean existsByClerkId(String clerkId){
//        return profileRepo.existsByClerkId(clerkId);
//    }
//
//    public void deleteProfile(String clerkId){
//        ProfileDocument id = profileRepo.findByClerkId(clerkId);
//        if (id!=null){
//            profileRepo.delete(id);
//        }
//    }
//
//    public ProfileDocument getCurrentProfile(){
//        if (SecurityContextHolder.getContext().getAuthentication() == null){
//            throw new UsernameNotFoundException("User not Authenticated");
//        }
//        String clerkId = SecurityContextHolder.getContext().getAuthentication().getName();
//        ProfileDocument profile = profileRepo.findByClerkId(clerkId);
//
//        if (profile == null) {
//            throw new UsernameNotFoundException(
//                    "Profile not found for Clerk ID: " + clerkId
//            );
//        }
//
//        return profile;
//    }
//
//}
