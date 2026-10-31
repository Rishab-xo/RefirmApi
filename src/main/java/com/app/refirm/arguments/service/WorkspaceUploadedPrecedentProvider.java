package com.app.refirm.arguments.service;

import com.app.refirm.arguments.domain.VerifiedPrecedentAuthority;
import com.app.refirm.inspector.domain.CorpusPrecedentEntry;
import com.app.refirm.inspector.service.CorpusPrecedentRegistryService;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.IngestionStatus;
import com.app.refirm.workspace.domain.IracBrief;
import com.app.refirm.workspace.domain.StructuredDocumentSummary;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Concrete precedent provider that resolves verified precedent authorities from:
 * 1. Active case documents uploaded into the workspace vault where documentType == JUDGMENT_PRECEDENT, AND
 * 2. Domain-verified landmark precedents dynamically loaded from CorpusPrecedentRegistryService.
 */
@Component
public class WorkspaceUploadedPrecedentProvider implements PrecedentAuthorityProvider {

    private final WorkspaceDocumentRepository documentRepository;
    private final CorpusPrecedentRegistryService corpusRegistry;

    @Autowired
    public WorkspaceUploadedPrecedentProvider(
            WorkspaceDocumentRepository documentRepository,
            @Autowired(required = false) CorpusPrecedentRegistryService corpusRegistry
    ) {
        this.documentRepository = documentRepository;
        this.corpusRegistry = corpusRegistry;
    }

    public WorkspaceUploadedPrecedentProvider(WorkspaceDocumentRepository documentRepository) {
        this(documentRepository, null);
    }

    @Override
    public List<VerifiedPrecedentAuthority> resolveVerifiedPrecedents(LegalWorkspace workspace) {
        if (workspace == null || workspace.getWorkspaceId() == null) {
            return List.of();
        }

        List<VerifiedPrecedentAuthority> verifiedList = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // 1. Resolve uploaded judgment documents from workspace vault
        List<WorkspaceDocument> documents = documentRepository
                .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(
                        workspace.getWorkspaceId(),
                        workspace.getClerkUserId()
                );

        if (documents != null && !documents.isEmpty()) {
            for (WorkspaceDocument doc : documents) {
                if (doc.getDocumentType() == DocumentType.JUDGMENT_PRECEDENT
                        && doc.getIngestionStatus() == IngestionStatus.READY) {

                    String authId = doc.getDocumentId().toString();
                    if (seenIds.add(authId)) {
                        StructuredDocumentSummary summary = doc.getStructuredSummary();
                        if (summary != null) {
                            String title = (summary.title() != null && !summary.title().isBlank())
                                    ? summary.title()
                                    : doc.getFileName();

                            IracBrief irac = summary.iracBrief();
                            List<String> propositions = (irac != null && !irac.issuesFramed().isEmpty())
                                    ? irac.issuesFramed()
                                    : summary.keyPartiesAndRoles();

                            String ratio = (irac != null && !irac.ratioDecidendi().isBlank())
                                    ? irac.ratioDecidendi()
                                    : summary.executiveGist();

                            verifiedList.add(new VerifiedPrecedentAuthority(
                                    authId,
                                    title,
                                    propositions,
                                    ratio,
                                    "WORKSPACE_UPLOADED_JUDGMENT"
                            ));
                        } else {
                            verifiedList.add(new VerifiedPrecedentAuthority(
                                    authId,
                                    doc.getFileName(),
                                    List.of(),
                                    "",
                                    "WORKSPACE_UPLOADED_JUDGMENT"
                            ));
                        }
                    }
                }
            }
        }

        // 2. Resolve domain-matched landmark precedents from dynamic corpus catalog
        if (corpusRegistry != null) {
            List<CorpusPrecedentEntry> corpusEntries = corpusRegistry.findRelevantForWorkspace(workspace);
            if (corpusEntries != null) {
                for (CorpusPrecedentEntry entry : corpusEntries) {
                    if (seenIds.add(entry.authorityId())) {
                        String citationText = entry.caseTitle();
                        if (entry.primaryCitation() != null && !entry.primaryCitation().isBlank()) {
                            citationText += " (" + entry.primaryCitation() + ")";
                        }
                        verifiedList.add(new VerifiedPrecedentAuthority(
                                entry.authorityId(),
                                citationText,
                                entry.keyPropositions(),
                                entry.summary(),
                                "VERIFIED_CORPUS"
                        ));
                    }
                }
            }
        }

        return List.copyOf(verifiedList);
    }
}
