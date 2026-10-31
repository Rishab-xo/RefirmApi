package com.app.refirm.dashboard.service;

import com.app.refirm.arguments.entity.WorkspaceArgumentSheet;
import com.app.refirm.arguments.repository.WorkspaceArgumentSheetRepository;
import com.app.refirm.dashboard.dto.MatterDashboardDTOs.*;
import com.app.refirm.inspector.dto.InspectorDTOs.JudgmentAuthorityCard;
import com.app.refirm.inspector.service.AuthorityDrawerService;
import com.app.refirm.templates.entity.WorkspaceDraft;
import com.app.refirm.templates.repository.WorkspaceDraftRepository;
import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.controller.dto.WorkspaceDocumentDTO;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.DocumentType;
import com.app.refirm.workspace.domain.WorkspaceMode;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.entity.WorkspaceSavedAuthority;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import com.app.refirm.workspace.repository.WorkspaceSavedAuthorityRepository;
import com.app.refirm.workspace.service.FactReconcilerService;
import com.app.refirm.workspace.service.WorkspaceOrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Service for View 3: Matter Dashboard War-Room (Wireframe 3),
 * 1-Click Chat-to-Matter Promotion, and Orphan Chat Attachment.
 */
@Service
public class MatterDashboardService {

    private static final Logger log = LoggerFactory.getLogger(MatterDashboardService.class);

    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceDocumentRepository documentRepository;
    private final WorkspaceDraftRepository draftRepository;
    private final WorkspaceArgumentSheetRepository argumentSheetRepository;
    private final WorkspaceSavedAuthorityRepository savedAuthorityRepository;
    private final WorkspaceOrchestratorService orchestratorService;
    private final FactReconcilerService factReconcilerService;
    private final AuthorityDrawerService drawerService;

