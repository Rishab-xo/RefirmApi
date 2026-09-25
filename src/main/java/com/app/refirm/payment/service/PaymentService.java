//package com.app.refirm.payment.service;
//
//import com.app.refirm.payment.entities.PaymentTransaction;
//import com.app.refirm.profile.entities.ProfileDocument;
//import com.app.refirm.payment.dto.PaymentDTO;
//import com.app.refirm.payment.dto.PaymentVerificationDTO;
//import com.app.refirm.payment.repo.PaymentTransactionRepo;
//import com.app.refirm.profile.service.ProfileService;
//import com.app.refirm.profile.service.UserCreditsService;
//import com.razorpay.Order;
//import com.razorpay.RazorpayClient;
//import com.razorpay.Utils;
//import lombok.RequiredArgsConstructor;
//import org.json.JSONObject;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Service;
//
//import java.time.LocalDateTime;
//
//@Service
//@RequiredArgsConstructor
//public class PaymentService {
//
//    private final ProfileService profileService;
//    private final UserCreditsService userCreditsService;
//    private final PaymentTransactionRepo paymentTransactionRepo;
//
//    @Value("${razorpay.api.key}")
//    private String razorpayKeyId;
//    @Value("${razorpay.api.secret}")
//    private String razorpayKeySecret;
//
//    public PaymentDTO createOrder(PaymentDTO paymentDTO){
//
//        try{
//            ProfileDocument currentProfile = profileService.getCurrentProfile();
//            String clerkId = currentProfile.getClerkId();
//
//            RazorpayClient razorpayClient = new RazorpayClient(razorpayKeyId,razorpayKeySecret);
//
//            JSONObject orderReq = new JSONObject();
//            orderReq.put("amount",paymentDTO.getAmount());
//            orderReq.put("currency",paymentDTO.getCurrency());
//            orderReq.put("receipt", "order_"+System.currentTimeMillis());
//
//            Order order = razorpayClient.orders.create(orderReq);
//            String orderId = order.get("id");
//
//            //Create pending transaction record
//            PaymentTransaction transaction = PaymentTransaction.builder()
//                    .clerkId(clerkId)
//                    .orderId(orderId)
//                    .planId(paymentDTO.getPlanId())
//                    .amount(paymentDTO.getAmount())
//                    .currency(paymentDTO.getCurrency())
//                    .status("PENDING")
//                    .transactionDate(LocalDateTime.now())
//                    .userName(currentProfile.getFirstName() + "" + currentProfile.getLastName())
//                    .userEmail(currentProfile.getEmail())
//                    .build();
//
//            paymentTransactionRepo.save(transaction);
//
//            return PaymentDTO.builder()
//                    .orderId(orderId)
//                    .key(razorpayKeyId)
//                    .success(true)
//                    .message("Order Created successfully")
//                    .build();
//
//        }
//        catch (Exception e){
//            return  PaymentDTO.builder()
//                    .success(false)
//                    .message("Error in creating order: "+ e.getMessage())
//                    .build();
//        }
//    }
//
//    public PaymentDTO verifyPayment(PaymentVerificationDTO request){
//        try {
//            ProfileDocument currentProfile = profileService.getCurrentProfile();
//            String clerkId = currentProfile.getClerkId();
//
//            JSONObject options = new JSONObject();
//            options.put("razorpay_order_id", request.getRazorpay_order_id());
//            options.put("razorpay_payment_id", request.getRazorpay_payment_id());
//            options.put("razorpay_signature", request.getRazorpay_signature());
//
//            boolean isValidSignature = Utils.verifyPaymentSignature(options, razorpayKeySecret);
//
//            if (isValidSignature){
//                int creditsToAdd = 5;
//                String plan = "Basic";
//
//                switch (request.getPlanId()){
//                    case "premium":
//                        creditsToAdd = 500;
//                        plan = "PREMIUM";
//                        break;
//                    case "ultimate":
//                        creditsToAdd = 5000;
//                        plan = "PREMIUM";
//                        break;
//                }
//                if (creditsToAdd > 0){
//                    userCreditsService.addCredits(clerkId, creditsToAdd, plan);
//                    updateTransactionStatus(request.getRazorpay_order_id(), "SUCCESS", request.getRazorpay_payment_id(), creditsToAdd);
//                    return PaymentDTO.builder()
//                            .success(true)
//                            .message("Payment verified and credits added successfully")
//                            .credits(userCreditsService.getUserCredits(clerkId).getCredits())
//                            .build();
//                }
//                else {
//                    updateTransactionStatus(request.getRazorpay_order_id(), "FAILED", request.getRazorpay_payment_id(), null);
//                    return PaymentDTO.builder()
//                            .success(true)
//                            .message("Invalid Plan Selected")
//                            .credits(null)
//                            .build();
//                }
//            }
//
//        }
//        catch (Exception e) {
//
//          try {
//              updateTransactionStatus(request.getRazorpay_order_id(), "ERROR", request.getRazorpay_payment_id(), null);
//          }
//          catch (Exception ex){
//              throw new RuntimeException(ex);
//          }
//          return PaymentDTO.builder()
//                  .success(true)
//                  .message("Error verifying payment: " + e.getMessage())
//                  .build();
//
//        }
//        return null;
//    }
//
//    private void updateTransactionStatus(String razorpayOrderId, String status, String razorpayPaymentId, Integer creditsToAdd) {
//
//        paymentTransactionRepo.findByOrderId(razorpayOrderId).ifPresent(transaction -> {
//            transaction.setStatus(status);
//            transaction.setPaymentId(razorpayPaymentId);
//            if (creditsToAdd != null) {
//                transaction.setCreditsAdded(creditsToAdd);
//            }
//            paymentTransactionRepo.save(transaction);
//        });
//
//    }
//
//
//}
