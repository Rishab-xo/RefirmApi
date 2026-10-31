package com.app.refirm.arguments.service;

import com.app.refirm.arguments.domain.*;
import com.app.refirm.triage.domain.StatutePin;
import com.app.refirm.workspace.domain.PartyPerspective;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic renderer that formats an ArgumentDossier into a polished,
 * courtroom-ready Markdown argument sheet.
 *
 * Rendering rules enforced:
 * - Section 2B: 3-column APPLICABLE STATUTORY FRAMEWORK table (from statutePack) with single-line rows,
 *   zero newline dumps, dynamically parsed section headings, and operative sentence summaries.
 * - Section 3: Suppresses repetitive "NONE_UPLOADED" / vault-noise anchors per submission;
 *   prints a single one-line note if no evidence is verified; consolidates gaps in Section 6.
 * - Section 4: Rebuttal Matrix rendered cleanly without robotic "PRONG 1" / "PRONG 2" meta-labels.
 * - Section 6: Deduplicated, capped at 6 high-priority concrete documentary items.
 */
@Component
public class ArgumentMarkdownRenderer {

    // ─── PRIMARY ENTRY POINTS (convenience and backward-compatible signatures) ───

    public String renderToMarkdown(ArgumentDossier dossier) {
        return renderToMarkdown(null, PartyPerspective.CLAIMANT_PETITIONER, dossier, false, List.of(), "");
    }

    public String renderToMarkdown(ArgumentDossier dossier, PartyPerspective perspective) {
        return renderToMarkdown(null, perspective, dossier, false, List.of(), "");
    }

    public String renderToMarkdown(String title, PartyPerspective perspective, ArgumentDossier dossier, boolean isStale) {
        return renderToMarkdown(title, perspective, dossier, isStale, List.of());
    }

    // ─── UPGRADED ENTRY POINT (5-arg signature with explicit statute pack) ───

    public String renderToMarkdown(
            String title,
            PartyPerspective perspective,
            ArgumentDossier dossier,
            boolean isStale,
            List<StatutePin> statutePack
    ) {
        return renderToMarkdown(title, perspective, dossier, isStale, statutePack, "");
    }

    // ─── UPGRADED ENTRY POINT (6-arg signature with userQuery for Source-Gap Audit) ───

