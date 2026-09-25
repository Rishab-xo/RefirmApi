package com.app.refirm.file.repo;

import com.app.refirm.file.entities.FileMetaDataDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileMetadataRepo extends JpaRepository<FileMetaDataDocument, String> {
    Page<FileMetaDataDocument> findByClerkId(Object clerkId, Pageable pageable);
}
