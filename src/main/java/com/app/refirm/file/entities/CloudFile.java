package com.app.refirm.file.entities;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cloud_files")
@Data
public class CloudFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "minio_object_key", nullable = false)
    private String minioObjectKey;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