    public String renderToMarkdown(
            String title,
            PartyPerspective perspective,
            ArgumentDossier dossier,
            boolean isStale,
            List<StatutePin> statutePack,
            String userQuery
    ) {
        StringBuilder sb = new StringBuilder();

        // ─── HEADER & BADGE ───
        String documentTitle;
        if (dossier != null && dossier.openingPitch60Seconds() != null && dossier.openingPitch60Seconds().contains("Strategic Advisory")) {
            documentTitle = "Tactical Hearing Note & Counter-Submissions";
        } else if (title != null && !title.isBlank()) {
            documentTitle = title;
        } else {
            documentTitle = "Courtroom Argument Sheet";
        }
        sb.append("# ").append(documentTitle).append("\n\n");

        String badge = perspective == PartyPerspective.CLAIMANT_PETITIONER
                ? "CLAIMANT / PETITIONER"
                : (perspective == PartyPerspective.OPPOSITE_PARTY_RESPONDENT
                ? "OPPOSITE PARTY / RESPONDENT"
                : "NEUTRAL / COURT ASSISTANT");

        sb.append("**PERSPECTIVE:** `").append(badge).append("`\n\n");

        if (isStale) {
            sb.append("> ⚠️ **STALE WARNING**: The underlying case facts or party perspective have changed since this argument sheet was generated. Re-generate to synchronize with the latest ledger.\n\n");
        }

        if (dossier == null) {
            return sb.toString();
        }

        // ─── 1. 60-SECOND OPENING PITCH ───
        sb.append("### 1. 60-SECOND OPENING COURTROOM PITCH\n\n");
        if (dossier.openingPitch60Seconds() != null && !dossier.openingPitch60Seconds().isBlank()) {
            sb.append(dossier.openingPitch60Seconds().trim()).append("\n\n");
        } else {
            sb.append("*[Opening pitch pending generation]*\n\n");
        }

        // ─── 2. ISSUES FRAMED ───
        sb.append("### 2. ISSUES FRAMED FOR DETERMINATION\n\n");
        if (dossier.framedIssues() != null && !dossier.framedIssues().isEmpty()) {
            for (FramedLegalIssue issue : dossier.framedIssues()) {
                sb.append("- **[").append(issue.issueId()).append("]** ")
                        .append(issue.questionFramed()).append("\n")
                        .append("  *Onus & Governing Standard:* ").append(issue.burdenOfProof()).append("\n\n");
            }
        } else {
            sb.append("*[No framed legal issues]*\n\n");
        }

        // ─── 2B. APPLICABLE STATUTORY FRAMEWORK (3-column table) ───
        List<StatutePin> candidatePack = resolveEffectiveStatutePack(statutePack, dossier);
        Set<ActSectionRef> citedRefs = extractCitedActSectionRefs(dossier, sb.toString());
        List<StatutePin> effectiveStatutePack = filterToActivelyCitedStatutes(candidatePack, citedRefs);
        if (!effectiveStatutePack.isEmpty()) {
            sb.append("### 2B. APPLICABLE STATUTORY FRAMEWORK\n\n");
            sb.append("| Statute | Provision | Statutory Rule & Core Application |\n");
            sb.append("| :--- | :--- | :--- |\n");
            for (StatutePin pin : effectiveStatutePack) {
                String actName = escapeTableCell(formatActNameForTableCell(pin));
                String section = escapeTableCell(formatProvisionForTableCell(pin));
                String operative = escapeTableCell(summarizeStatutePinForTableCell(pin, dossier));
                sb.append("| ").append(actName)
                        .append(" | ").append(section)
                        .append(" | ").append(operative)
                        .append(" |\n");
            }
            sb.append("\n");

            // Explicit Statutory Pack Coverage & Source-Gap Audit
            String coverageBlock = renderStatutorySourceCoverage(effectiveStatutePack, userQuery, dossier);
            if (!coverageBlock.isBlank()) {
                sb.append(coverageBlock).append("\n\n");
            }
        }

        // ─── 3. CORE SUBMISSIONS ───
        sb.append("### 3. CORE LEGAL & FACTUAL SUBMISSIONS\n\n");

        if (dossier.coreSubmissions() != null && !dossier.coreSubmissions().isEmpty()) {
            boolean anyVerifiedEvidence = dossier.coreSubmissions().stream()
                    .anyMatch(sub -> !isUnverifiedOrIrrelevantAnchor(sub.verifiedEvidenceAnchor()));

            if (!anyVerifiedEvidence) {
                sb.append("> *Note: Submissions below are anchored to the pleaded narrative and statutory framework. Documentary exhibits to be annexed prior to filing are consolidated in Section 6.*\n\n");
            }

            for (ArgumentSubmission sub : dossier.coreSubmissions()) {
                sb.append("#### ").append(sub.submissionId()).append(": ").append(sub.title()).append("\n\n");
                sb.append(sub.legalProposition()).append("\n\n");

                if (sub.pinnedStatutes() != null && !sub.pinnedStatutes().isEmpty()) {
                    sb.append("- **Pinned Statutes:** ").append(String.join("; ", sub.pinnedStatutes())).append("\n");
                }
                if (sub.pinnedPrecedents() != null && !sub.pinnedPrecedents().isEmpty()) {
                    sb.append("- **Pinned Precedents:** ").append(String.join("; ", sub.pinnedPrecedents())).append("\n");
                }

                if (!isUnverifiedOrIrrelevantAnchor(sub.verifiedEvidenceAnchor())) {
                    sb.append("- **Verified Evidence Anchor:** `").append(sub.verifiedEvidenceAnchor()).append("`\n");
                }

                if (anyVerifiedEvidence
                        && sub.evidentiaryGapWarning() != null
                        && !sub.evidentiaryGapWarning().isBlank()
                        && !isUnverifiedOrIrrelevantAnchor(sub.evidentiaryGapWarning())) {
                    sb.append("> ⚠️ **Evidentiary Gap:** ").append(sub.evidentiaryGapWarning()).append("\n");
                }

                sb.append("\n");
            }
        } else {
            sb.append("*[No core submissions recorded]*\n\n");
        }

        // ─── 4. REBUTTAL MATRIX (3-Column Scannable Table) ───
        sb.append("### 4. OPPOSITE-PARTY REBUTTAL MATRIX\n\n");
        if (dossier.rebuttalMatrix() != null && !dossier.rebuttalMatrix().isEmpty()) {
            sb.append("| Opponent's Likely Point | Our Courtroom Response | Authority / Evidence to Have Ready |\n");
            sb.append("| :--- | :--- | :--- |\n");
            for (RebuttalMatrixItem reb : dossier.rebuttalMatrix()) {
                String idPrefix = (reb.rebuttalId() != null && !reb.rebuttalId().isBlank())
                        ? "**[" + reb.rebuttalId() + "]** "
                        : "";
                String opponentPoint = idPrefix + (reb.anticipatedOppositeArgument() != null ? reb.anticipatedOppositeArgument().trim() : "");
                String ourResponse = cleanRebuttalProse(reb.ourCounterRebuttal());
                String authorityAnchor = reb.statutoryOrEvidentiaryAnchor();
                if (authorityAnchor == null || isUnverifiedOrIrrelevantAnchor(authorityAnchor)) {
                    authorityAnchor = "Statutory provisions & record";
                }
                sb.append("| ").append(escapeTableCell(opponentPoint))
                        .append(" | ").append(escapeTableCell(ourResponse))
                        .append(" | ").append(escapeTableCell(authorityAnchor))
                        .append(" |\n");
            }
            sb.append("\n");
        } else {
            sb.append("*[Rebuttal matrix empty]*\n\n");
        }

        // ─── 5. STRATEGIC WEAK POINTS & BENCH TRAPS TO PREPARE FOR ───
        sb.append("### 5. STRATEGIC WEAK POINTS & BENCH TRAPS TO PREPARE FOR\n\n");
        if (dossier.benchGrillQuestions() != null && !dossier.benchGrillQuestions().isEmpty()) {
            int qIndex = 1;
            for (BenchQuestionItem bq : dossier.benchGrillQuestions()) {
                sb.append("**Q").append(qIndex++).append(" (From the Bench):** *\"")
                        .append(bq.questionFromJudge()).append("\"*\n\n")
                        .append("**Recommended Courtroom Answer:**\n")
                        .append(bq.recommendedCourtroomAnswer()).append("\n\n")
                        .append("**Authority Pin:** `").append(bq.authorityPin()).append("`\n\n")
                        .append("---\n\n");
            }
        } else {
            sb.append("*[Bench grill questions empty]*\n\n");
        }

        // ─── 6. FACTS & DOCUMENTS NEEDED TO FINALISE (capped at 6, deduplicated) ───
        sb.append("### 6. FACTS & DOCUMENTS NEEDED TO FINALISE\n\n");
        if (dossier.preHearingEvidenceChecklist() != null && !dossier.preHearingEvidenceChecklist().isEmpty()) {
            List<String> dedupedChecklist = dossier.preHearingEvidenceChecklist().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .limit(6)
                    .collect(Collectors.toList());

            for (String item : dedupedChecklist) {
                sb.append("- [ ] ").append(item).append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("*[No checklist items]*\n\n");
        }

        return scrubLegacyVaultSentences(sb.toString());
    }

    // ─── FORMATTING HELPERS FOR TABLE 2B ───

    public static String formatActNameForTableCell(StatutePin pin) {
        if (pin == null) return "The Consumer Protection Act, 2019";
        String act = pin.actName();
        if (act == null || act.isBlank()) {
            return "The Consumer Protection Act, 2019";
        }
        if (!act.contains("_") && !act.contains("-") && act.contains(" ")) {
            return act.trim();
        }
        return com.app.refirm.inspector.service.InspectorService.humanizeActTitle(act);
    }

    public static String formatProvisionForTableCell(StatutePin pin) {
        if (pin == null) return "Applicable Provision";
        String rawSec = pin.sectionNumber() != null ? pin.sectionNumber().trim() : "";
        if (rawSec.isBlank()) return "Applicable Provision";

        // If rawSec is raw like "section_2", "section_34", "sec_2", or "2", "34"
        if (rawSec.contains("_") || rawSec.matches("(?i)^section_?\\d+$") || rawSec.matches("^\\d+$")) {
            com.app.refirm.inspector.service.InspectorService.ParsedStatuteMetadata parsed =
                    com.app.refirm.inspector.service.InspectorService.parseStatutePin(pin);
            String secNum = parsed.sectionNumber();
            String heading = parsed.sectionHeading();
            if (heading != null && !heading.isBlank()) {
                return "Section " + secNum + " — " + heading;
            } else {
                return "Section " + secNum;
            }
        }
        return rawSec;
    }

    public static String truncateAtWordBoundary(String text, int maxChars) {
        if (text == null) return "";
        String s = text.trim();
        if (s.length() <= maxChars) {
            return s;
        }
        int limit = maxChars - 3;
        if (limit <= 0) limit = maxChars;
        int lastSpace = s.lastIndexOf(' ', limit);
        if (lastSpace != -1) {
            return s.substring(0, lastSpace).stripTrailing() + "...";
        }
        int spaceWithin = s.lastIndexOf(' ', maxChars);
        if (spaceWithin != -1) {
            return s.substring(0, spaceWithin).stripTrailing() + "...";
        }
        return s.substring(0, Math.min(s.length(), limit)).stripTrailing() + "...";
    }

    public static String cleanBareActText(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return "";
        String text = rawContent;

        // 1. Strip internal prompt anchor block: ### PINNED STATUTORY ANCHORS...
        text = text.replaceAll("(?s)###\\s*PINNED STATUTORY ANCHORS.*?(?=(?:\\r?\\n){2}|---|#|\\Z)", " ");

        // 2. Strip YAML frontmatter block: --- ... ---
        if (text.startsWith("---")) {
            int end = text.indexOf("---", 3);
            if (end != -1) {
                text = text.substring(end + 3);
            }
        }
        text = text.replaceAll("(?s)^---.*?---\\s*", " ");

        // 3. Strip Markdown heading lines: (?m)^#+\s+.*$
        text = text.replaceAll("(?m)^#+\\s+.*$", " ");

        // 4. Strip generic legislative opening formulas
        text = text.replaceAll("(?i)In this (?:Act|Code|Sanhita|Adhiniyam),?\\s+unless the context otherwise requires,?\\s*(?:---|--|—|–|:-|-)?\\s*", " ");
        text = text.replaceAll("(?i)Unless the context otherwise requires,?\\s*(?:---|--|—|–|:-|-)?\\s*", " ");

        // 5. Collapse all line breaks (\r, \n) and consecutive spaces into a single space (" ") BEFORE extracting
        text = text.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();

        // 6. Universally strip leading section identifier and marginal heading prefix
        // Handles: [238A. Limitation.--, **[238A. Limitation.** --, [1[238A., [NA. Title.—, 10A. Title.---, [10A. Title.—
        text = text.replaceAll("(?i)^\\s*(?:\\*+\\s*)?(?:\\[\\d+\\s*)?\\[?\\*?\\*?\\s*[0-9A-Za-z]+(?:\\([0-9A-Za-z]+\\))?\\]?\\*?\\*?\\.\\s*(?:[^.—–\\r\\n]{1,120}?(?:\\.—|\\.--|---|--|—|–|:-)\\s*)?", "");
        text = text.replaceAll("(?i)^\\s*(?:[A-Za-z0-9\\s,–\\-'\"]{1,60}?\\*+\\s*(?:--|—|–|:-|-)\\s*|\\*+\\s*(?:--|—|–|:-|-)\\s*)", "").trim();
        text = text.replaceAll("^\\s*(?:\\]|\\*+|--+|—|–|\\.|-)+\\s*", "").trim();
        text = text.replaceAll("\\]+$", "").trim();

        return text;
    }

    public static String summarizeStatutePinForTableCell(StatutePin pin, ArgumentDossier dossier) {
        if (pin == null) return "";
        String secNum = resolveBaseSectionNumber(pin);
        String pinAct = pin.actName() != null ? pin.actName().trim() : "";
        String content = pin.markdownContent() != null && !pin.markdownContent().isBlank()
                ? pin.markdownContent()
                : (pin.operativeText() != null ? pin.operativeText() : "");
        if (content.isBlank()) return "";

        // 1. Dynamically inspect which specific sub-clauses of pin's section number
        // are actually cited in dossier strictly for this pin's Act
        Set<String> citedSubClauseIds = extractCitedSubClausesForSection(secNum, dossier, pin);

        if (!citedSubClauseIds.isEmpty()) {
            List<String> extractedClauses = extractSubClauseDefinitions(secNum, citedSubClauseIds, content, pinAct);
            if (!extractedClauses.isEmpty()) {
                List<String> capped = extractedClauses.stream().limit(4).toList();
                String joined = String.join("; ", capped);
                joined = truncateAtWordBoundary(joined, 240);
                return escapeTableCell(joined);
            }
        }

        // 2. If no sub-clauses are cited, extract first complete operative sentence (<= 280 chars)
        String cleanOperative = stripPreambleAndExtractFirstOperativeSentence(content, secNum);
        return escapeTableCell(cleanOperative);
    }

    public static Set<String> extractCitedSubClausesForSection(String secNum, ArgumentDossier dossier, StatutePin pin) {
        Set<String> subClauses = new LinkedHashSet<>();
        if (secNum == null || secNum.isBlank()) return subClauses;
        String pinAct = pin != null && pin.actName() != null ? pin.actName().trim() : "";

        // If dossier explicitly cites statutes, verify if this pin's Act is cited for this section
        if (dossier != null && !pinAct.isBlank()) {
            Set<ActSectionRef> citedRefs = extractCitedActSectionRefs(dossier);
            if (!citedRefs.isEmpty()) {
                List<ActSectionRef> refsForSec = citedRefs.stream()
                        .filter(r -> r.sectionNumber().equalsIgnoreCase(secNum))
                        .toList();
                if (!refsForSec.isEmpty()) {
                    boolean hasExplicitAct = refsForSec.stream().anyMatch(r -> !r.actKey().isBlank());
                    if (hasExplicitAct) {
                        boolean matchesPinAct = refsForSec.stream()
                                .filter(r -> !r.actKey().isBlank())
                                .anyMatch(r -> isActMatch(r.actKey(), pinAct));
                        if (!matchesPinAct) {
                            return subClauses;
                        }
                    }
                }
            }
        }

        java.util.function.BiConsumer<String, String> scanWithAct = (text, contextualAct) -> {
            if (text == null || text.isBlank()) return;
            if (!pinAct.isBlank() && contextualAct != null && !contextualAct.isBlank()) {
                if (!isActMatch(contextualAct, pinAct)) {
                    return;
                }
            }

            // Pattern 1: Section 3(8), 3(10), 3(11), 3(12), 2(7)(ii), 2(11)
            Pattern p1 = Pattern.compile("(?i)(?:\\b(?:sections?|sec\\.?)\\s*)?\\b" + Pattern.quote(secNum) + "\\s*\\(([0-9a-zA-Z]+(?:\\([0-9a-zA-Z]+\\))*)\\)");
            Matcher m1 = p1.matcher(text);
            while (m1.find()) {
                subClauses.add(m1.group(1).trim());
            }

            // Pattern 2: Pinned anchor format: Section 2(7)(ii) [Consumer & Beneficiary Standing]
            Pattern pAnchor = Pattern.compile("(?i)(?:^|\\n)\\s*[-*]?\\s*Section\\s+" + Pattern.quote(secNum) + "\\s*\\(([0-9a-zA-Z]+(?:\\([0-9a-zA-Z]+\\))*)\\)");
            Matcher mAnchor = pAnchor.matcher(text);
            while (mAnchor.find()) {
                subClauses.add(mAnchor.group(1).trim());
            }

            // Pattern 3: Comma separated sub-clauses following base section: Section 3(8), (10), (11), (12)
            Pattern pSeries = Pattern.compile("(?i)(?:\\b(?:sections?|sec\\.?)\\s*)?\\b" + Pattern.quote(secNum) + "\\s*\\(([0-9a-zA-Z]+)\\)((?:\\s*,?\\s*(?:and|&)?\\s*\\([0-9a-zA-Z]+\\))+)");
            Matcher mSeries = pSeries.matcher(text);
            if (mSeries.find()) {
                subClauses.add(mSeries.group(1).trim());
                Matcher nextParens = Pattern.compile("\\(([0-9a-zA-Z]+)\\)").matcher(mSeries.group(2));
                while (nextParens.find()) {
                    subClauses.add(nextParens.group(1).trim());
                }
            }

            // Pattern 4: "clause (8) of section 3" or "sub-section (8) of section 3"
            Pattern pClauseOf = Pattern.compile("(?i)(?:clauses?|sub-?sections?|cls?\\.?)\\s*\\(?([0-9a-zA-Z]+)\\)?\\s+of\\s+(?:sections?|sec\\.?)\\s*" + Pattern.quote(secNum));
            Matcher mClauseOf = pClauseOf.matcher(text);
            while (mClauseOf.find()) {
                subClauses.add(mClauseOf.group(1).trim());
            }
        };

        if (pin != null) {
            scanWithAct.accept(pin.sectionNumber(), pinAct);
            scanWithAct.accept(pin.parentContext(), pinAct);
            if (pin.operativeText() != null) {
                scanWithAct.accept(cleanBareActText(pin.operativeText()), pinAct);
            }
        }

        if (dossier != null) {
            if (dossier.openingPitch60Seconds() != null) {
                String localAct = extractActFromStatuteString(dossier.openingPitch60Seconds());
                scanWithAct.accept(dossier.openingPitch60Seconds(), (localAct != null && !localAct.isBlank()) ? localAct : null);
            }
            if (dossier.framedIssues() != null) {
                for (FramedLegalIssue fi : dossier.framedIssues()) {
                    String a1 = extractActFromStatuteString(fi.questionFramed());
                    scanWithAct.accept(fi.questionFramed(), (a1 != null && !a1.isBlank()) ? a1 : null);
                    String a2 = extractActFromStatuteString(fi.burdenOfProof());
                    scanWithAct.accept(fi.burdenOfProof(), (a2 != null && !a2.isBlank()) ? a2 : null);
                }
            }
            if (dossier.coreSubmissions() != null) {
                for (ArgumentSubmission sub : dossier.coreSubmissions()) {
                    String subAct = null;
                    if (sub.pinnedStatutes() != null) {
                        for (String s : sub.pinnedStatutes()) {
                            String a = extractActFromStatuteString(s);
                            if (a != null && !a.isBlank()) {
                                subAct = a;
                                break;
                            }
                        }
                    }
                    scanWithAct.accept(sub.title(), subAct);
                    scanWithAct.accept(sub.legalProposition(), subAct);
                    if (sub.pinnedStatutes() != null) {
                        for (String s : sub.pinnedStatutes()) {
                            String actInStat = extractActFromStatuteString(s);
                            scanWithAct.accept(s, (actInStat != null && !actInStat.isBlank()) ? actInStat : null);
                        }
                    }
                    scanWithAct.accept(sub.verifiedEvidenceAnchor(), subAct);
                }
            }
            if (dossier.rebuttalMatrix() != null) {
                for (RebuttalMatrixItem reb : dossier.rebuttalMatrix()) {
                    String actInStat = extractActFromStatuteString(reb.statutoryOrEvidentiaryAnchor());
                    String actToPass = (actInStat != null && !actInStat.isBlank()) ? actInStat : null;
                    scanWithAct.accept(reb.anticipatedOppositeArgument(), actToPass);
                    scanWithAct.accept(reb.ourCounterRebuttal(), actToPass);
                    scanWithAct.accept(reb.statutoryOrEvidentiaryAnchor(), actToPass);
                }
            }
            if (dossier.benchGrillQuestions() != null) {
                for (BenchQuestionItem bq : dossier.benchGrillQuestions()) {
                    String actInStat = extractActFromStatuteString(bq.authorityPin());
                    String actToPass = (actInStat != null && !actInStat.isBlank()) ? actInStat : null;
                    scanWithAct.accept(bq.questionFromJudge(), actToPass);
                    scanWithAct.accept(bq.recommendedCourtroomAnswer(), actToPass);
                    scanWithAct.accept(bq.authorityPin(), actToPass);
                }
            }
        }

        return subClauses;
    }

    public static List<String> extractSubClauseDefinitions(String secNum, Set<String> citedSubClauseIds, String rawContent) {
        return extractSubClauseDefinitions(secNum, citedSubClauseIds, rawContent, "");
    }

    public static List<String> extractSubClauseDefinitions(String secNum, Set<String> citedSubClauseIds, String rawContent, String pinAct) {
        List<String> results = new ArrayList<>();
        if (rawContent == null || rawContent.isBlank() || citedSubClauseIds == null || citedSubClauseIds.isEmpty()) {
            return results;
        }

        // 1. Check if rawContent has "### PINNED STATUTORY ANCHORS"
        if (rawContent.contains("### PINNED STATUTORY ANCHORS") && pinAct != null && !pinAct.isBlank()) {
            Matcher mAnchorBlock = Pattern.compile("(?s)###\\s*PINNED STATUTORY ANCHORS.*?(?=(?:\\r?\\n){2}|---|#|\\Z)").matcher(rawContent);
            if (mAnchorBlock.find()) {
                String anchorBlock = mAnchorBlock.group(0);
                Matcher mAct = Pattern.compile("(?i)(?:^|\\n)act:\\s*['\"]?([^'\"\\r\\n]+)['\"]?").matcher(rawContent);
                boolean contentActMatches = true;
                if (mAct.find()) {
                    String contentAct = mAct.group(1).trim();
                    if (!isActMatch(contentAct, pinAct)) {
                        contentActMatches = false;
                    }
                }
                if (contentActMatches) {
                    for (String line : anchorBlock.split("\r?\n")) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                            String lineAct = extractActFromStatuteString(trimmed);
                            if (!lineAct.isBlank() && !isActMatch(lineAct, pinAct)) {
                                continue;
                            }
                            for (String subId : citedSubClauseIds) {
                                if (results.size() >= 4) break;
                                Pattern pLineMatch = Pattern.compile("(?i)(Section\\s+" + Pattern.quote(secNum) + "\\s*\\(" + Pattern.quote(subId) + "[^\\)]*\\)[^\\[\\r\\n]*\\[[^\\]\\r\\n]+\\])");
                                Matcher mLineMatch = pLineMatch.matcher(trimmed);
                                if (mLineMatch.find()) {
                                    results.add(mLineMatch.group(1).trim());
                                }
                            }
                        }
                    }
                }
            }
        }

