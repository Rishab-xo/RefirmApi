package com.app.refirm.file.repo;

import com.app.refirm.file.entities.CloudFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CloudFileRepo extends JpaRepository<CloudFile, UUID> {

    // Renamed to match the 'uploadedBy' field in your CloudFile entity
    Page<CloudFile> findByUploadedBy(String uploadedBy, Pageable pageable);

}