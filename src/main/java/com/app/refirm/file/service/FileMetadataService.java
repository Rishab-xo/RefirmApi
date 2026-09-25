//package com.app.refirm.file.service;
//
//import com.app.refirm.file.dto.FileMetaDataDTO;
//import com.app.refirm.file.entities.FileMetaDataDocument;
//import com.app.refirm.file.repo.FileMetadataRepo;
//import com.app.refirm.profile.entities.ProfileDocument;
//import com.app.refirm.profile.service.ProfileService;
//import com.app.refirm.profile.service.UserCreditsService;
//import io.minio.MinioClient;
//import lombok.RequiredArgsConstructor;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.data.domain.Pageable;
//import org.springframework.data.domain.Sort;
//import org.springframework.stereotype.Service;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.io.IOException;
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Optional;
//import java.util.UUID;
//import java.util.stream.Collectors;
//
//@Service
//@RequiredArgsConstructor
//public class FileMetadataService {
//
//    private final ProfileService profileService;
//    private final UserCreditsService userCreditsService;
//    private final FileMetadataRepo fileMetadataRepo;
//
//    @Value("${minio.bucket-name}")
//    private String bucketName;
//
//    private final MinioClient minioClient;
//
//    public List<FileMetaDataDTO> uploadFiles(MultipartFile files[]) throws IOException {
//        ProfileDocument currentProfile = profileService.getCurrentProfile();
//        List<FileMetaDataDocument> savedFiles = new ArrayList<>();
//
//        if (!userCreditsService.hasEnoughCredits(files.length)){
//            throw new RuntimeException("Not enough credits to upload files. please purchase");
//        }
//
//        for (MultipartFile file : files){
//            // Generate a unique file name
//            String fileName = UUID.randomUUID() + "-" + file.getOriginalFilename();
//
//            try {
//                // 1. UPLOAD DIRECTLY TO FILEBASE (S3) INSTEAD OF LOCAL DISK
//                minioClient.putObject(
//                        io.minio.PutObjectArgs.builder()
//                                .bucket(bucketName)
//                                .object(fileName)
//                                .stream(file.getInputStream(), file.getSize(), -1)
//                                .contentType(file.getContentType())
//                                .build()
//                );
//            } catch (Exception e) {
//                throw new RuntimeException("Error uploading to Cloud Storage: " + e.getMessage());
//            }
//
//            // 2. SAVE METADATA TO MONGODB
//            FileMetaDataDocument fileMetadata = FileMetaDataDocument.builder()
//                    .fileLocation(fileName) // Just store the object key so you can retrieve it later
//                    .name(file.getOriginalFilename())
//                    .size(file.getSize())
//                    .type(file.getContentType())
//                    .clerkId(currentProfile.getClerkId())
//                    .isPublic(false)
//                    .uploadedAt(LocalDateTime.now())
//                    .build();
//
//            userCreditsService.consumeCredit();
//            savedFiles.add(fileMetadataRepo.save(fileMetadata));
//        }
//
//        return savedFiles.stream().map(this::mapToDTO).collect(Collectors.toList());
//    }
//
//    private FileMetaDataDTO mapToDTO(FileMetaDataDocument fileMetaDataDocument) {
//        return FileMetaDataDTO.builder()
//                .id(fileMetaDataDocument.getId())
//                .fileLocation(fileMetaDataDocument.getFileLocation())
//                .name(fileMetaDataDocument.getName())
//                .size(fileMetaDataDocument.getSize())
//                .type(fileMetaDataDocument.getType())
//                .clerkId(fileMetaDataDocument.getClerkId())
//                .isPublic(fileMetaDataDocument.getIsPublic())
//                .uploadedAt(fileMetaDataDocument.getUploadedAt())
//                .build();
//    }
//
//    public Page<FileMetaDataDTO> getFilesPaginated(int page, int size) {
//        ProfileDocument currentProfile = profileService.getCurrentProfile();
//
//        // 1. Create the PageRequest (Note: Spring pages are 0-indexed. Page 0 is the first page)
//        // We sort by 'uploadedAt' descending so the newest files show up first
//        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "uploadedAt"));
//
//        // 2. Fetch the page from MongoDB
//        Page<FileMetaDataDocument> filePage = fileMetadataRepo.findByClerkId(currentProfile.getClerkId(), pageable);
//
//        // 3. Map the Document to DTO (Spring's Page interface does the streaming for you!)
//        return filePage.map(this::mapToDTO);
//    }
//
//    public FileMetaDataDTO getPublicFile(String id){
//        Optional<FileMetaDataDocument> fileOptional = fileMetadataRepo.findById(id);
//        if (fileOptional.isEmpty() || !fileOptional.get().getIsPublic()){
//            throw new RuntimeException("Unable to get the file");
//        }
//
//        FileMetaDataDocument document = fileOptional.get();
//        return mapToDTO(document);
//    }
//
//    public FileMetaDataDTO getDownloadableFile(String id){
//        FileMetaDataDocument file = fileMetadataRepo.findById(id).orElseThrow(() -> new RuntimeException("File not found"));
//        return mapToDTO(file);
//    }
//
//    public void deleteFile(String id){
//        try {
//            ProfileDocument currentProfile = profileService.getCurrentProfile();
//            FileMetaDataDocument file = fileMetadataRepo.findById(id)
//                    .orElseThrow(() -> new RuntimeException("File not found"));
//
//            if (!file.getClerkId().equals(currentProfile.getClerkId())){
//                throw new RuntimeException("File does not belong to Current User");
//            }
//
//            // Delete from Filebase (S3)
//            minioClient.removeObject(
//                    io.minio.RemoveObjectArgs.builder()
//                            .bucket(bucketName)
//                            .object(file.getFileLocation())
//                            .build()
//            );
//
//            // Delete from MongoDB
//            fileMetadataRepo.deleteById(id);
//
//        } catch (Exception e){
//            throw new RuntimeException("Error deleting the File: " + e.getMessage());
//        }
//    }
//
//    public FileMetaDataDTO togglePublic(String id){
//
//        FileMetaDataDocument file = fileMetadataRepo.findById(id).orElseThrow(()-> new RuntimeException("File not found"));
//
//        file.setIsPublic(!file.getIsPublic());
//        fileMetadataRepo.save(file);
//        return mapToDTO(file);
//    }
//
//}