        String normalizedText = cleanBareActText(rawContent);

        int maxClauses = Math.min(4, citedSubClauseIds.size());
        int perClauseCap = maxClauses > 2 ? (220 / maxClauses) : 110;

        // 2. Search definitions or sub-sections in bare act normalized text
        for (String subId : citedSubClauseIds) {
            if (results.size() >= 4) break;

            boolean alreadyAdded = results.stream().anyMatch(r -> r.contains("(" + subId + ")"));
            if (alreadyAdded) continue;

            String cleanSubId = subId.replaceAll("[\\(\\)]", "").trim();

            Pattern pClause = Pattern.compile("(?:^|\\s)\\(" + Pattern.quote(cleanSubId) + "\\)\\s*([\"“]?[^;]+?[\"”]?(?:means|includes|shall|may|is|are|has|have|who|which)?[^;.]+?(?:;|\\.|$))", Pattern.CASE_INSENSITIVE);
            Matcher mClause = pClause.matcher(normalizedText);
            if (mClause.find()) {
                String body = mClause.group(1).trim();
                body = body.replaceAll("[;,.]+$", "").trim();
                body = body.replaceAll("\\*([^*]+)\\*", "$1");
                String formatted = "(" + cleanSubId + ") " + body;
                formatted = truncateAtWordBoundary(formatted, perClauseCap);
                results.add(formatted);
            } else {
                Pattern pFallback = Pattern.compile("(?:^|\\s)\\(" + Pattern.quote(cleanSubId) + "\\)\\s*([^;]+?)(?=;|\\s*\\([0-9a-zA-Z]+\\)|$)", Pattern.CASE_INSENSITIVE);
                Matcher mFallback = pFallback.matcher(normalizedText);
                if (mFallback.find()) {
                    String body = mFallback.group(1).trim().replaceAll("[;,.]+$", "").replaceAll("\\*([^*]+)\\*", "$1");
                    String formatted = "(" + cleanSubId + ") " + body;
                    formatted = truncateAtWordBoundary(formatted, perClauseCap);
                    results.add(formatted);
                }
            }
        }

