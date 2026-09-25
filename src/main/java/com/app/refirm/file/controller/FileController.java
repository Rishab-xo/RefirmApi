//package com.app.refirm.file.controller;
//
//import com.app.refirm.profile.entities.UserCredits;
//import com.app.refirm.file.entities.CloudFile;
//import com.app.refirm.file.service.CloudFileService;
//import com.app.refirm.profile.service.UserCreditsService;
//import lombok.RequiredArgsConstructor;
//import org.springframework.data.domain.Page;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.security.Principal;
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//@RestController
//@RequiredArgsConstructor
//@RequestMapping("/files")
//public class FileController {
//
//    private final UserCreditsService userCreditsService;
//    private final CloudFileService cloudFileService;
//
//    @PostMapping("/upload")
//    public ResponseEntity<?> uploadFiles(@RequestParam("files") MultipartFile[] files, Principal principal) {
//        try {
//            Map<String, Object> response = new HashMap<>();
//            String ownerId = principal.getName();
//
//            List<CloudFile> uploadedFiles = new ArrayList<>();
//            for (MultipartFile file : files) {
//                CloudFile savedFile = cloudFileService.uploadFile(file, ownerId);
//                uploadedFiles.add(savedFile);
//            }
//
//            UserCredits finalCredits = userCreditsService.getUserCredits();
//            response.put("files", uploadedFiles);
//            response.put("remainingCredits", finalCredits);
//
//            return ResponseEntity.ok(response);
//        } catch (Exception e) {
//            e.printStackTrace();
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(Map.of("message", "Upload failed: " + e.getMessage()));
//        }
//    }
//
//    @GetMapping("/my")
//    public ResponseEntity<?> getFilesForCurrentUser(
//            @RequestParam(defaultValue = "0") int pageNo,
//            @RequestParam(defaultValue = "10") int pageSize,
//            Principal principal) {
//
//        String ownerId = principal.getName();
//        Page paginatedFiles = cloudFileService.getUserFiles(ownerId, pageNo, pageSize);
//        return ResponseEntity.ok(paginatedFiles);
//    }
//
//    @GetMapping("/public/{id}")
//    public ResponseEntity<?> getPublicFile(@PathVariable String id) {
//        try {
//            CloudFile file = cloudFileService.getPublicFile(id);
//            return ResponseEntity.ok(file);
//        } catch (Exception e) {
//            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
//        }
//    }
//
//    @PatchMapping("/{id}/toggle-public")
//    public ResponseEntity togglePublic(@PathVariable String id, java.security.Principal principal) {
//        try {
//            // Get the secure user ID from the token
//            String ownerId = principal.getName();
//
//            CloudFile updatedFile = cloudFileService.togglePublic(id, ownerId);
//            return ResponseEntity.ok(updatedFile);
//        } catch (Exception e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body("Could not toggle status: " + e.getMessage());
//        }
//    }
//
//    @DeleteMapping("/{id}")
//    public ResponseEntity<?> deleteFile(@PathVariable String id, Principal principal) {
//        try {
//            // Grab the secure user ID and pass it to the service to delete the file
//            cloudFileService.deleteFile(id, principal.getName());
//            return ResponseEntity.noContent().build();
//        } catch (Exception e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body("Could not delete file: " + e.getMessage());
//        }
//    }
//
//}
