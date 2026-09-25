package com.app.refirm.payment.repo;

import com.app.refirm.payment.entities.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepo extends JpaRepository<PaymentTransaction, String > {

    // Changed ClerkId to ClerkUserId
    List<PaymentTransaction> findByClerkUserId(String clerkUserId);

    // Changed ClerkId to ClerkUserId and TransactionDate to CreatedAt
    List<PaymentTransaction> findByClerkUserIdOrderByCreatedAt(String clerkUserId);

    // Changed ClerkId to ClerkUserId and TransactionDate to CreatedAt
    List<PaymentTransaction> findByClerkUserIdAndStatusOrderByCreatedAtDesc(String clerkUserId, String status);

    Optional<PaymentTransaction> findByRazorpayOrderId(String razorpayOrderId);
}