        return results;
    }

    public static String stripPreambleAndExtractFirstOperativeSentence(String rawContent, String secNum) {
        if (rawContent == null || rawContent.isBlank()) return "";
        String text = cleanBareActText(rawContent);
        if (text.isBlank()) return "";

        // If starts with (1) "..." means (pure definitions section where no sub-clauses cited)
        if (text.matches("(?i)^\\(1\\)\\s*[\"'][^\"']+[\"']\\s*means.*")) {
            return "Defines operative statutory terms and expressions governing proceedings under this enactment.";
        }

        // Extract first complete operative sentence (ignoring periods immediately following digits or standard abbreviations)
        Pattern SENTENCE_PATTERN = Pattern.compile("^(.*?)(?<=[.!?])(?<!\\b(?:sec|secs|art|arts|no|nos|v|vs|dr|mr|mrs|ms|adv|hon|rs|in|re|p|pp|cl|cls|or|i|ii|iii|iv|v|vi|vii|viii|ix|x)\\.)(?<!\\b\\d+\\.)(?:\\s+(?=[A-Z0-9\"'(\\[])|$)", Pattern.CASE_INSENSITIVE);
        Matcher sentMatcher = SENTENCE_PATTERN.matcher(text);
        String firstSentence = text;
        while (sentMatcher.find()) {
            String candidate = sentMatcher.group(1).trim();
            // Guardrail 2: Never stop at a period (.) if resulting string would be shorter than 25 characters
            if (candidate.length() >= 25 || text.length() < 25) {
                firstSentence = candidate;
                break;
            }
        }

        String result = truncateAtWordBoundary(firstSentence, 280);

        // Guardrail 3: Final Fallback: If extracted summary is < 20 characters or matches ^\[?\d+[A-Za-z]*\.?$,
        // skip past the first '.' and take the next 220 characters of normalized Bare Act text (cut at word boundary)
        if (result.length() < 20 || result.matches("(?i)^\\s*[\\[\\*]*\\d+[A-Za-z]*[\\]\\*]*\\.?\\s*$")) {
            int dotIdx = text.indexOf('.');
            String fallbackText = (dotIdx != -1 && dotIdx + 1 < text.length())
                    ? text.substring(dotIdx + 1).trim()
                    : text;
            fallbackText = fallbackText.replaceAll("^\\s*(?:\\]|\\*+|--+|—|–|\\.)+\\s*", "").trim();
            if (!fallbackText.isBlank()) {
                result = truncateAtWordBoundary(fallbackText, 220);
            }
        }

        return result;
    }

    private static String extractActFromStatuteString(String s) {
        if (s == null || s.isBlank()) return "";
        String cleaned = s.replaceAll("^[\\s\\-*•#>]+", "").trim();
        if (cleaned.isBlank()) return "";

        if (cleaned.toLowerCase().matches("^(?:sections?|sec\\.?|articles?|art\\.?)\\b.*")) {
            Pattern pOf = Pattern.compile("(?i)\\b(?:of|under|in|read\\s+with|r/w)\\s+(?:the\\s+)?([A-Za-z0-9\\s,–\\-'\"]+?(?:Act|Code|Sanhita|Adhiniyam|Rules)(?:,\\s*\\d{4})?)");
            Matcher mOf = pOf.matcher(cleaned);
            if (mOf.find()) {
                return mOf.group(1).trim();
            }
            Pattern pAcr = Pattern.compile("(?:(?i)sections?|sec\\.?)\\s+[0-9a-zA-Z\\(\\),\\s–\\-&/]+?\\s+((?-i:[A-Z]{2,6}|[A-Z][a-z]?[A-Z]{1,4})|(?i:[A-Za-z0-9\\s,–\\-'\"]+?\\s+(?:Act|Code|Rules|Sanhita|Adhiniyam)))");
            Matcher mAcr = pAcr.matcher(cleaned);
            if (mAcr.find()) {
                String acr = mAcr.group(1).trim();
                if (!isStopWord(acr)) {
                    return acr;
                }
            }
            return "";
        }

        Pattern pLead = Pattern.compile("(?i)^([A-Za-z0-9\\s,–\\-'\"]+?(?:Act|Code|Rules|Sanhita|Adhiniyam)(?:,\\s*\\d{4})?|(?-i:[A-Z]{2,6}|[A-Z][a-z]?[A-Z]{1,4}))(?:,|\\s)+\\b(?:sections?|sec\\.?|articles?|art\\.?)\\b");
        Matcher mLead = pLead.matcher(cleaned);
        if (mLead.find()) {
            String act = mLead.group(1).trim().replaceAll("(?i)\\b(?:under|pursuant\\s+to|in\\s+terms\\s+of)\\b", "").trim();
            if (!act.isBlank() && !isStopWord(act) && act.length() >= 2) {
                return act;
            }
        }
        Pattern pOf = Pattern.compile("(?i)\\b(?:of|under|in|read\\s+with|r/w)\\s+(?:the\\s+)?([A-Za-z0-9\\s,–\\-'\"]+?(?:Act|Code|Sanhita|Adhiniyam|Rules)(?:,\\s*\\d{4})?)");
        Matcher mOf = pOf.matcher(cleaned);
        if (mOf.find()) {
            return mOf.group(1).trim();
        }
        return "";
    }

    public static String renderStatutorySourceCoverage(
            List<StatutePin> effectiveStatutePack,
            String userQuery,
            ArgumentDossier dossier
    ) {
        if (effectiveStatutePack == null || effectiveStatutePack.isEmpty()) {
            return "";
        }

        // 1. Group verified statutes by Act name
        Map<String, List<String>> verifiedByAct = new LinkedHashMap<>();
        for (StatutePin pin : effectiveStatutePack) {
            String actTitle = formatActNameForTableCell(pin);
            String sec = pin.sectionNumber() != null ? pin.sectionNumber().trim() : "";
            if (sec.toLowerCase().startsWith("section_")) {
                sec = "Section " + sec.substring(8);
            } else if (sec.toLowerCase().startsWith("sec_")) {
                sec = "Section " + sec.substring(4);
            } else if (sec.matches("^\\d+[A-Za-z]?$")) {
                sec = "Section " + sec;
            } else if (!sec.toLowerCase().startsWith("section") && !sec.toLowerCase().startsWith("article") && !sec.toLowerCase().startsWith("schedule")) {
                sec = "Section " + sec;
            }
            verifiedByAct.computeIfAbsent(actTitle, k -> new ArrayList<>()).add(sec);
        }

        List<String> verifiedSummary = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : verifiedByAct.entrySet()) {
            List<String> dedupedSecs = entry.getValue().stream().distinct().toList();
            verifiedSummary.add(entry.getKey() + " (" + String.join(", ", dedupedSecs) + ")");
        }
        String verifiedStr = String.join("; ", verifiedSummary);

        StringBuilder sb = new StringBuilder();
        sb.append("> **Statutory Source Coverage:** **Verified in Local OKF Pack:** ").append(verifiedStr).append(".");

        // 2. Parse explicit Act / Section / Article references mentioned in userQuery (and dossier)
        List<String> unbundled = findUnbundledStatuteReferences(userQuery, dossier, effectiveStatutePack);
        if (!unbundled.isEmpty()) {
            sb.append("\n> **External / Unbundled Statutes Referenced (Verify Bare Act Text):** ")
              .append(String.join("; ", unbundled))
              .append(" — *Not present in local OKF bundle; verify verbatim text prior to filing.*");
        }

        return sb.toString();
    }

    public static List<String> findUnbundledStatuteReferences(
            String userQuery,
            ArgumentDossier dossier,
            List<StatutePin> effectiveStatutePack
    ) {
        if ((userQuery == null || userQuery.isBlank()) && dossier == null) {
            return List.of();
        }

        Set<String> unbundled = new LinkedHashSet<>();
        Set<String> bundledActsNormalized = new HashSet<>();
        Set<String> bundledSecsNormalized = new HashSet<>();

        if (effectiveStatutePack != null) {
            for (StatutePin pin : effectiveStatutePack) {
                String act = pin.actName() != null ? pin.actName().toLowerCase().replaceAll("[^a-z0-9]", "") : "";
                if (!act.isBlank()) bundledActsNormalized.add(act);
                String sec = resolveBaseSectionNumber(pin).toLowerCase().replaceAll("[^a-z0-9]", "");
                if (!sec.isBlank()) bundledSecsNormalized.add(sec);
            }
        }

        java.util.function.BiConsumer<String, String> checkReference = (actRaw, secRaw) -> {
            if (actRaw == null || actRaw.isBlank()) return;
            String actNorm = actRaw.toLowerCase().replaceAll("[^a-z0-9]", "");
            String secNorm = (secRaw != null) ? secRaw.toLowerCase().replaceAll("[^a-z0-9]", "") : "";

            boolean actFound = false;
            for (String bundledAct : bundledActsNormalized) {
                if (bundledAct.contains(actNorm) || actNorm.contains(bundledAct)) {
                    actFound = true;
                    break;
                }
            }

            if (!actFound) {
                if ((actNorm.contains("cpc") || actNorm.contains("civilprocedure")) && bundledActsContains(bundledActsNormalized, "civilprocedure")) actFound = true;
                if ((actNorm.contains("crpc") || actNorm.contains("criminalprocedure")) && bundledActsContains(bundledActsNormalized, "criminalprocedure")) actFound = true;
                if ((actNorm.contains("ibc") || actNorm.contains("insolvency") || actNorm.equals("thecode") || actNorm.equals("code") || actNorm.contains("ibcode")) && bundledActsContains(bundledActsNormalized, "insolvency")) actFound = true;
                if ((actNorm.contains("niact") || actNorm.contains("negotiableinstruments")) && bundledActsContains(bundledActsNormalized, "negotiableinstruments")) actFound = true;
                if ((actNorm.contains("cpa") || actNorm.contains("consumerprotection")) && bundledActsContains(bundledActsNormalized, "consumerprotection")) actFound = true;
                if ((actNorm.contains("limitation")) && bundledActsContains(bundledActsNormalized, "limitation")) actFound = true;
                if ((actNorm.contains("evidence") || actNorm.contains("sakshya") || actNorm.contains("bsa")) && (bundledActsContains(bundledActsNormalized, "sakshya") || bundledActsContains(bundledActsNormalized, "evidence"))) actFound = true;
                if ((actNorm.contains("contract")) && bundledActsContains(bundledActsNormalized, "contract")) actFound = true;
                if ((actNorm.contains("arbitration")) && bundledActsContains(bundledActsNormalized, "arbitration")) actFound = true;
            }

            boolean secFound = secNorm.isBlank() || bundledSecsNormalized.contains(secNorm);

            if (!actFound || !secFound) {
                String displayAct = cleanActDisplayTitle(actRaw);
                String displaySec = !secNorm.isBlank() ? "Section " + secRaw.replaceAll("(?i)^section[ _-]*", "").trim() : "";
                String item = !displaySec.isBlank() ? displayAct + " (" + displaySec + ")" : displayAct;
                unbundled.add(item);
            }
        };

        if (userQuery != null && !userQuery.isBlank()) {
            scanStatutoryReferencesFromText(userQuery, checkReference);
        }

        return new ArrayList<>(unbundled);
    }

    private static boolean bundledActsContains(Set<String> set, String fragment) {
        for (String s : set) {
            if (s.contains(fragment)) return true;
        }
        return false;
    }

    private static void scanStatutoryReferencesFromText(String text, java.util.function.BiConsumer<String, String> consumer) {
        if (text == null || text.isBlank()) return;

        Pattern p1 = Pattern.compile("(?i)(?:sections?|sec\\.?|articles?|art\\.?)\\s+([0-9a-zA-Z]+(?:\\([0-9a-zA-Z]+\\))*)\\s+(?:of|under|in)\\s+(?:the\\s+)?([A-Za-z0-9\\s,–\\-]+?(?:Act|Code|Rules|Constitution|Adhiniyam|Sanhita)(?:,\\s*\\d{4})?)");
        Matcher m1 = p1.matcher(text);
        while (m1.find()) {
            consumer.accept(m1.group(2).trim(), m1.group(1).trim());
        }

        Pattern p2 = Pattern.compile("(?i)(?:the\\s+)?([A-Za-z0-9\\s,–\\-]+?(?:Act|Code|Rules|Constitution|Adhiniyam|Sanhita)(?:,\\s*\\d{4})?)\\s*,?\\s*(?:sections?|sec\\.?|articles?|art\\.?)\\s+([0-9a-zA-Z]+(?:\\([0-9a-zA-Z]+\\))*)");
        Matcher m2 = p2.matcher(text);
        while (m2.find()) {
            consumer.accept(m2.group(1).trim(), m2.group(2).trim());
        }

        Pattern p3 = Pattern.compile("(?i)(?:sections?|sec\\.?)\\s+([0-9a-zA-Z]+)\\s+(CPC|CrPC|IBC|NI\\s+Act|CPA|TPA|HMA|ICA|RERA)");
        Matcher m3 = p3.matcher(text);
        while (m3.find()) {
            consumer.accept(m3.group(2).trim(), m3.group(1).trim());
        }
    }

    private static String cleanActDisplayTitle(String rawAct) {
        if (rawAct == null) return "";
        String trimmed = rawAct.trim().replaceAll("^[-,;\\s]+|[-,;\\s]+$", "");
        String lower = trimmed.toLowerCase();
        if (lower.equals("cpc")) return "Code of Civil Procedure, 1908";
        if (lower.equals("crpc")) return "Code of Criminal Procedure, 1973";
        if (lower.equals("ibc")) return "The Insolvency and Bankruptcy Code, 2016";
        if (lower.equals("ni act")) return "The Negotiable Instruments Act, 1881";
        if (lower.equals("cpa")) return "The Consumer Protection Act, 2019";
        if (lower.equals("tpa")) return "The Transfer of Property Act, 1882";
        if (lower.equals("hma")) return "The Hindu Marriage Act, 1955";
        if (lower.equals("ica")) return "The Indian Contract Act, 1872";
        if (lower.equals("rera")) return "Real Estate (Regulation and Development) Act, 2016";
        return com.app.refirm.inspector.service.InspectorService.humanizeActTitle(trimmed);
    }

    public static String cleanRebuttalProse(String rebuttal) {
        if (rebuttal == null || rebuttal.isBlank()) return "";
        String cleaned = rebuttal;
        // Strip robotic labels like "PRONG 1 — Substantive defeat:" or "PRONG 1 (Substantive Defeat):" or "PRONG 1 —"
        cleaned = cleaned.replaceAll("(?i)(?:^|\\n)\\s*[-*]?\\s*PRONG\\s*1\\s*(?:\\([^\\)]*\\))?\\s*[-—–:]*\\s*(?:Substantive\\s+defeat[^:\\n]*:)?\\s*", "\n");
        // Strip robotic labels like "PRONG 2 — Procedural & statutory survival:" or "PRONG 2:"
        cleaned = cleaned.replaceAll("(?i)(?:^|\\n)\\s*[-*]?\\s*PRONG\\s*2\\s*(?:\\([^\\)]*\\))?\\s*[-—–:]*\\s*(?:Procedural\\s+(?:and|&)\\s+statutory\\s+survival[^:\\n]*:)?\\s*", "\n\nFurthermore, ");
        return cleaned.replaceAll("(?m)^\\s*\\n{3,}", "\n\n").trim();
    }

    public static String escapeTableCell(String text) {
        if (text == null) return "";
        return text.replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ─── HELPER: detect unverified / vault-noise anchor strings ───

    public static boolean isUnverifiedOrIrrelevantAnchor(String anchor) {
        if (anchor == null || anchor.isBlank()) return true;
        String lower = anchor.toLowerCase();
        return lower.equals("none_uploaded")
                || lower.contains("no relevant evidence")
                || lower.contains("is unrelated")
                || lower.contains("none is in the uploaded vault")
                || lower.contains("not verified in the uploaded vault")
                || lower.contains("no matching documentary annexure")
                || lower.contains("no documentary evidence uploaded")
                || lower.contains("no such files or page references");
    }

    public static boolean containsLegacyVaultNoise(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        return lower.contains("is unrelated")
                || lower.contains("no relevant evidence")
                || lower.contains("none is in the uploaded vault")
                || lower.contains("no such files or page references")
                || lower.contains("not verified in the uploaded vault");
    }

    public static String scrubLegacyVaultSentences(String text) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.contains("is unrelated")
                    || lower.contains("no relevant evidence")
                    || lower.contains("none is in the uploaded vault")
                    || lower.contains("no such files or page references")
                    || lower.contains("not verified in the uploaded vault")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- **Verified Evidence Anchor:**")
                        || trimmed.startsWith("**Authority / Anchor:**")
                        || trimmed.startsWith("> ⚠️ **Evidentiary Gap:**")) {
                    continue;
                }
                String cleanedLine = removeVaultPhrasesFromLine(line);
                if (!cleanedLine.trim().isEmpty()) {
                    sb.append(cleanedLine).append("\n");
                }
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().replaceAll("(?m)^\\s*\\n{3,}", "\n\n").trim() + "\n";
    }

    private static String removeVaultPhrasesFromLine(String line) {
        String[] sentences = line.split("(?<=[.?!])\\s+");
        List<String> valid = new ArrayList<>();
        for (String s : sentences) {
            String l = s.toLowerCase();
            if (!l.contains("is unrelated")
                    && !l.contains("no relevant evidence")
                    && !l.contains("none is in the uploaded vault")
                    && !l.contains("no such files or page references")
                    && !l.contains("not verified in the uploaded vault")) {
                valid.add(s);
            }
        }
        return String.join(" ", valid);
    }

    // ─── HELPER: resolve the effective statute pack for the table ───

    private List<StatutePin> resolveEffectiveStatutePack(List<StatutePin> explicitPack, ArgumentDossier dossier) {
        if (explicitPack != null && !explicitPack.isEmpty()) {
            return explicitPack;
        }
        if (dossier != null && dossier.coreSubmissions() != null) {
            List<StatutePin> extracted = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (ArgumentSubmission sub : dossier.coreSubmissions()) {
                if (sub.pinnedStatutes() != null) {
                    for (String stat : sub.pinnedStatutes()) {
                        if (stat != null && !stat.isBlank() && seen.add(stat.trim())) {
                            extracted.add(new StatutePin(stat.trim(), "", sub.legalProposition() != null ? sub.legalProposition() : "Operative statutory ground"));
                        }
                    }
                }
            }
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        return List.of();
    }

    // ─── TUPLE-SCOPED STATUTE CITATION & FILTERING ───

    public record ActSectionRef(String actKey, String sectionNumber) {
        public ActSectionRef {
            actKey = actKey != null ? actKey.trim() : "";
            sectionNumber = sectionNumber != null ? sectionNumber.trim() : "";
        }
    }

    public static boolean isActMatch(String actA, String actB) {
        if (actA == null || actB == null) return false;
        String a = actA.trim().toLowerCase();
        String b = actB.trim().toLowerCase();
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;

        // Clean punctuation and normalize whitespace
        String cleanA = a.replaceAll("[^a-z0-9]", " ").replaceAll("\\s+", " ").trim();
        String cleanB = b.replaceAll("[^a-z0-9]", " ").replaceAll("\\s+", " ").trim();
        if (cleanA.equals(cleanB)) return true;

        if (cleanA.startsWith("the ")) cleanA = cleanA.substring(4).trim();
        if (cleanB.startsWith("the ")) cleanB = cleanB.substring(4).trim();
        if (cleanA.equals(cleanB)) return true;

        // Strip trailing 4-digit years (e.g. 1872, 1908, 1963, 1996, 2013, 2016, 2019, 2023)
        String strippedA = cleanA.replaceAll("\\b(18|19|20)\\d{2}\\b", "").trim().replaceAll("\\s+", " ");
        String strippedB = cleanB.replaceAll("\\b(18|19|20)\\d{2}\\b", "").trim().replaceAll("\\s+", " ");
        if (!strippedA.isEmpty() && strippedA.equals(strippedB)) return true;

        // Acronym match (e.g. "ibc" <-> "insolvency and bankruptcy code", "bsa" <-> "bharatiya sakshya adhiniyam", "cpa" <-> "consumer protection act")
        if (matchesAcronym(cleanA, cleanB) || matchesAcronym(cleanB, cleanA)) {
            return true;
        }

        // Token overlap of significant words (length >= 3, excluding legislative stop words)
        Set<String> stopWords = Set.of("the", "and", "of", "in", "for", "to", "on", "with", "an", "a", "act", "code", "sanhita", "adhiniyam", "rules");
        List<String> wordsA = Arrays.stream(strippedA.split("\\s+"))
                .filter(w -> w.length() >= 3 && !stopWords.contains(w))
                .toList();
        List<String> wordsB = Arrays.stream(strippedB.split("\\s+"))
                .filter(w -> w.length() >= 3 && !stopWords.contains(w))
                .toList();

        if (wordsA.isEmpty() || wordsB.isEmpty()) {
            return false;
        }

        // Check if one set of significant words is a subset of the other
        return wordsB.containsAll(wordsA) || wordsA.containsAll(wordsB);
    }

    private static boolean matchesAcronym(String candidate, String fullText) {
        String acr = candidate.replaceAll("\\s+", "").toLowerCase();
        if (acr.length() < 2 || acr.length() > 6 || !acr.matches("^[a-z]+$")) {
            return false;
        }
        Set<String> stopWords = Set.of("the", "and", "of", "in", "for", "to", "on", "with", "an", "a");
        String[] words = fullText.split("\\s+");
        StringBuilder sig = new StringBuilder();
        StringBuilder all = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            char first = w.charAt(0);
            if (Character.isLetter(first)) {
                all.append(first);
                if (!stopWords.contains(w)) {
                    sig.append(first);
                }
            }
        }
        if (acr.contentEquals(sig) || acr.contentEquals(all)) {
            return true;
        }
        if (acr.length() == sig.length()) {
            char[] a1 = acr.toCharArray();
            char[] a2 = sig.toString().toCharArray();
            Arrays.sort(a1);
            Arrays.sort(a2);
            if (Arrays.equals(a1, a2)) return true;
        }
        return false;
    }

    public static List<StatutePin> filterToActivelyCitedStatutes(List<StatutePin> statutePack, Collection<?> citedRefsOrSections) {
        if (statutePack == null || statutePack.isEmpty()) {
            return List.of();
        }
        if (citedRefsOrSections == null || citedRefsOrSections.isEmpty()) {
            return statutePack;
        }

        Set<ActSectionRef> citedRefs = new HashSet<>();
        for (Object item : citedRefsOrSections) {
            if (item instanceof ActSectionRef r) {
                citedRefs.add(r);
            } else if (item instanceof String s) {
                if (!s.isBlank()) {
                    citedRefs.add(new ActSectionRef("", s.trim()));
                }
            }
        }

        if (citedRefs.isEmpty()) {
            return statutePack;
        }

        Map<String, List<ActSectionRef>> refsBySec = citedRefs.stream()
                .collect(Collectors.groupingBy(r -> r.sectionNumber().toLowerCase()));

        List<StatutePin> filtered = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (StatutePin pin : statutePack) {
            String baseSec = resolveBaseSectionNumber(pin).toLowerCase();
            String pinAct = pin.actName() != null ? pin.actName().trim() : "";
            String key = pinAct.toLowerCase() + "::" + baseSec;

            List<ActSectionRef> matchingRefs = refsBySec.get(baseSec);
            if (matchingRefs == null || matchingRefs.isEmpty()) {
                continue;
            }

            boolean hasExplicitActRefs = matchingRefs.stream().anyMatch(r -> !r.actKey().isBlank());
            boolean isMatched;

            if (hasExplicitActRefs) {
                isMatched = matchingRefs.stream()
                        .filter(r -> !r.actKey().isBlank())
                        .anyMatch(r -> isActMatch(r.actKey(), pinAct));
            } else {
                isMatched = true;
            }

            if (isMatched && seenKeys.add(key)) {
                filtered.add(pin);
            }
        }

        return filtered.isEmpty() ? statutePack : filtered;
    }

    public static List<StatutePin> filterToActivelyCitedStatutes(List<StatutePin> statutePack, Set<String> citedSectionNumbers, boolean legacyMode) {
        return filterToActivelyCitedStatutes(statutePack, (Collection<?>) citedSectionNumbers);
    }

    public static Set<ActSectionRef> extractCitedActSectionRefs(ArgumentDossier dossier) {
        return extractCitedActSectionRefs(dossier, null);
    }

    public static Set<ActSectionRef> extractCitedActSectionRefs(ArgumentDossier dossier, String renderedMarkdown) {
        Set<ActSectionRef> refs = new LinkedHashSet<>();

        if (dossier != null) {
            if (dossier.coreSubmissions() != null) {
                for (ArgumentSubmission sub : dossier.coreSubmissions()) {
                    if (sub.pinnedStatutes() != null) {
                        for (String stat : sub.pinnedStatutes()) {
                            parseStatuteStringIntoRefs(stat, refs);
                        }
                    }
                    parseTextIntoRefs(sub.title(), refs);
                    parseTextIntoRefs(sub.legalProposition(), refs);
                }
            }
            if (dossier.rebuttalMatrix() != null) {
                for (RebuttalMatrixItem reb : dossier.rebuttalMatrix()) {
                    parseStatuteStringIntoRefs(reb.statutoryOrEvidentiaryAnchor(), refs);
                    parseTextIntoRefs(reb.ourCounterRebuttal(), refs);
                    parseTextIntoRefs(reb.anticipatedOppositeArgument(), refs);
                }
            }
            if (dossier.benchGrillQuestions() != null) {
                for (BenchQuestionItem bq : dossier.benchGrillQuestions()) {
                    parseStatuteStringIntoRefs(bq.authorityPin(), refs);
                    parseTextIntoRefs(bq.recommendedCourtroomAnswer(), refs);
                    parseTextIntoRefs(bq.questionFromJudge(), refs);
                }
            }
            if (dossier.framedIssues() != null) {
                for (FramedLegalIssue issue : dossier.framedIssues()) {
                    parseTextIntoRefs(issue.questionFramed(), refs);
                    parseTextIntoRefs(issue.burdenOfProof(), refs);
                }
            }
            if (dossier.openingPitch60Seconds() != null) {
                parseTextIntoRefs(dossier.openingPitch60Seconds(), refs);
            }
        }

        if (renderedMarkdown != null && !renderedMarkdown.isBlank()) {
            parseTextIntoRefs(renderedMarkdown, refs);
        }

        return refs;
    }

    public static void parseStatuteStringIntoRefs(String raw, Set<ActSectionRef> out) {
        if (raw == null || raw.isBlank()) return;

        String[] segments = raw.split(";");
        for (String segment : segments) {
            String seg = segment.replaceAll("^[\\s\\-*•#>]+", "").trim();
            if (seg.isBlank()) continue;

            // Pattern A: Leading Act name followed by Section(s)
            if (!seg.toLowerCase().matches("^(?:sections?|sec\\.?|articles?|art\\.?)\\b.*")) {
                Pattern pLeadAct = Pattern.compile("(?i)^([A-Za-z0-9\\s,–\\-'\"]+?(?:Act|Code|Rules|Sanhita|Adhiniyam)(?:,\\s*\\d{4})?|(?-i:[A-Z]{2,6}|[A-Z][a-z]?[A-Z]{1,4}))(?:,|\\s)+\\b(?:sections?|sec\\.?|articles?|art\\.?)\\s+([0-9a-zA-Z\\(\\),\\s–\\-&/]+)$");
                Matcher mLead = pLeadAct.matcher(seg);
                if (mLead.find()) {
                    String actPart = mLead.group(1).trim().replaceAll("(?i)\\b(?:under|pursuant\\s+to|in\\s+terms\\s+of)\\b", "").trim();
                    if (!actPart.isBlank() && !isStopWord(actPart) && actPart.length() >= 2) {
                        String secPart = mLead.group(2).trim();
                        extractBaseSections(secPart).forEach(sec -> out.add(new ActSectionRef(actPart, sec)));
                        continue;
                    }
                }
            }

            // Pattern B: Section(s) followed by "of / under / in / read with" and Act Name
            Pattern pSecOfAct = Pattern.compile("(?i)(?:sections?|sec\\.?|articles?|art\\.?)\\s+([0-9a-zA-Z\\(\\),\\s–\\-&/]+?)\\s+(?:of|under|in|read\\s+with|r/w)\\s+(?:the\\s+)?([A-Za-z0-9\\s,–\\-'\"]+?(?:Act|Code|Sanhita|Adhiniyam|Rules)(?:,\\s*\\d{4})?)");
            Matcher mSecOf = pSecOfAct.matcher(seg);
            boolean foundSecOf = false;
            while (mSecOf.find()) {
                foundSecOf = true;
                String secPart = mSecOf.group(1).trim();
                String actPart = mSecOf.group(2).trim();
                extractBaseSections(secPart).forEach(sec -> out.add(new ActSectionRef(actPart, sec)));
            }
            if (foundSecOf) continue;

            // Pattern C: Section followed by Act acronym / short name
            Pattern pSecAcr = Pattern.compile("(?:(?i)sections?|sec\\.?)\\s+([0-9a-zA-Z]+(?:\\([0-9a-zA-Z]+\\))*)\\s+((?-i:[A-Z]{2,6}|[A-Z][a-z]?[A-Z]{1,4})|(?i:[A-Za-z0-9\\s,–\\-'\"]+?\\s+(?:Act|Code|Rules|Sanhita|Adhiniyam)))");
            Matcher mAcr = pSecAcr.matcher(seg);
            boolean foundAcr = false;
            while (mAcr.find()) {
                String actPart = mAcr.group(2).trim();
                String baseSec = extractBaseSectionNumber(mAcr.group(1));
                if (!isStopWord(actPart)) {
                    foundAcr = true;
                    out.add(new ActSectionRef(actPart, baseSec));
                }
            }
            if (foundAcr) continue;

            // Pattern D: Bare sections e.g. "Section 7", "Sections 34(1), 35(1) and 100"
            Pattern pBare = Pattern.compile("(?i)\\b(?:sections?|sec\\.?)\\s+([0-9a-zA-Z\\(\\),\\s–\\-&/]+?)(?=[.?;!\\n\\r]|$)");
            Matcher mBare = pBare.matcher(seg);
            while (mBare.find()) {
                String secPart = mBare.group(1).trim();
                extractBaseSections(secPart).forEach(sec -> out.add(new ActSectionRef("", sec)));
            }
        }
    }

    private static boolean isStopWord(String word) {
        if (word == null || word.isBlank()) return true;
        String w = word.trim().toLowerCase();
        return Set.of(
                "by", "and", "or", "of", "to", "in", "is", "as", "at", "for", "on", "with",
                "from", "that", "this", "the", "when", "where", "which", "shall", "may",
                "will", "can", "does", "did", "have", "has", "had", "been", "being", "held",
                "read", "also", "only", "then", "into", "over", "such", "each", "both", "under",
                "any", "all", "its", "their", "our", "his", "her", "before", "after", "while",
                "since", "until", "against", "about", "between", "through", "during", "without",
                "corporate", "debtor", "creditor", "default", "dispute", "complainant", "respondent",
                "applicant", "petitioner", "appellant", "tribunal", "court", "commission", "board",
                "standing", "relief", "matter", "notice", "reply", "appeal", "application", "claim"
        ).contains(w);
    }

    public static void parseTextIntoRefs(String text, Set<ActSectionRef> out) {
        if (text == null || text.isBlank()) return;

        // 1. "Section(s) X of/under Act"
        Pattern pSecOfAct = Pattern.compile("(?i)(?:sections?|sec\\.?|articles?|art\\.?)\\s+([0-9a-zA-Z\\(\\),\\s–\\-&/]+?)\\s+(?:of|under|in|read\\s+with|r/w)\\s+(?:the\\s+)?([A-Za-z0-9\\s,–\\-'\"]+?(?:Act|Code|Sanhita|Adhiniyam|Rules)(?:,\\s*\\d{4})?)");
        Matcher mSecOf = pSecOfAct.matcher(text);
        while (mSecOf.find()) {
            String secPart = mSecOf.group(1).trim();
            String actPart = mSecOf.group(2).trim();
            extractBaseSections(secPart).forEach(sec -> out.add(new ActSectionRef(actPart, sec)));
        }

        // 2. "Act, Sections X, Y"
        Pattern pLeadAct = Pattern.compile("(?i)\\b([A-Za-z0-9\\s,–\\-'\"]+?(?:Act|Code|Sanhita|Adhiniyam)(?:,\\s*\\d{4})?),?\\s+(?:sections?|sec\\.?)\\s+([0-9a-zA-Z\\(\\),\\s–\\-&/]+?)(?=[.?;!\\n\\r]|$)");
        Matcher mLead = pLeadAct.matcher(text);
        while (mLead.find()) {
            String actPart = mLead.group(1).trim();
            String secPart = mLead.group(2).trim();
            extractBaseSections(secPart).forEach(sec -> out.add(new ActSectionRef(actPart, sec)));
        }

        // 3. Section acronym e.g. "Section 34(2) CPA", "Section 7 IBC"
        Pattern pSecAcr = Pattern.compile("(?:(?i)sections?|sec\\.?)\\s+([0-9a-zA-Z]+(?:\\([0-9a-zA-Z]+\\))*)\\s+((?-i:[A-Z]{2,6}|[A-Z][a-z]?[A-Z]{1,4})|(?i:[A-Za-z0-9\\s,–\\-'\"]+?\\s+(?:Act|Code|Rules|Sanhita|Adhiniyam)))");
        Matcher mAcr = pSecAcr.matcher(text);
        while (mAcr.find()) {
            String actPart = mAcr.group(2).trim();
            String baseSec = extractBaseSectionNumber(mAcr.group(1));
            if (!isStopWord(actPart)) {
                out.add(new ActSectionRef(actPart, baseSec));
            }
        }

        // 4. Section identifier e.g. section_2, section_34
        Matcher secUnderMatcher = Pattern.compile("(?i)\\bsection_(\\d+[A-Za-z]?)\\b").matcher(text);
        while (secUnderMatcher.find()) {
            out.add(new ActSectionRef("", secUnderMatcher.group(1)));
        }

        // 5. Bare sections e.g. "Section 7", "Section 2(7)", "Section 34"
        Pattern pBareSec = Pattern.compile("(?i)\\b(?:sections?|sec\\.?)\\s+([0-9a-zA-Z]+(?:\\([0-9a-zA-Z]+\\))*)");
        Matcher mBareSec = pBareSec.matcher(text);
        while (mBareSec.find()) {
            String base = extractBaseSectionNumber(mBareSec.group(1));
            if (!base.isBlank()) {
                out.add(new ActSectionRef("", base));
            }
        }
    }

    private static List<String> extractBaseSections(String secPart) {
        List<String> list = new ArrayList<>();
        if (secPart == null || secPart.isBlank()) return list;
        String[] tokens = secPart.split(",|\\band\\b|&|\\bor\\b|/");
        for (String token : tokens) {
            String trimmed = token.trim().replaceAll("(?i)^sections?\\s*", "");
            String base = extractBaseSectionNumber(trimmed);
            if (!base.isBlank()) {
                list.add(base);
            }
        }
        return list;
    }

    public static Set<String> extractCitedSectionNumbers(ArgumentDossier dossier, String renderedMarkdown) {
        Set<String> cited = new LinkedHashSet<>();

        java.util.function.Consumer<String> scanText = text -> {
            if (text == null || text.isBlank()) return;

            // 1. Direct section identifier e.g. section_2, section_34
            Matcher secUnderMatcher = Pattern.compile("(?i)\\bsection_(\\d+[A-Za-z]?)\\b").matcher(text);
            while (secUnderMatcher.find()) {
                cited.add(secUnderMatcher.group(1));
            }

            // 2. Section / Sections / Sec / Sec. phrases e.g. "Section 34", "Sections 34(1), 35(1) and 100", "Sections 2(17), 2(42), 2(11) & 100"
            Matcher secPhraseMatcher = Pattern.compile("(?i)\\b(?:sections?|sec\\.?)\\s+([0-9a-zA-Z\\(\\),\\s–\\-&/]+?)(?=[.?;!\\n\\r]|\\b(?:of|under|in|read|r/w|with|the|act)\\b|$)").matcher(text);
            while (secPhraseMatcher.find()) {
                String phrase = secPhraseMatcher.group(1);
                String[] tokens = phrase.split(",|\\band\\b|&|\\bor\\b|/");
                for (String token : tokens) {
                    String trimmed = token.trim().replaceAll("(?i)^sections?\\s*", "");
                    Matcher numMatcher = Pattern.compile("^(\\d+[A-Za-z]?)").matcher(trimmed);
                    if (numMatcher.find()) {
                        cited.add(numMatcher.group(1));
                    }
                }
            }

            // 3. Universal single section matcher
            Matcher singleSecMatcher = Pattern.compile("(?i)\\bsections?\\s+(\\d+[A-Za-z]?)").matcher(text);
            while (singleSecMatcher.find()) {
                cited.add(singleSecMatcher.group(1));
            }
        };

        if (renderedMarkdown != null) {
            scanText.accept(renderedMarkdown);
        }

        if (dossier != null) {
            if (dossier.openingPitch60Seconds() != null) {
                scanText.accept(dossier.openingPitch60Seconds());
            }
            if (dossier.framedIssues() != null) {
                for (FramedLegalIssue issue : dossier.framedIssues()) {
                    scanText.accept(issue.questionFramed());
                    scanText.accept(issue.burdenOfProof());
                }
            }
            if (dossier.coreSubmissions() != null) {
                for (ArgumentSubmission sub : dossier.coreSubmissions()) {
                    scanText.accept(sub.title());
                    scanText.accept(sub.legalProposition());
                    if (sub.pinnedStatutes() != null) {
                        for (String stat : sub.pinnedStatutes()) {
                            scanText.accept(stat);
                            String base = extractBaseSectionNumber(stat);
                            if (!base.isBlank()) {
                                cited.add(base);
                            }
                        }
                    }
                }
            }
            if (dossier.rebuttalMatrix() != null) {
                for (RebuttalMatrixItem reb : dossier.rebuttalMatrix()) {
                    scanText.accept(reb.anticipatedOppositeArgument());
                    scanText.accept(reb.ourCounterRebuttal());
                    scanText.accept(reb.statutoryOrEvidentiaryAnchor());
                }
            }
            if (dossier.benchGrillQuestions() != null) {
                for (BenchQuestionItem qa : dossier.benchGrillQuestions()) {
                    scanText.accept(qa.questionFromJudge());
                    scanText.accept(qa.recommendedCourtroomAnswer());
                    scanText.accept(qa.authorityPin());
                }
            }
        }

        return cited;
    }

    public static String resolveBaseSectionNumber(StatutePin pin) {
        if (pin == null) return "";
        String rawContent = pin.operativeText() != null ? pin.operativeText() : "";

        Matcher secYamlMatcher = Pattern.compile("(?m)^(?:section|section_number)\\s*:\\s*['\"]?([^'\"\\r\\n]+)['\"]?").matcher(rawContent);
        if (secYamlMatcher.find()) {
            String candidate = secYamlMatcher.group(1).trim();
            String base = extractBaseSectionNumber(candidate);
            if (!base.isBlank()) return base;
        }

        return extractBaseSectionNumber(pin.sectionNumber());
    }

    public static String extractBaseSectionNumber(String rawSec) {
        if (rawSec == null || rawSec.isBlank()) return "";
        String s = rawSec.trim();
        Matcher m = Pattern.compile("(?i)(?:section[ _-]*)?(\\d+[A-Za-z]?)").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        return s.replaceAll("(?i)section[ _-]*", "").trim();
    }
}