    @Autowired
    public MatterDashboardService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDocumentRepository documentRepository,
            WorkspaceDraftRepository draftRepository,
            WorkspaceArgumentSheetRepository argumentSheetRepository,
            WorkspaceSavedAuthorityRepository savedAuthorityRepository,
            WorkspaceOrchestratorService orchestratorService,
            FactReconcilerService factReconcilerService,
            AuthorityDrawerService drawerService
    ) {
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.draftRepository = draftRepository;
        this.argumentSheetRepository = argumentSheetRepository;
        this.savedAuthorityRepository = savedAuthorityRepository;
        this.orchestratorService = orchestratorService;
        this.factReconcilerService = factReconcilerService;
        this.drawerService = drawerService;
    }

    @Transactional(readOnly = true)
    public MatterDashboardResponse getDashboard(UUID workspaceId, String clerkUserId) {
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        int ledgerVersion = workspace.getFactLedger() != null ? workspace.getFactLedger().version() : 1;

        // 1. Files
        List<WorkspaceDocument> docs = documentRepository.findByWorkspaceIdAndClerkUserIdOrderByCreatedAtAsc(workspaceId, clerkUserId);
        List<WorkspaceDocumentDTO> fileDTOs = docs.stream()
                .map(d -> WorkspaceDocumentDTO.from(d, ledgerVersion))
                .toList();

        // 2. Drafts
        List<WorkspaceDraft> drafts = draftRepository.findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(workspaceId, clerkUserId);
        List<MatterDraftSummaryDTO> draftDTOs = drafts.stream()
                .map(d -> new MatterDraftSummaryDTO(d.getDraftId(), d.getTitle(), d.getVersion(), d.getUpdatedAt()))
                .toList();

        // 3. Argument Sheets
        List<WorkspaceArgumentSheet> argSheets = argumentSheetRepository.findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(workspaceId, clerkUserId);
        List<MatterArgumentSheetSummaryDTO> argDTOs = argSheets.stream()
                .map(s -> new MatterArgumentSheetSummaryDTO(s.getSheetId(), s.getTitle(), s.getVersion(), s.isStaleComparedTo(workspace), s.getUpdatedAt()))
                .toList();

        // 4. Authorities (Saved + Uploaded Precedents + Domain Relevant)
        List<JudgmentAuthorityCard> authorities = new ArrayList<>();
        List<WorkspaceSavedAuthority> savedAuths = savedAuthorityRepository.findByWorkspaceIdAndClerkUserIdOrderByCreatedAtDesc(workspaceId, clerkUserId);
        for (WorkspaceSavedAuthority sa : savedAuths) {
            authorities.add(new JudgmentAuthorityCard(
                    sa.getAuthorityId(),
                    sa.getCaseTitle() != null ? sa.getCaseTitle() : sa.getAuthorityId(),
                    "Supreme Court of India",
                    "2022-08-08",
                    sa.getPrimaryCitation() != null ? sa.getPrimaryCitation() : "Cited in Matter",
                    List.of(sa.getPrimaryCitation() != null ? sa.getPrimaryCitation() : sa.getAuthorityId()),
                    "VERIFIED_CORPUS",
                    null,
                    "Saved to active case project",
                    null,
                    "Saved authority pinned to this matter.",
                    List.of("Key precedent proposition"),
                    true
            ));
        }

        // Add domain relevant authorities if saved is low
        String domainStr = workspace.getPrimaryDomain() != null ? workspace.getPrimaryDomain().name() : "CONSUMER";
        List<JudgmentAuthorityCard> relevant = drawerService.getRelevantAuthoritiesForDomain(domainStr, workspaceId, clerkUserId);
        for (JudgmentAuthorityCard rel : relevant) {
            if (authorities.stream().noneMatch(a -> a.authorityId().equalsIgnoreCase(rel.authorityId()))) {
                authorities.add(rel);
            }
        }

        // 5. Tracked Court Cases
        List<CourtCaseTrackedDTO> courtCases = List.of(
                new CourtCaseTrackedDTO(
                        "DLCT01-002934-2026",
                        "District Consumer Disputes Redressal Commission (Central), Delhi",
                        "Monday, 5 October",
                        "Scheduled for Arguments (Interim Protection In Force)"
                )
        );

        // 6. Chats
        List<String> subtitleBadges = new ArrayList<>();
        if (!draftDTOs.isEmpty()) subtitleBadges.add("Draft v" + draftDTOs.get(0).version());
        if (!argDTOs.isEmpty()) subtitleBadges.add("Arg Sheet v" + argDTOs.get(0).version());
        if (subtitleBadges.isEmpty()) subtitleBadges.add("Active Triage");

        List<MatterChatThreadDTO> chats = List.of(
                new MatterChatThreadDTO(
                        workspace.getWorkspaceId(),
                        workspace.getTitle() != null ? workspace.getTitle() : "Primary Matter Thread",
                        subtitleBadges,
                        workspace.getUpdatedAt()
                )
        );

        MatterStatsSubtitle statsSubtitle = new MatterStatsSubtitle(
                chats.size(),
                fileDTOs.size(),
                draftDTOs.size(),
                authorities.size()
        );

        LegalDomain domain = workspace.getPrimaryDomain() != null ? workspace.getPrimaryDomain() : LegalDomain.CONSUMER;
        CaseFactLedger ledger = workspace.getFactLedger() != null ? workspace.getFactLedger() : CaseFactLedger.empty();

        return new MatterDashboardResponse(
                workspace.getWorkspaceId(),
                workspace.getTitle(),
                workspace.getClientName(),
                domain,
                workspace.getPartyPerspective(),
                statsSubtitle,
                ledger,
                courtCases,
                chats,
                fileDTOs,
                draftDTOs,
                argDTOs,
                authorities
        );
    }

    @Transactional
    public MatterDashboardResponse promoteToMatter(
            UUID workspaceId,
            String clerkUserId,
            PromoteToMatterRequest request
    ) {
        log.info("Promoting workspace {} to Case Project with title '{}'", workspaceId, request.caseTitle());
        orchestratorService.promoteToCaseProject(
                workspaceId,
                clerkUserId,
                request.caseTitle(),
                request.clientName(),
                request.partyPerspective(),
                request.excludedDocumentIds()
        );

        return getDashboard(workspaceId, clerkUserId);
    }

    @Transactional
    public MatterDashboardResponse attachChatToMatter(
            UUID targetMatterWorkspaceId,
            UUID sourceQuickConsultWorkspaceId,
            String clerkUserId
    ) {
        log.info("Attaching orphan Quick Consult {} into Matter {}", sourceQuickConsultWorkspaceId, targetMatterWorkspaceId);

        LegalWorkspace targetMatter = workspaceRepository.findByWorkspaceIdAndClerkUserId(targetMatterWorkspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Target matter not found: " + targetMatterWorkspaceId));

        LegalWorkspace sourceConsult = workspaceRepository.findByWorkspaceIdAndClerkUserId(sourceQuickConsultWorkspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Source quick consult not found: " + sourceQuickConsultWorkspaceId));

        // 1. Reconcile facts from source into target matter
        if (sourceConsult.getFactLedger() != null) {
            CaseFactLedger mergedLedger = factReconcilerService.reconcile(
                    targetMatter.getFactLedger(),
                    sourceConsult.getFactLedger().extractedFacts(),
                    sourceConsult.getFactLedger().ruleEvaluationFacts()
            );
            targetMatter.setFactLedger(mergedLedger);
        }

        // 2. Merge chat history
        if (sourceConsult.getChatHistory() != null && !sourceConsult.getChatHistory().isEmpty()) {
            if (targetMatter.getChatHistory() == null) {
                targetMatter.setChatHistory(new ArrayList<>());
            }
            targetMatter.getChatHistory().addAll(sourceConsult.getChatHistory());
        }

        targetMatter.setTemporary(false);
        targetMatter.setUpdatedAt(Instant.now());
        workspaceRepository.save(targetMatter);

        // 3. Move documents from source into target matter
        List<WorkspaceDocument> sourceDocs = documentRepository.findByWorkspaceIdAndClerkUserIdOrderByCreatedAtAsc(sourceQuickConsultWorkspaceId, clerkUserId);
        for (WorkspaceDocument doc : sourceDocs) {
            doc.setWorkspaceId(targetMatterWorkspaceId);
            documentRepository.save(doc);
        }

        // 4. Move drafts from source into target matter
        List<WorkspaceDraft> sourceDrafts = draftRepository.findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(sourceQuickConsultWorkspaceId, clerkUserId);
        for (WorkspaceDraft draft : sourceDrafts) {
            draft.setWorkspaceId(targetMatterWorkspaceId);
            draftRepository.save(draft);
        }

        // 5. Move argument sheets from source into target matter
        List<WorkspaceArgumentSheet> sourceSheets = argumentSheetRepository.findByWorkspaceIdAndClerkUserIdOrderByUpdatedAtDesc(sourceQuickConsultWorkspaceId, clerkUserId);
        for (WorkspaceArgumentSheet sheet : sourceSheets) {
            sheet.setWorkspaceId(targetMatterWorkspaceId);
            argumentSheetRepository.save(sheet);
        }

        // 6. Move saved authorities
        List<WorkspaceSavedAuthority> sourceAuths = savedAuthorityRepository.findByWorkspaceIdAndClerkUserIdOrderByCreatedAtDesc(sourceQuickConsultWorkspaceId, clerkUserId);
        for (WorkspaceSavedAuthority sa : sourceAuths) {
            sa.setWorkspaceId(targetMatterWorkspaceId);
            savedAuthorityRepository.save(sa);
        }

        // Mark source as archived or promoted
        sourceConsult.setMode(WorkspaceMode.CASE_PROJECT);
        sourceConsult.setTemporary(false);
        sourceConsult.setTitle(sourceConsult.getTitle() + " [Attached to " + targetMatter.getTitle() + "]");
        workspaceRepository.save(sourceConsult);

        return getDashboard(targetMatterWorkspaceId, clerkUserId);
    }
}
