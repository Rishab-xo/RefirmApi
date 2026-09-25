//package com.app.refirm.auth;
//
//import com.app.refirm.profile.dto.ProfileDTO;
//import com.app.refirm.profile.service.ProfileService;
//import com.app.refirm.profile.service.UserCreditsService;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import lombok.RequiredArgsConstructor;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.server.ResponseStatusException;
//
//@RestController
//@RequestMapping("/webhooks")
//@RequiredArgsConstructor
//public class ClerkWebhookController {
//
//    @Value("${clerk.webhook.secret}")
//    private String webhookSecret;
//
//    private final ProfileService service;
//    private final UserCreditsService userCreditsService;
//
//    @PostMapping("/clerk")
//    public ResponseEntity<?> handelWebhook(@RequestHeader("svix-id") String svixId,
//                                           @RequestHeader("svix-timestamp") String svixTimestamp,
//                                           @RequestHeader("svix-signature") String svixSignature,
//                                           @RequestBody String payload){
//
//        System.out.println("=== WEBHOOK TRIGGERED ===");
//
//        if (svixId == null) {
//            System.out.println("WARNING: svix-id header is missing! Clerk might be getting a 400 error.");
//            return ResponseEntity.badRequest().body("Missing svix headers");
//        }
//
//        try {
//            boolean isValid = verfyWebhookSignature(svixId,svixSignature,svixTimestamp,payload);
//                if (!isValid){
//                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid Webhook Signature");
//                }
//                ObjectMapper mapper = new ObjectMapper();
//                JsonNode rootNode = mapper.readTree(payload);
//
//                String eventType = rootNode.path("type").asText();
//
//                switch (eventType){
//                    case "user.created":
//                        handleUserCreated(rootNode.path("data"));
//                        break;
//                    case "user.updated":
//                        handleUserUpdate(rootNode.path("data"));
//                        break;
//                    case "user.deleted":
//                        handleUserDelete(rootNode.path("data"));
//                        break;
//                }
//            System.out.println("Database updated successfully. Sending 200 OK to Clerk.");
//            // Replace return ResponseEntity.ok().build(); with this:
//            return ResponseEntity.ok()
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .body("{\"success\": true}");
//
//        }
//        catch (Exception e){
//            System.out.println("=== WEBHOOK CRASHED ===");
//            e.printStackTrace();
//            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
//        }
//
//    }
//
//    private void handleUserDelete(JsonNode data) {
//        String clerkId = data.path("id").asText();
//        service.deleteProfile(clerkId);
//    }
//
//
//    private void handleUserCreated(JsonNode data) {
//        String clerkId = data.path("id").asText();
//
//        String email = "";
//        JsonNode emailAddresses = data.path("email_addresses");
//        if (emailAddresses.isArray() && (emailAddresses.size() > 0)){
//            email = emailAddresses.get(0).path("email_address").asText();
//        }
//
//        String firstName = data.path("first_name").asText("");
//        String lastName = data.path("last_name").asText("");
//        String photoUrl = data.path("image_url").asText("");
//
//        ProfileDTO newProfile = ProfileDTO.builder()
//                  .clerkId(clerkId)
//                  .email(email)
//                  .firstName(firstName)
//                  .lastName(lastName)
//                  .photoUrl(photoUrl)
//                  .build();
//
//        service.createProfile(newProfile);
//        userCreditsService.createInitialCredits(clerkId);
//
//    }
//
//    private void handleUserUpdate(JsonNode data) {
//
//        String clerkId = data.path("id").asText();
//
//        String email = "";
//        JsonNode emailAddresses = data.path("email_addresses");
//        if (emailAddresses.isArray() && (emailAddresses.size() > 0)){
//            email = emailAddresses.get(0).path("email_address").asText();
//        }
//
//        String firstName = data.path("first_name").asText("");
//        String lastName = data.path("last_name").asText("");
//        String photoUrl = data.path("image_url").asText("");
//
//        ProfileDTO updatedProfile = ProfileDTO.builder()
//                .clerkId(clerkId)
//                .email(email)
//                .firstName(firstName)
//                .lastName(lastName)
//                .photoUrl(photoUrl)
//                .build();
//
//        updatedProfile = service.updateProfile(updatedProfile);
//
//        if (updatedProfile == null){
//            handleUserCreated(data);
//        }
//
//    }
//
//    private boolean verfyWebhookSignature(String svixId, String svixSignature, String svixTimestamp, String payload) {
//        return true;
//    }
//
//}
