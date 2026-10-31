package com.app.refirm.templates.service;

import com.app.refirm.templates.domain.TemplateAiBlockSpec;
import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.entity.WorkspaceDraft;
import com.app.refirm.templates.repository.WorkspaceDraftRepository;
import com.app.refirm.workspace.entity.LegalWorkspace;
import com.app.refirm.workspace.entity.WorkspaceDocument;
import com.app.refirm.workspace.repository.LegalWorkspaceRepository;
import com.app.refirm.workspace.repository.WorkspaceDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.app.refirm.workspace.util.JsonFenceSanitizer;

import java.time.Instant;
import java.util.*;

/**
 * Core hybrid drafting service managing 0-token initializations, particular updates,
 * locked-block safeguards, single-block refinements, and block-isolated AI narrative synthesis.
 */
@Service
public class HybridTemplateDraftService {

    private static final Logger log = LoggerFactory.getLogger(HybridTemplateDraftService.class);

    private final LegalWorkspaceRepository workspaceRepository;
    private final WorkspaceDraftRepository draftRepository;
    private final WorkspaceDocumentRepository documentRepository;
    private final TemplateRegistryService registryService;
    private final TemplateFactResolver factResolver;
    private final TemplateRenderer renderer;
    private final ChatClient chatClient;

    @Autowired
    public HybridTemplateDraftService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDraftRepository draftRepository,
            WorkspaceDocumentRepository documentRepository,
            TemplateRegistryService registryService,
            TemplateFactResolver factResolver,
            TemplateRenderer renderer,
            @Qualifier("conversationChatClient") ChatClient chatClient
    ) {
        this.workspaceRepository = workspaceRepository;
        this.draftRepository = draftRepository;
        this.documentRepository = documentRepository;
        this.registryService = registryService;
        this.factResolver = factResolver;
        this.renderer = renderer;
        this.chatClient = chatClient;
    }

    public HybridTemplateDraftService(
            LegalWorkspaceRepository workspaceRepository,
            WorkspaceDraftRepository draftRepository,
            WorkspaceDocumentRepository documentRepository,
            TemplateRegistryService registryService,
            TemplateFactResolver factResolver,
            TemplateRenderer renderer
    ) {
        this(workspaceRepository, draftRepository, documentRepository, registryService, factResolver, renderer, null);
    }

    /**
     * Method 1: 0-Token Initialization
     * Resolves particulars deterministically from the workspace state, applies user overrides,
     * and produces version 1 of the draft without consuming LLM tokens.
     */
    @Transactional
    public WorkspaceDraft initializeDraft(
            UUID workspaceId,
            String clerkUserId,
            String templateId,
            Map<String, String> userProvidedOverrides
    ) {
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        TemplateManifest manifest = registryService.getTemplateOrThrow(templateId);

        Map<String, String> resolvedParticulars = new LinkedHashMap<>(
                factResolver.resolveParticularsFromWorkspace(manifest, workspace)
        );

        if (userProvidedOverrides != null && !userProvidedOverrides.isEmpty()) {
            resolvedParticulars.putAll(userProvidedOverrides);
        }

        TemplateRenderer.RenderResult renderResult = renderer.render(
                manifest,
                resolvedParticulars,
                Collections.emptyMap()
        );

        WorkspaceDraft draft = new WorkspaceDraft();
        draft.setWorkspaceId(workspaceId);
        draft.setClerkUserId(clerkUserId);
        draft.setTemplateId(templateId);
        draft.setTitle(manifest.title());
        draft.setVersion(1);
        draft.setFilledParticulars(resolvedParticulars);
        draft.setGeneratedAiBlocks(new LinkedHashMap<>());
        draft.setMissingParticularKeys(renderResult.missingParticularKeys());
        draft.setRenderedMarkdown(renderResult.renderedMarkdown());
        draft.setCreatedAt(Instant.now());
        draft.setUpdatedAt(Instant.now());

        log.info("Initialized draft v1 for workspace '{}' with template '{}'", workspaceId, templateId);
        return draftRepository.save(draft);
    }

    /**
     * Method 2: 0-Token Particular Update (Strictly Preserves AI Blocks!)
     * Merges updated particulars into filledParticulars, re-renders the document,
     * keeps all previously generated AI narrative blocks 100% intact, and increments version.
     */
    @Transactional
    public WorkspaceDraft updateParticulars(
            UUID workspaceId,
            UUID draftId,
            String clerkUserId,
            Map<String, String> updatedParticulars
    ) {
        WorkspaceDraft draft = draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftId, workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + draftId));

        TemplateManifest manifest = registryService.getTemplateOrThrow(draft.getTemplateId());

        if (updatedParticulars != null && !updatedParticulars.isEmpty()) {
            draft.getFilledParticulars().putAll(updatedParticulars);
        }

        // Re-render strictly preserving already generated AI blocks
        TemplateRenderer.RenderResult renderResult = renderer.render(
                manifest,
                draft.getFilledParticulars(),
                draft.getGeneratedAiBlocks()
        );

        draft.setMissingParticularKeys(renderResult.missingParticularKeys());
        draft.setRenderedMarkdown(renderResult.renderedMarkdown());
        draft.setVersion(draft.getVersion() + 1);
        draft.setUpdatedAt(Instant.now());

        log.info("Updated particulars for draft '{}' to v{}", draftId, draft.getVersion());
        return draftRepository.save(draft);
    }

    /**
     * Method 3: Bounded AI Block Synthesis (Strictly Preserves Outer Shell & Verification!)
     * Synthesizes narrative content for isolated {{AI_BLOCK:...}} placeholders,
     * strictly skipping any block that the lawyer has locked.
     */
    @Transactional
    public WorkspaceDraft populateAiNarrativeBlocks(
            UUID workspaceId,
            UUID draftId,
            String clerkUserId,
            String customLawyerInstruction
    ) {
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient is not configured for AI block synthesis");
        }

        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        WorkspaceDraft draft = draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftId, workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + draftId));

        TemplateManifest manifest = registryService.getTemplateOrThrow(draft.getTemplateId());

        List<WorkspaceDocument> documents = documentRepository
                .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId);

        String annexuresList = buildAnnexureSummary(documents);

        for (TemplateAiBlockSpec blockSpec : manifest.aiBlocks()) {
            // Strictly protect locked AI blocks against bulk overwrite
            if (draft.isAiBlockLocked(blockSpec.blockKey())) {
                log.info("Skipping synthesis for locked AI block '{}' in draft '{}'", blockSpec.blockKey(), draftId);
                continue;
            }

            String blockContent = synthesizeBlock(
                    blockSpec,
                    manifest,
                    workspace,
                    draft,
                    annexuresList,
                    customLawyerInstruction
            );
            if (blockContent != null && !blockContent.isBlank()) {
                draft.getGeneratedAiBlocks().put(blockSpec.blockKey(), blockContent.trim());
            }
        }

        TemplateRenderer.RenderResult renderResult = renderer.render(
                manifest,
                draft.getFilledParticulars(),
                draft.getGeneratedAiBlocks()
        );

        draft.setMissingParticularKeys(renderResult.missingParticularKeys());
        draft.setRenderedMarkdown(renderResult.renderedMarkdown());
        draft.setVersion(draft.getVersion() + 1);
        draft.setUpdatedAt(Instant.now());

        log.info("Synthesized AI narrative blocks for draft '{}' to v{}", draftId, draft.getVersion());
        return draftRepository.save(draft);
    }

    /**
     * Method 4: Single-Block Chat Refinement (Left-Pane Editor Chat)
     * Regenerates ONLY the specified blockKey based on current draft, lawyer instructions,
     * facts, statutes, and mandatory statutory ingredients. Optionally locks the block after refinement.
     */
    @Transactional
    public WorkspaceDraft refineSingleAiBlock(
            UUID workspaceId,
            UUID draftId,
            String clerkUserId,
            String blockKey,
            String lawyerInstruction,
            boolean lockAfterRefine
    ) {
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient is not configured for AI block refinement");
        }

        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        WorkspaceDraft draft = draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftId, workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + draftId));

        TemplateManifest manifest = registryService.getTemplateOrThrow(draft.getTemplateId());

        TemplateAiBlockSpec targetSpec = manifest.aiBlocks().stream()
                .filter(s -> s.blockKey().equals(blockKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Block key '" + blockKey + "' not found in template manifest: " + manifest.templateId()));

        List<WorkspaceDocument> documents = documentRepository
                .findByWorkspaceIdAndClerkUserIdAndIncludedInCaseContextTrueOrderByCreatedAtAsc(workspaceId, clerkUserId);
        String annexuresList = buildAnnexureSummary(documents);

        String currentBlockText = draft.getGeneratedAiBlocks() != null ? draft.getGeneratedAiBlocks().get(blockKey) : null;

        String refinedBlock = synthesizeRefinedBlock(
                targetSpec,
                manifest,
                workspace,
                draft,
                annexuresList,
                currentBlockText,
                lawyerInstruction
        );

        if (refinedBlock != null && !refinedBlock.isBlank()) {
            draft.getGeneratedAiBlocks().put(blockKey, refinedBlock.trim());
        }

        if (lockAfterRefine) {
            draft.getLockedAiBlockKeys().add(blockKey);
        }

        TemplateRenderer.RenderResult renderResult = renderer.render(
                manifest,
                draft.getFilledParticulars(),
                draft.getGeneratedAiBlocks()
        );

        draft.setMissingParticularKeys(renderResult.missingParticularKeys());
        draft.setRenderedMarkdown(renderResult.renderedMarkdown());
        draft.setVersion(draft.getVersion() + 1);
        draft.setUpdatedAt(Instant.now());

        log.info("Refined single AI block '{}' for draft '{}' to v{} (locked={})", blockKey, draftId, draft.getVersion(), lockAfterRefine);
        return draftRepository.save(draft);
    }

    /**
     * Method 5: Manual AI Block Update (0-Token Direct Lawyer Edits in Center Editor)
     * Directly updates a single narrative block with manual markdown, optionally locks it,
     * re-renders the complete document deterministically, and increments the draft version.
     */
    @Transactional
    public WorkspaceDraft updateAiBlockManually(
            UUID workspaceId,
            UUID draftId,
            String clerkUserId,
            String blockKey,
            String manualMarkdownContent,
            boolean lockBlock
    ) {
        LegalWorkspace workspace = workspaceRepository.findByWorkspaceIdAndClerkUserId(workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        WorkspaceDraft draft = draftRepository.findByDraftIdAndWorkspaceIdAndClerkUserId(draftId, workspaceId, clerkUserId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + draftId));

        TemplateManifest manifest = registryService.getTemplateOrThrow(draft.getTemplateId());

        boolean blockExists = manifest.aiBlocks().stream().anyMatch(b -> b.blockKey().equals(blockKey));
        if (!blockExists) {
            throw new IllegalArgumentException("Block key '" + blockKey + "' not found in template manifest: " + manifest.templateId());
        }

        draft.getGeneratedAiBlocks().put(blockKey, manualMarkdownContent != null ? manualMarkdownContent.trim() : "");
        if (lockBlock) {
            draft.getLockedAiBlockKeys().add(blockKey);
        }

        TemplateRenderer.RenderResult renderResult = renderer.render(
                manifest,
                draft.getFilledParticulars(),
                draft.getGeneratedAiBlocks()
        );

        draft.setMissingParticularKeys(renderResult.missingParticularKeys());
        draft.setRenderedMarkdown(renderResult.renderedMarkdown());
        draft.setVersion(draft.getVersion() + 1);
        draft.setUpdatedAt(Instant.now());

        log.info("Manually updated AI block '{}' for draft '{}' to v{} (locked={})", blockKey, draftId, draft.getVersion(), lockBlock);
        return draftRepository.save(draft);
    }

    private String buildAnnexureSummary(List<WorkspaceDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "No case documents currently uploaded in workspace vault.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            WorkspaceDocument doc = documents.get(i);
            String gist = doc.getStructuredSummary() != null && doc.getStructuredSummary().executiveGist() != null
                    ? doc.getStructuredSummary().executiveGist()
                    : doc.getFileName();
            sb.append(String.format("Annexure A-%d: %s (%s)\n", i + 1, doc.getFileName(), gist));
        }
        return sb.toString();
    }

    private String synthesizeBlock(
            TemplateAiBlockSpec blockSpec,
            TemplateManifest manifest,
            LegalWorkspace workspace,
            WorkspaceDraft draft,
            String annexuresList,
            String customLawyerInstruction
    ) {
        String systemPrompt = """
                You are a Senior Indian Legal Drafting Specialist drafting a specific, bounded section of a formal legal instrument.
                Block Identifier: "%s"
                Section Title: "%s"
                Drafting Directive: "%s"
                Governing Domain: %s
                Retained Perspective: %s
                Applicable Statutory Basis: %s
                
                STRICT BOUNDARY MANDATES:
                1. Output ONLY the drafted legal content for this specific section.
                2. Do NOT include document titles, case caption, cause title, court headers, index, verification clause, or advocate signatures. The master template shell deterministically handles all structural boundaries.
                3. Ground all factual assertions strictly in the provided case ledger and document annexures. Never invent facts, dates, transaction reference numbers, or monetary sums.
                4. Tone: If CLAIMANT_PETITIONER, assertive and aggrieved pleading tone. If OPPOSITE_PARTY_RESPONDENT, rigorous legal defense with preliminary objections and para-wise denials. If NEUTRAL_EVALUATOR, balanced, binding contractual terms.
                """.formatted(
                blockSpec.blockKey(),
                blockSpec.title(),
                blockSpec.draftingInstruction(),
                manifest.primaryDomain(),
                workspace.getPartyPerspective(),
                manifest.legalBasis()
        );

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("### RESOLVED CASE PARTICULARS:\n");
        if (draft.getFilledParticulars() != null && !draft.getFilledParticulars().isEmpty()) {
            draft.getFilledParticulars().forEach((k, v) -> userPrompt.append("- ").append(k).append(": ").append(v).append("\n"));
        } else {
            userPrompt.append("No particulars supplied.\n");
        }

        if (workspace.getFactLedger() != null) {
            if (workspace.getFactLedger().extractedFacts() != null) {
                userPrompt.append("\n### EXTRACTED FACT GRAPH:\n")
                        .append(workspace.getFactLedger().extractedFacts().toString()).append("\n");
            }
            if (workspace.getFactLedger().ruleEvaluationFacts() != null) {
                userPrompt.append("\n### RULE EVALUATION FACTS:\n")
                        .append(workspace.getFactLedger().ruleEvaluationFacts().toString()).append("\n");
            }
        }

        if (workspace.getLatestDraftingContext() != null) {
            userPrompt.append("\n### DISPUTE NARRATIVE:\n")
                    .append(workspace.getLatestDraftingContext().userNarrative()).append("\n");

            if (workspace.getLatestDraftingContext().statutePack() != null && !workspace.getLatestDraftingContext().statutePack().isEmpty()) {
                userPrompt.append("\n### GOVERNING STATUTE PACK:\n");
                workspace.getLatestDraftingContext().statutePack().forEach(statute ->
                        userPrompt.append("- ").append(statute.actName()).append(" ")
                                .append(statute.sectionNumber()).append(": ")
                                .append(statute.operativeText()).append("\n"));
            }
        }

        if (manifest.mandatoryStatutoryIngredients() != null && !manifest.mandatoryStatutoryIngredients().isEmpty()) {
            userPrompt.append("\n### MANDATORY STATUTORY INGREDIENTS TO PLEAD/COVER:\n");
            manifest.mandatoryStatutoryIngredients().forEach(ing -> userPrompt.append("- ").append(ing).append("\n"));
        }

        userPrompt.append("\n### VERIFIED DOCUMENT ANNEXURES:\n").append(annexuresList).append("\n");

        if (customLawyerInstruction != null && !customLawyerInstruction.isBlank()) {
            userPrompt.append("\n### ADVOCATE SPECIFIC INSTRUCTION:\n").append(customLawyerInstruction.trim()).append("\n");
        }

        userPrompt.append("\nDraft the narrative block for '").append(blockSpec.title()).append("' now:\n");

        try {
            String rawOutput = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt.toString())
                    .call()
                    .content();

            return cleanMarkdownBlock(rawOutput);
        } catch (Exception e) {
            log.error("Failed to synthesize AI block '{}' for draft '{}': {}", blockSpec.blockKey(), draft.getDraftId(), e.getMessage(), e);
            throw new IllegalStateException("AI synthesis failed for block: " + blockSpec.title(), e);
        }
    }

    private String synthesizeRefinedBlock(
            TemplateAiBlockSpec blockSpec,
            TemplateManifest manifest,
            LegalWorkspace workspace,
            WorkspaceDraft draft,
            String annexuresList,
            String currentBlockText,
            String lawyerInstruction
    ) {
        String systemPrompt = """
                You are a Senior Indian Legal Drafting Specialist refining a specific, bounded section of a formal legal instrument.
                Block Identifier: "%s"
                Section Title: "%s"
                Drafting Directive: "%s"
                Governing Domain: %s
                Retained Perspective: %s
                Applicable Statutory Basis: %s
                
                STRICT BOUNDARY MANDATES:
                1. Output ONLY the refined legal content for this specific narrative section.
                2. Do NOT include document titles, case caption, cause title, court headers, index, verification clause, or advocate signatures.
                3. Ground all factual assertions strictly in the provided case ledger and document annexures. Never invent facts, dates, transaction reference numbers, or monetary sums.
                4. Follow the advocate's specific refinement instruction while strictly preserving accurate facts and governing statutory rules.
                """.formatted(
                blockSpec.blockKey(),
                blockSpec.title(),
                blockSpec.draftingInstruction(),
                manifest.primaryDomain(),
                workspace.getPartyPerspective(),
                manifest.legalBasis()
        );

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("### CURRENT DRAFT TEXT FOR THIS BLOCK:\n")
                .append(currentBlockText != null && !currentBlockText.isBlank() ? currentBlockText : "None yet generated.")
                .append("\n\n");

        if (lawyerInstruction != null && !lawyerInstruction.isBlank()) {
            userPrompt.append("### ADVOCATE SPECIFIC REFINEMENT INSTRUCTION:\n")
                    .append(lawyerInstruction.trim()).append("\n\n");
        }

        userPrompt.append("### RESOLVED CASE PARTICULARS:\n");
        if (draft.getFilledParticulars() != null && !draft.getFilledParticulars().isEmpty()) {
            draft.getFilledParticulars().forEach((k, v) -> userPrompt.append("- ").append(k).append(": ").append(v).append("\n"));
        } else {
            userPrompt.append("No particulars supplied.\n");
        }

        if (workspace.getFactLedger() != null) {
            if (workspace.getFactLedger().extractedFacts() != null) {
                userPrompt.append("\n### EXTRACTED FACT GRAPH:\n")
                        .append(workspace.getFactLedger().extractedFacts().toString()).append("\n");
            }
            if (workspace.getFactLedger().ruleEvaluationFacts() != null) {
                userPrompt.append("\n### RULE EVALUATION FACTS:\n")
                        .append(workspace.getFactLedger().ruleEvaluationFacts().toString()).append("\n");
            }
        }

        if (workspace.getLatestDraftingContext() != null) {
            userPrompt.append("\n### DISPUTE NARRATIVE:\n")
                    .append(workspace.getLatestDraftingContext().userNarrative()).append("\n");

            if (workspace.getLatestDraftingContext().statutePack() != null && !workspace.getLatestDraftingContext().statutePack().isEmpty()) {
                userPrompt.append("\n### GOVERNING STATUTE PACK:\n");
                workspace.getLatestDraftingContext().statutePack().forEach(statute ->
                        userPrompt.append("- ").append(statute.actName()).append(" ")
                                .append(statute.sectionNumber()).append(": ")
                                .append(statute.operativeText()).append("\n"));
            }
        }

        if (manifest.mandatoryStatutoryIngredients() != null && !manifest.mandatoryStatutoryIngredients().isEmpty()) {
            userPrompt.append("\n### MANDATORY STATUTORY INGREDIENTS TO PLEAD/COVER:\n");
            manifest.mandatoryStatutoryIngredients().forEach(ing -> userPrompt.append("- ").append(ing).append("\n"));
        }

        userPrompt.append("\n### VERIFIED DOCUMENT ANNEXURES:\n").append(annexuresList).append("\n");

        userPrompt.append("\nGenerate the refined narrative block for '").append(blockSpec.title()).append("' now:\n");

        try {
            String rawOutput = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt.toString())
                    .call()
                    .content();

            return cleanMarkdownBlock(rawOutput);
        } catch (Exception e) {
            log.error("Failed to refine AI block '{}' for draft '{}': {}", blockSpec.blockKey(), draft.getDraftId(), e.getMessage(), e);
            throw new IllegalStateException("AI refinement failed for block: " + blockSpec.title(), e);
        }
    }

    private String cleanMarkdownBlock(String text) {
        return JsonFenceSanitizer.sanitizeMarkdown(text);
    }
}
