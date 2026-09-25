//package com.app.refirm.file.controller;
//
//import com.app.refirm.file.entities.CloudFile;
//import com.app.refirm.file.service.CloudFileService;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.security.Principal;
//
//@RestController
//@RequestMapping("/api/v1.0/files")
//@RequiredArgsConstructor
//public class CloudFileContainer {
//
//    private final CloudFileService cloudFileService;
//
//    @PostMapping("/upload")
//    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file, Principal principal) {
//        try {
//            // Spring Security automatically extracts the Clerk User ID from the JWT token
//            // and places it inside the "Principal" object.
//            String ownerId = principal.getName();
//
//            // Pass the file and the secure user ID to our MinIO service
//            CloudFile uploadedFile = cloudFileService.uploadFile(file, ownerId);
//
//            return ResponseEntity.status(HttpStatus.CREATED).body(uploadedFile);
//
//        } catch (Exception e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body("Could not upload file: " + e.getMessage());
//        }
//    }
//}
//
