//package com.app.refirm.file.service;
//
//import com.app.refirm.file.entities.CloudFile;
//import com.app.refirm.file.repo.CloudFileRepo;
//import io.minio.*;
//import lombok.RequiredArgsConstructor;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.stereotype.Service;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.io.InputStream;
//import java.util.UUID;
//
//@Service
//@RequiredArgsConstructor
//public class CloudFileService {
//
//    private final MinioClient minioClient;
//    private final CloudFileRepo cloudFileRepo;
//
//    @Value("${minio.endpoint}")
//    private String endpoint;
//
//    @Value("${minio.bucket-name}")
//    private String bucketName;
//
//
//    public CloudFile uploadFile(MultipartFile file, String ownerId) throws Exception {
//        String originalFileName = file.getOriginalFilename();
//
//        // 1. Generate a unique name to prevent users from overwriting each other's files
//        String objectKey = UUID.randomUUID().toString() + "-" + originalFileName;
//
//        // 2. Stream the file directly into your MinIO Bucket
//        try (InputStream inputStream = file.getInputStream()) {
//            minioClient.putObject(
//                    PutObjectArgs.builder()
//                            .bucket(bucketName)
//                            .object(objectKey)
//                            .stream(inputStream, file.getSize(), -1)
//                            .contentType(file.getContentType())
//                            .build()
//            );
//        }
//
//        // 3. Save the file metadata to MongoDB
//        CloudFile cloudFile = new CloudFile();
//        cloudFile.setOriginalFileName(originalFileName);
//        cloudFile.setObjectKey(objectKey);
//        cloudFile.setContentType(file.getContentType());
//        cloudFile.setSize(file.getSize());
//        cloudFile.setOwnerId(ownerId);
//
//        // Construct the direct download link
//        String fileUrl = endpoint + "/" + bucketName + "/" + objectKey;
//        cloudFile.setUrl(fileUrl);
//
//        return cloudFileRepo.save(cloudFile);
//    }
//
//    // 1. Fetching Files
//    public Page<CloudFile> getUserFiles(String ownerId, int pageNo, int pageSize) {
//        return cloudFileRepo.findByOwnerId(ownerId, PageRequest.of(pageNo, pageSize));
//    }
//
//    // 2. Deleting Files
//    public void deleteFile(String fileId, String ownerId) throws Exception {
//        CloudFile file = cloudFileRepo.findById(fileId)
//                .orElseThrow(() -> new RuntimeException("File not found"));
//
//        // Security check: Ensure the person deleting the file actually owns it
//        if (!file.getOwnerId().equals(ownerId)) {
//            throw new RuntimeException("Unauthorized to delete this file");
//        }
//
//        // Destroy the file inside the MinIO Docker Container
//        minioClient.removeObject(
//                RemoveObjectArgs.builder()
//                        .bucket(bucketName)
//                        .object(file.getObjectKey())
//                        .build()
//        );
//
//        // Delete the metadata from MongoDB
//        cloudFileRepo.delete(file);
//    }
//
//    // 1. Toggle the public/private status of a file
//    public CloudFile togglePublic(String fileId, String ownerId) throws Exception {
//        CloudFile file = cloudFileRepo.findById(fileId)
//                .orElseThrow(() -> new RuntimeException("File not found"));
//
//        // Security check: Only the owner can change this setting
//        if (!file.getOwnerId().equals(ownerId)) {
//            throw new RuntimeException("Unauthorized to modify this file");
//        }
//
//        // Flip the boolean
//        file.setPublic(!file.isPublic());
//        return cloudFileRepo.save(file);
//    }
//
//    // 2. Fetch file metadata ONLY if it is marked as public
//    public CloudFile getPublicFile(String fileId) throws Exception {
//        CloudFile file = cloudFileRepo.findById(fileId)
//                .orElseThrow(() -> new RuntimeException("File not found"));
//
//        if (!file.isPublic()) {
//            throw new RuntimeException("This file is private and cannot be viewed");
//        }
//
//        return file;
//    }
//
////    @PostConstruct
////    public void initBucket() {
////        try {
////            // 1. Check if the bucket exists. If not, create it.
////            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
////            if (!found) {
////                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
////            }
////
////            // 2. Tell MinIO to make this bucket PUBLIC for downloading
////            String policy = """
////                    {
////                      "Version": "2012-10-17",
////                      "Statement": [
////                        {
////                          "Effect": "Allow",
////                          "Principal": "*",
////                          "Action": "s3:GetObject",
////                          "Resource": "arn:aws:s3:::%s/*"
////                        }
////                      ]
////                    }
////                    """.formatted(bucketName);
////
////            minioClient.setBucketPolicy(
////                    SetBucketPolicyArgs.builder()
////                            .bucket(bucketName)
////                            .config(policy)
////                            .build()
////            );
////
////            System.out.println("MinIO Bucket ready and set to PUBLIC!");
////
////        } catch (Exception e) {
////            System.err.println("Could not initialize bucket: " + e.getMessage());
////        }
////    }
//
//}
