//package com.app.refirm.payment.controller;
//
//import com.app.refirm.payment.entities.PaymentTransaction;
//import com.app.refirm.profile.entities.ProfileDocument;
//import com.app.refirm.payment.repo.PaymentTransactionRepo;
//import com.app.refirm.profile.service.ProfileService;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.util.List;
//
//@RestController
//@RequiredArgsConstructor
//@RequestMapping("/transactions")
//public class TransactionController {
//
//    private final PaymentTransactionRepo paymentTransactionRepo;
//    private final ProfileService profileService;
//
//    @GetMapping
//    public ResponseEntity<?> getUserTransactions() {
//        ProfileDocument currentProfile = profileService.getCurrentProfile();
//        String clerkId = currentProfile.getClerkId();
//
//        List<PaymentTransaction> transactions = paymentTransactionRepo.findByClerkIdAndStatusOrderByTransactionDateDesc(clerkId, "SUCCESS");
//        return ResponseEntity.ok(transactions);
//    }
//
//}
