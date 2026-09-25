//package com.app.refirm.profile.service;
//
//import com.app.refirm.profile.entities.UserCredits;
//import com.app.refirm.profile.repo.UserCreditsRepo;
//import lombok.RequiredArgsConstructor;
//import org.springframework.stereotype.Service;
//
//@Service
//@RequiredArgsConstructor
//public class UserCreditsService {
//
//    private final UserCreditsRepo userCreditsRepo;
//    private final ProfileService profileService;
//
//    public UserCredits createInitialCredits(String clerkId){
//
//        UserCredits userCredits = UserCredits.builder()
//                .clerkId(clerkId)
//                .credits(5)
//                .plan("BASIC")
//                .build();
//        return userCreditsRepo.save(userCredits);
//    }
//
//    public UserCredits getUserCredits(String clerkId){
//        return userCreditsRepo.findByClerkId(clerkId)
//                 .orElseGet(()-> createInitialCredits(clerkId));
//    }
//
//    public UserCredits getUserCredits(){
//        String clerkId = profileService.getCurrentProfile().getClerkId();
//        return getUserCredits(clerkId);
//    }
//
//    public boolean hasEnoughCredits(int requiredCredits){
//        UserCredits userCredits = getUserCredits();
//        return userCredits.getCredits() >= requiredCredits;
//    }
//
//    public UserCredits consumeCredit(){
//        UserCredits userCredits = getUserCredits();
//
//        if (userCredits.getCredits()<=0){
//            return null;
//        }
//
//        userCredits.setCredits(userCredits.getCredits()-1);
//        return userCreditsRepo.save(userCredits);
//    }
//
//    public UserCredits addCredits(String clerkId, Integer creditsToAdd, String plan){
//        UserCredits userCredits = userCreditsRepo.findByClerkId(clerkId).orElseGet(() -> createInitialCredits(clerkId));
//        userCredits.setCredits(userCredits.getCredits() + creditsToAdd);
//        userCredits.setPlan(plan);
//        return userCreditsRepo.save(userCredits);
//    }
//
//}
