//package com.app.refirm.profile.controller;
//
//import com.app.refirm.profile.entities.UserCredits;
//import com.app.refirm.profile.dto.UserCreditsDTO;
//import com.app.refirm.profile.service.UserCreditsService;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("/users")
//@RequiredArgsConstructor
//public class UserCreditsController {
//
//    private final UserCreditsService userCreditsService;
//
//    @GetMapping("/credits")
//    public ResponseEntity<?> getUserCredits() {
//        UserCredits userCredits = userCreditsService.getUserCredits();
//
//        UserCreditsDTO response = UserCreditsDTO.builder()
//                .credits(userCredits.getCredits())
//                .plan(userCredits.getPlan())
//                .build();
//
//        return ResponseEntity.ok(response);
//    }
//
//
//}
