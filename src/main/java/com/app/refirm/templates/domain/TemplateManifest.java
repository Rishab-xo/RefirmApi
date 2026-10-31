package com.app.refirm.templates.domain;

import com.app.refirm.triage.domain.LegalDomain;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Immutable master manifest defining a hybrid OKF statutory template.
 *
 * @param templateId                    Unique slug identifier (e.g., "consumer_legal_notice_deficiency")
 * @param title                         Official title of the statutory pleading or agreement
 * @param category                      Broad template grouping
 * @param primaryDomain                 Core substantive legal domain
 * @param targetPerspective             Party role this template serves
 * @param whenToUse                     Executive guidance on jurisdictional triggers
 * @param variantComparisonNote         Disambiguation against close alternative templates
 * @param youWillNeed                   Prerequisite documents and client evidence required before filing
 * @param legalBasis                    Governing statutory sections and judicial precedents
 * @param particulars                   Scalar fact slots requiring deterministic values
 * @param aiBlocks                      Isolated narrative sections synthesized by LLM
 * @param skeletonMarkdown              Complete statutory template containing {{FACT:key}} and {{AI_BLOCK:blockKey}}
 * @param stage                         Procedural/litigation stage represented by this template
 * @param triggerKeywords               Statutory or dispute trigger keywords matching case anomalies or titles
 * @param mandatoryStatutoryIngredients Averments and conditions precedent that MUST be checked and pleaded
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateManifest(
        String templateId,
        String title,
        TemplateCategory category,
        LegalDomain primaryDomain,
        PartyPerspective targetPerspective,
        String whenToUse,
        String variantComparisonNote,
        String youWillNeed,
        String legalBasis,
        List<TemplateParticular> particulars,
        List<TemplateAiBlockSpec> aiBlocks,
        String skeletonMarkdown,
        TemplateStage stage,
        List<String> triggerKeywords,
        List<String> mandatoryStatutoryIngredients
) {
    public TemplateManifest {
        particulars = particulars != null ? List.copyOf(particulars) : List.of();
        aiBlocks = aiBlocks != null ? List.copyOf(aiBlocks) : List.of();
        triggerKeywords = triggerKeywords != null ? List.copyOf(triggerKeywords) : List.of();
        mandatoryStatutoryIngredients = mandatoryStatutoryIngredients != null ? List.copyOf(mandatoryStatutoryIngredients) : List.of();
        if (stage == null) {
            stage = inferStage(category);
        }
    }

    /**
     * Backward-compatible 12-parameter constructor preserving compatibility with Step 3 tests.
     */
    public TemplateManifest(
            String templateId,
            String title,
            TemplateCategory category,
            LegalDomain primaryDomain,
            PartyPerspective targetPerspective,
            String whenToUse,
            String variantComparisonNote,
            String youWillNeed,
            String legalBasis,
            List<TemplateParticular> particulars,
            List<TemplateAiBlockSpec> aiBlocks,
            String skeletonMarkdown
    ) {
        this(
                templateId,
                title,
                category,
                primaryDomain,
                targetPerspective,
                whenToUse,
                variantComparisonNote,
                youWillNeed,
                legalBasis,
                particulars,
                aiBlocks,
                skeletonMarkdown,
                inferStage(category),
                List.of(),
                List.of()
        );
    }

    /**
     * Infers the template stage deterministically from the template category.
     */
    public static TemplateStage inferStage(TemplateCategory category) {
        if (category == null) return TemplateStage.ORIGINAL_FILING;
        return switch (category) {
            case NOTICES -> TemplateStage.PRE_LITIGATION_NOTICE;
            case PLEADINGS_COMPLAINTS -> TemplateStage.ORIGINAL_FILING;
            case REPLIES_DEFENSE -> TemplateStage.DEFENSE_REPLY;
            case APPEALS -> TemplateStage.APPELLATE;
            case AFFIDAVITS_CERTIFICATES -> TemplateStage.EVIDENCE_AFFIDAVIT;
            case AGREEMENTS_DEEDS -> TemplateStage.TRANSACTIONAL_AGREEMENT;
        };
    }

    /**
     * Copy helper method to attach or replace external skeleton markdown.
     */
    public TemplateManifest withSkeletonMarkdown(String newSkeleton) {
        return new TemplateManifest(
                this.templateId,
                this.title,
                this.category,
                this.primaryDomain,
                this.targetPerspective,
                this.whenToUse,
                this.variantComparisonNote,
                this.youWillNeed,
                this.legalBasis,
                this.particulars,
                this.aiBlocks,
                newSkeleton,
                this.stage,
                this.triggerKeywords,
                this.mandatoryStatutoryIngredients
        );
    }
}
