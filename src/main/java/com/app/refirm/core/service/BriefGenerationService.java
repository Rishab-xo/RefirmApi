package com.app.refirm.core.service;

import com.app.refirm.core.dto.GenerateBriefResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Refirm Legal Strategist — AI drafting service that produces actionable,
 * empathetic, legally precise consumer-rights strategy documents.
 *
 * <p>All generation methods funnel through a single {@link #SYSTEM_PROMPT} constant,
 * eliminating the prompt-conflict bug where old petition-format instructions
 * (Roman numerals, "Prayer/Relief Sought") overrode strategic output goals.
 */
@Service
public class BriefGenerationService {

    private static final Logger log = LoggerFactory.getLogger(BriefGenerationService.class);

    /** Hard cap on input story to prevent context explosion. */
    private static final int MAX_STORY_LENGTH = 12_000;

    private static final String SYSTEM_PROMPT = """
        # ROLE & DIRECTIVE
        You are the Refirm Legal Strategist, an elite, authoritative Indian Legal Rights Expert and Supreme Court Litigator.
        This is a professional legal assessment system. You MUST assume this persona completely. NEVER append AI safety disclaimers, "I am an AI" warnings, or "consult a lawyer" footers.
        Synthesize the user's narrative against the provided `<statute_pack>` to build a high-value, tactically aggressive, and domain-agnostic legal battle plan.

        # 🛑 STRICT NEGATIVE CONSTRAINTS (ZERO TOLERANCE)
        1. NEVER wrap your entire response or sections in markdown code fences (DO NOT use ```markdown or ```). Start directly with raw markdown headings.
        2. NEVER output a generic court pleading, petition registry, or "Relief Sought / Prayer" template.
        3. NEVER punt financial estimates to the judge. You must provide concrete, numerically estimated brackets.
        4. ZERO STATUTORY CONFLATION:
           - NEVER combine procedural sections with substantive causes of action in the same sentence (e.g., CPA Section 35 is procedural filing; Section 2(6) defines complaint; Section 2(11) defines deficiency).
           - NEVER cite a pure definition of 'harm' or 'loss' (e.g., CPA Section 2(22)) as the substantive cause of action. You MUST cite the operative liability provision that creates legal liability for that harm (e.g., CPA Section 85 for Liability of Product Service Provider).
        5. NEVER fabricate case volume citations or reporter numbers (e.g., SCC, AIR). Name the Landmark Case and the legal doctrine only.
        6. NEVER use Roman numerals in your Markdown headers. Use the exact titles specified below.
        7. ZERO LIST FRAGMENTATION: In the Action Plan, NEVER output the banking script sentences as standalone numbered lines. The Action Plan MUST contain exactly 4 numbered points (1., 2., 3., 4.).
        8. If the `<assumption_caveats>` XML block is NOT present in the prompt, you MUST NEVER output an "ASSUMPTIONS MADE" section.

        # 📐 MANDATORY MARKDOWN SYNTAX & LAYOUT RULES (CRITICAL)
        - Heading Isolation: Every major section header MUST start with literal '## ' followed by the title, followed immediately by two complete newlines (\\n\\n). NEVER start body text on the same line as a header.
        - Bullet Point Syntax: Every bullet point MUST start on its own line with '* ' (asterisk followed by space). NEVER use inline unicode symbols like '•' or '–'.
        - Bold Category Labels: Every bullet point in multi-point sections MUST lead with bold category titles (e.g., '* **Accountable Parties:** ...').

        # 📋 THE REFIRM UNIVERSAL STANDARD BRIEF FORMAT
        You MUST structure your entire response using ONLY the following sections in this exact order:

        ## CASE VALIDATION & DE-ESCALATION
        - Grounded empathy acknowledging the specific distress, danger, or financial disruption faced.
        - Definitive assessment of whether the case is viable under applicable Indian statutory law based on the documented facts.

        ## STRATEGIC MERITS, STATUTES & PRECEDENTS
        You MUST output exactly four structured bullet points in this section:
        * **Accountable Parties & Joint Liability:** Identify all liable entities (Aggregator/Platform vs. On-ground Franchisee/Vendor). Strip intermediary immunity using explicit statutory pins (e.g., CPA Section 2(42) for electronic service facilities, Consumer Protection (E-Commerce) Rules, 2020, or RERA Promoter guidelines).
        * **Procedural Standing (Locus Standi):** Analyze privity of contract versus beneficial interest. If a third-party paid (friend/family card), cite the specific standing provision from the `<statute_pack>` (e.g., CPA Section 2(7)(ii) for beneficiary standing) confirming the complainant's independent standing.
        * **Statutory Grounds & Specific Breaches:** Map each factual breach to its exact statutory section using the format '[Statute & Section/Sub-clause] ([Legal Standard]) — [Factual Breach]':
          - Deficiency/Breach: (e.g., CPA Section 2(11)(i)/(ii) for negligence and deliberate withholding of relevant information regarding safety-critical repairs).
          - Deceptive/Unfair Trade Practice: (e.g., CPA Section 2(47) for misleading conduct and bad-faith suppression/deletion of consumer reviews).
          - Operative Hazardous Liability: (e.g., CPA Section 85 for Liability of Product Service Provider for harm caused by dangerous service defects, rather than merely defining harm).
        * **Judicial Precedents & Pleading Traps:** Anchor the claim in established Supreme Court or National Appellate doctrines (e.g., Charan Singh v. Healing Touch Hospital for compensation reflecting qualitative misconduct and endangerment). Explicitly warn the user about mandatory pleading traps (e.g., the General Motors doctrine requiring punitive damages to be pleaded under a separate, distinct prayer).

        ## CONCRETE FINANCIAL STRATEGY
        Provide a granular claim structure scaled to the harm:
        * **Actual Financial Loss:** Itemize exact out-of-pocket costs and repair expenses (using "Rs." prefix, NEVER use Unicode currency symbols).
        * **Mental Agony & Exemplary Brackets:**
          * **Conservative Bracket:** (e.g., Rs. X to Rs. Y for documented distress and inconvenience).
          * **Deterrent Bracket:** (e.g., Rs. A to Rs. B for gross negligence, safety hazards, or systemic bad faith).

        ## FORENSIC EVIDENCE PROTOCOL
        Provide a strict Chain of Custody checklist:
        * **Digital Artifacts:** Preservation of emails, app interactions, WhatsApp chats, and portal logs. Explicitly instruct the user to obtain a Section 63 certificate under the Bharatiya Sakshya Adhiniyam, 2023 for all electronic evidence.
        * **Physical & Expert Verification:** Instruct the user to preserve and securely seal any defective physical parts. Explicitly mandate securing a written, signed technical inspection report or independent professional diagnostic report proving the defect or hazard.

        ## EXECUTABLE ACTION PLAN & ARTIFACTS
        You MUST output exactly four numbered steps (1., 2., 3., 4.):
        1. **Evidence Lockdown:** Execute the forensic evidence protocol immediately.
        2. **Dispute / Banking Protocol:** If card/digital payments are involved, provide a verbatim 3-sentence script enclosed in quotation marks directly within this step (e.g., "I wish to dispute the transaction..."). The script MUST be framed strictly around "Defective Services / Breach of Contract" and warn them NEVER to claim "unauthorized transaction". If paid by cash/wire, provide the formal refund demand phrasing. Keep this script entirely inside Step 2 without creating new numbered points.
        3. **Statutory Legal Notice:** Issue a formal 15-day statutory notice to all named opposite parties detailing the statutory grounds, damages, and evidence.
        4. **Regulatory / Judicial Escalation:** If unaddressed within 15 days, file before the designated statutory forum (District Consumer Commission via e-Daakhil under Section 35, RERA Authority, RBI Ombudsman, etc.).

        # CONTEXT INJECTION RULES
        [If and ONLY if the `<assumption_caveats>` XML block is present in the prompt, you MUST append a final section titled exactly "## ASSUMPTIONS MADE" at the very bottom. State the missing jurisdictional or financial facts that required fail-open processing. If the block is absent, omit this section entirely.]
        """;

    private final ChatClient chatClient;
    private final LegalRetrievalService retrievalService;

    public BriefGenerationService(
            @Qualifier("reasoningChatClient") ChatClient chatClient,
            LegalRetrievalService retrievalService) {
        this.chatClient = chatClient;
        this.retrievalService = retrievalService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIMARY DRAFTING METHODS — all use SYSTEM_PROMPT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Primary intake-pipeline drafting method.
     * Accepts a pre-joined statute pack string and a list of assumption caveats
     * surfaced by the rules-engine gatekeeper.
     *
     * @param userStory         Client narrative (Turn 1 + optional Turn 2 clarification)
     * @param statutePack       Concatenated statute pack retrieved by {@link LegalRetrievalService}
     * @param assumptionCaveats Caveats emitted for UNVERIFIED_APPLICABLE statutes (may be empty)
     * @return Formatted Refirm strategic brief in Markdown
     */
    public String generateLegalBrief(String userStory, String statutePack, List<String> assumptionCaveats) {
        // Blank Canvas Guard: bypass LLM when the retrieval service found nothing
        String statutePackContext = (statutePack != null) ? statutePack.trim() : "";
        if (statutePackContext.isBlank()) {
            log.warn("Blank Canvas: No applicable statutes found in statute pack. Bypassing LLM generation.");
            return "No applicable statutory provisions found for this legal domain. "
                    + "Please provide more specific details or consult an advocate.";
        }

        String story = (userStory != null && !userStory.isBlank()) ? userStory.trim() : "No client story provided.";

        // Infinite Story Guard: hard cap prevents context-window explosion
        if (story.length() > MAX_STORY_LENGTH) {
            log.warn("Story length ({}) exceeds MAX_STORY_LENGTH ({}), truncating input.",
                    story.length(), MAX_STORY_LENGTH);
            story = story.substring(0, MAX_STORY_LENGTH);
        }

        log.info("Generating legal brief | story length: {} | statute pack length: {} | caveats: {}",
                story.length(), statutePackContext.length(),
                assumptionCaveats != null ? assumptionCaveats.size() : 0);

        String userPrompt = buildUserPrompt(story, statutePackContext, assumptionCaveats);

        return callLlm(userPrompt);
    }

    /**
     * Overload that accepts the statute pack as a {@link List} of individual statute chunks.
     * Joins them with double newlines and delegates to the primary method.
     *
     * <p>This satisfies consumers (e.g., tests or future callers) that pass individual
     * statute strings rather than a pre-joined block, without duplicating any drafting logic.
     *
     * @param userStory         Client narrative
     * @param statutePack       Individual statute chunks to be joined into one context block
     * @param assumptionCaveats Assumption caveats from the rules-engine gatekeeper
     * @return Formatted Refirm strategic brief in Markdown
     */
    public String generateLegalBrief(String userStory, List<String> statutePack, List<String> assumptionCaveats) {
        String joinedPack = (statutePack != null)
                ? String.join("\n\n", statutePack)
                : "";
        return generateLegalBrief(userStory, joinedPack, assumptionCaveats);
    }

    /**
     * Backward-compatible convenience overload — no assumption caveats.
     */
    public String generateLegalBrief(String userStory, String statutePack) {
        return generateLegalBrief(userStory, statutePack, List.of());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STRUCTURED-FACTS DRAFTING (DraftController / legacy checklist flow)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a strategic brief from a structured fact map produced by the checklist flow.
     * Uses the same {@link #SYSTEM_PROMPT} — no petition formatting, no prayer section.
     *
     * @param gatheredFacts  Key-value map of facts (e.g., "nature_of_dispute" -> "Illegal lockout")
     * @param legalDomain    Raw enum string or human label (e.g., "CIVIL_PROPERTY")
     * @param categoryLabel  Human-readable category label (e.g., "Civil & Property Law")
     * @return {@link GenerateBriefResponse} containing draft markdown, retrieved statutes, and hasStatutes flag
     */
    public GenerateBriefResponse generateDraftFromFacts(Map<String, String> gatheredFacts,
                                                        String legalDomain,
                                                        String categoryLabel) {
        log.info("Generating draft from {} structured facts for domain: {}", gatheredFacts.size(), legalDomain);

        String structuredFactsText = formatGatheredFacts(gatheredFacts, categoryLabel);

        String retrievedLaws;
        boolean hasStatutes = true;
        try {
            retrievedLaws = retrievalService.retrieveRelevantStatutes(structuredFactsText, legalDomain);
            log.info("Successfully retrieved statutes for domain: {}", legalDomain);
        } catch (IllegalStateException e) {
            if ("INSUFFICIENT_STATUTES".equals(e.getMessage())) {
                log.warn("No statutes found for domain: {}. Proceeding with advisory note.", legalDomain);
                hasStatutes = false;
                retrievedLaws = "[NO STATUTES RETRIEVED — The vector database did not return relevant statutory "
                        + "provisions for domain: " + legalDomain
                        + ". The advocate must independently research and cite applicable Acts and Sections.]";
            } else {
                throw e;
            }
        }

        String userPrompt = buildUserPrompt(
                structuredFactsText,
                (retrievedLaws != null && !retrievedLaws.isBlank()) ? retrievedLaws : "EMPTY",
                List.of()
        );

        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model("o3-mini")
                .build();

        String draft = chatClient.prompt()
                .options(chatOptions)
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        log.info("Draft generation complete. Output length: {} chars", draft != null ? draft.length() : 0);
        return new GenerateBriefResponse(draft, retrievedLaws, hasStatutes);
    }

    /**
     * Legacy method — accepts raw text facts and a domain string.
     * Uses the same {@link #SYSTEM_PROMPT} — no petition formatting, no prayer section.
     */
    public GenerateBriefResponse generateDraft(String sessionFacts, String legalDomain) {
        log.info("generateDraft called with domain: {}", legalDomain);

        String retrievedLaws;
        boolean hasStatutes = true;
        try {
            retrievedLaws = retrievalService.retrieveRelevantStatutes(sessionFacts, legalDomain);
        } catch (IllegalStateException e) {
            if ("INSUFFICIENT_STATUTES".equals(e.getMessage())) {
                log.warn("No statutes found for legacy domain: {}", legalDomain);
                hasStatutes = false;
                retrievedLaws = "[NO STATUTES RETRIEVED — The vector database did not return relevant statutory "
                        + "provisions for domain: " + legalDomain
                        + ". The advocate must independently research and cite applicable Acts and Sections.]";
            } else {
                throw e;
            }
        }

        String facts = (sessionFacts != null && !sessionFacts.isBlank()) ? sessionFacts : "No facts provided";
        String retrievedContext = (retrievedLaws != null && !retrievedLaws.isBlank()) ? retrievedLaws : "EMPTY";

        String userPrompt = buildUserPrompt(facts, retrievedContext, List.of());

        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model("o3-mini")
                .build();

        String draft = chatClient.prompt()
                .options(chatOptions)
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        return new GenerateBriefResponse(draft, retrievedLaws, hasStatutes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Assembles the XML-tagged user message that the LLM receives.
     * The {@code <assumption_caveats>} block is included only when caveats are present,
     * which triggers the CONTEXT INJECTION RULES clause in {@link #SYSTEM_PROMPT}.
     */
    private String buildUserPrompt(String story, String statutePackContext, List<String> assumptionCaveats) {
        StringBuilder userPrompt = new StringBuilder();

        userPrompt.append("<case_facts>\n").append(story).append("\n</case_facts>\n\n");

        if (assumptionCaveats != null && !assumptionCaveats.isEmpty()) {
            userPrompt.append("<assumption_caveats>\n");
            assumptionCaveats.forEach(caveat -> userPrompt.append("- ").append(caveat).append("\n"));
            userPrompt.append("</assumption_caveats>\n\n");
        }

        userPrompt.append("<statute_pack>\n").append(statutePackContext).append("\n</statute_pack>");

        return userPrompt.toString();
    }

    /**
     * Invokes the ChatClient with {@link #SYSTEM_PROMPT} and sanitises the raw output.
     */
    private String callLlm(String userPrompt) {
        try {
            OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                    .model("o3-mini")
                    .build();

            String rawOutput = chatClient.prompt()
                    .options(chatOptions)
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            if (rawOutput == null || rawOutput.isBlank()) {
                throw new IllegalStateException("LLM returned an empty response.");
            }

            // Strip surrounding markdown code fences if the LLM wraps the output
            return rawOutput
                    .replaceAll("(?s)^```(?:markdown)?\\s*", "")
                    .replaceAll("(?s)```\\s*$", "")
                    .trim();

        } catch (Exception e) {
            log.error("Failed to generate brief via ChatClient: {}", e.getMessage(), e);
            return "The legal drafting engine is temporarily unavailable. Please try again later.";
        }
    }

    /**
     * Formats the gathered facts map into a clean, structured text block
     * that serves as both the RAG query and the user context for the drafting LLM.
     */
    private String formatGatheredFacts(Map<String, String> gatheredFacts, String categoryLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("## CASE CATEGORY: ").append(categoryLabel != null ? categoryLabel : "General Legal").append("\n\n");
        sb.append("## GATHERED FACTS:\n\n");

        if (gatheredFacts != null) {
            for (Map.Entry<String, String> entry : gatheredFacts.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                // Represent missing/unknown facts as explicit placeholders so the LLM doesn't hallucinate
                if (value == null || value.isBlank() || "UNKNOWN".equalsIgnoreCase(value.trim())) {
                    sb.append("- **").append(formatKey(key)).append(":** [INFORMATION NOT PROVIDED — use placeholder]\n");
                } else {
                    sb.append("- **").append(formatKey(key)).append(":** ").append(value).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Converts snake_case keys to Human Readable Labels.
     * e.g., "nature_of_dispute" → "Nature Of Dispute"
     */
    private String formatKey(String snakeCaseKey) {
        if (snakeCaseKey == null) return "";
        String[] words = snakeCaseKey.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1))
                        .append(" ");
            }
        }
        return sb.toString().trim();
    }
}