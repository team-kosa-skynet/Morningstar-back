package com.gaebang.backend.domain.interviewTurn.repository;

import com.gaebang.backend.domain.interviewTurn.entity.UploadedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UploadedDocumentRepository extends JpaRepository<UploadedDocument, UUID> {
}