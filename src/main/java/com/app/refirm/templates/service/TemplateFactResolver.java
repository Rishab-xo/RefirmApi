package com.app.refirm.templates.service;

import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.templates.domain.TemplateManifest;
import com.app.refirm.templates.domain.TemplateParticular;
import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.ExtractedFacts;
import com.app.refirm.triage.domain.PartyEntity;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.workspace.domain.CaseFactLedger;
import com.app.refirm.workspace.domain.PartyPerspective;
import com.app.refirm.workspace.entity.LegalWorkspace;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.Set;

/**
 * Deterministic, 0-AI-token fact resolver that extracts and maps case facts from
 * the workspace's CaseFactLedger and DraftingContext into template particular slots.
 */
@Component
public class TemplateFactResolver {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    public static final Set<String> SUPPORTED_LEDGER_KEYS = Set.of(
            "targetForum.forumName",
            "claimant.name",
            "oppositeParties.primary",
            "oppositeParties.second",
            "oppositeParties.allFormatted",
            "paymentInstrumentHolder.name",
            "disputeAmount",
            "location",
            "limitationMonths",
            "propertySizeSqMeters",
            "primaryStatuteCited",
            "perspectiveClient.name",
            "currentDate",
            "MANUAL"
    );

    /**
     * Resolves all possible particulars for a given template manifest from workspace state.
     * Never guesses or fabricates missing values — only populates non-blank resolved values.
     */
    public Map<String, String> resolveParticularsFromWorkspace(TemplateManifest manifest, LegalWorkspace workspace) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (manifest == null || manifest.particulars() == null || workspace == null) {
            return resolved;
        }

        CaseFactLedger ledger = workspace.getFactLedger();
        ExtractedFacts facts = ledger != null ? ledger.extractedFacts() : null;
        ExtractedFactsDTO ruleFacts = ledger != null ? ledger.ruleEvaluationFacts() : null;
        DraftingContext draftingContext = workspace.getLatestDraftingContext();

        for (TemplateParticular particular : manifest.particulars()) {
            String sourceKey = particular.ledgerSourceKey();
            if (sourceKey == null || sourceKey.isBlank() || "MANUAL".equalsIgnoreCase(sourceKey)) {
                continue;
            }

            String value = resolveSourceValue(sourceKey, workspace, facts, ruleFacts, draftingContext);
            if (value != null && !value.trim().isEmpty()) {
                resolved.put(particular.key(), value.trim());
            }
        }

        return resolved;
    }

    private String resolveSourceValue(
            String sourceKey,
            LegalWorkspace workspace,
            ExtractedFacts facts,
            ExtractedFactsDTO ruleFacts,
            DraftingContext draftingContext
    ) {
        return switch (sourceKey) {
            case "currentDate" -> LocalDate.now().format(DATE_FORMATTER);

            case "perspectiveClient.name" -> {
                if (workspace.getPartyPerspective() == PartyPerspective.OPPOSITE_PARTY_RESPONDENT) {
                    if (facts != null && facts.oppositeParties() != null && !facts.oppositeParties().isEmpty()) {
                        yield facts.oppositeParties().get(0).name();
                    }
                    yield null;
                } else {
                    if (facts != null && facts.claimant() != null && facts.claimant().name() != null) {
                        yield facts.claimant().name();
                    }
                    yield workspace.getClientName();
                }
            }

            case "claimant.name" -> {
                if (facts != null && facts.claimant() != null && facts.claimant().name() != null) {
                    yield facts.claimant().name();
                }
                yield workspace.getClientName();
            }

            case "oppositeParties.primary" -> {
                if (facts != null && facts.oppositeParties() != null && !facts.oppositeParties().isEmpty()) {
                    yield facts.oppositeParties().get(0).name();
                }
                yield null;
            }

            case "oppositeParties.second" -> {
                if (facts != null && facts.oppositeParties() != null && facts.oppositeParties().size() >= 2) {
                    yield facts.oppositeParties().get(1).name();
                }
                yield null;
            }

            case "oppositeParties.allFormatted" -> {
                if (facts != null && facts.oppositeParties() != null && !facts.oppositeParties().isEmpty()) {
                    List<PartyEntity> ops = facts.oppositeParties();
                    if (ops.size() == 1) {
                        yield ops.get(0).name();
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ops.size(); i++) {
                        if (i > 0) sb.append(", ");
                        PartyEntity p = ops.get(i);
                        sb.append(i + 1).append(". ").append(p.name());
                        if (p.role() != null && p.role().name() != null && !p.role().name().isBlank()) {
                            sb.append(" (").append(p.role().name()).append(")");
                        }
                    }
                    yield sb.toString();
                }
                yield null;
            }

            case "paymentInstrumentHolder.name" -> {
                if (facts != null && facts.paymentInstrumentHolder() != null) {
                    yield facts.paymentInstrumentHolder().name();
                }
                yield null;
            }

            case "disputeAmount" -> {
                if (ruleFacts != null && ruleFacts.disputeAmount() != null) {
                    yield String.valueOf(ruleFacts.disputeAmount());
                }
                if (facts != null && facts.estimatedFinancialLoss() != null && facts.estimatedFinancialLoss() > 0) {
                    yield String.format(Locale.ROOT, "%.0f", facts.estimatedFinancialLoss());
                }
                yield null;
            }

            case "location" -> {
                if (ruleFacts != null && ruleFacts.location() != null) {
                    yield ruleFacts.location();
                }
                yield null;
            }

            case "propertySizeSqMeters" -> {
                if (ruleFacts != null && ruleFacts.propertySizeSqMeters() != null) {
                    yield String.valueOf(ruleFacts.propertySizeSqMeters());
                }
                yield null;
            }

            case "targetForum.forumName" -> {
                if (draftingContext != null && draftingContext.targetForum() != null) {
                    yield draftingContext.targetForum().forumName();
                }
                yield null;
            }

            case "limitationMonths" -> {
                if (draftingContext != null && draftingContext.limitationMonths() != null) {
                    int m = draftingContext.limitationMonths();
                    if (m % 12 == 0 && m >= 12) {
                        int years = m / 12;
                        yield (years == 1 ? "1 year (" : years + " years (") + m + " months)";
                    }
                    yield m + " months";
                }
                yield null;
            }

            case "primaryStatuteCited" -> {
                if (draftingContext != null && draftingContext.statutePack() != null && !draftingContext.statutePack().isEmpty()) {
                    StatutePin pin = draftingContext.statutePack().get(0);
                    if (pin.actName() != null && pin.sectionNumber() != null) {
                        yield pin.actName() + " (" + pin.sectionNumber() + ")";
                    } else if (pin.actName() != null) {
                        yield pin.actName();
                    } else if (pin.sectionNumber() != null) {
                        yield pin.sectionNumber();
                    }
                }
                yield null;
            }

            default -> null;
        };
    }
}
