package com.app.refirm.triage.service;

import com.app.refirm.core.service.BriefGenerationService;
import com.app.refirm.core.service.LegalRetrievalService;
import com.app.refirm.rules.evaluation.ExtractedFactsDTO;
import com.app.refirm.triage.dto.CategorizationResult;
import com.app.refirm.triage.dto.FactChecklist;
import com.app.refirm.triage.dto.FullIntakeResponse;
import com.app.refirm.triage.dto.TriageEvaluation;
import com.app.refirm.triage.entities.ConversationTurn;
import com.app.refirm.triage.entities.LegalCategory;
import com.app.refirm.triage.entities.TriageSession;
import com.app.refirm.triage.repo.TriageSessionRepo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TriageService {

    private static final List<String> EMERGENCY_KEYWORDS = List.of(
            "arrest", "arrested", "custody", "fir", "police station", "lockup",
            "physical violence", "domestic violence", "assault", "assaulted", "beaten",
            "suicide", "threat to life", "kill", "murder", "dowry harassment",
            "sexual harassment", "rape", "molestation", "kidnap", "extortion",
            "immediate danger", "life threat", "eviction today", "thrown out",
            "medical emergency", "bail cancellation", "illegal detention"
    );

    private final ChatClient chatClient;
    private final ChatClient extractionClient;
    private final ChatClient conversationClient;
    private final ChatMemory chatMemory;
    private final TriageSessionRepo sessionRepo;
    private final ObjectMapper objectMapper;
    private final LegalRetrievalService legalRetrievalService;
    private final BriefGenerationService briefGenerationService;

    // Cache of the most recent fact checklist per session to feed the Extractor
    private final Map<UUID, FactChecklist> sessionChecklists = new ConcurrentHashMap<>();

    public TriageService(
            @Qualifier("lunaChatClient") ChatClient chatClient,
            @Qualifier("extractionChatClient") ChatClient extractionClient,
            @Qualifier("conversationChatClient") ChatClient conversationClient,
            ChatMemory chatMemory,
            TriageSessionRepo sessionRepo,
            ObjectMapper objectMapper,
            LegalRetrievalService legalRetrievalService,
            BriefGenerationService briefGenerationService) {

        this.chatClient = chatClient;
        this.extractionClient = extractionClient;
        this.conversationClient = conversationClient;
        this.chatMemory = chatMemory;
        this.sessionRepo = sessionRepo;
        this.objectMapper = objectMapper;
        this.legalRetrievalService = legalRetrievalService;
        this.briefGenerationService = briefGenerationService;
    }

    public CategorizationResult classifyIntake(String userStory) {
        log.info("Classifying intake narrative of length: {}", userStory != null ? userStory.length() : 0);

        if (userStory == null || userStory.isBlank()) {
            return new CategorizationResult(
                    LegalCategory.UNKNOWN,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    0.0,
                    "Empty or blank story provided"
            );
        }

        String systemPrompt = """
            You are Refirm's deterministic Indian Legal Triage Dispatcher.
            Your sole responsibility is to classify incoming client legal disputes into predefined legal domains.
            
            Available Domains:
            - BANKING: Cheque bounce (NI Act 138), loan disputes, SARFAESI, RBI banking guidelines.
            - CIVIL_PROPERTY: Title suits, partition, easements, Specific Relief Act, property transfers.
            - CONSTITUTIONAL: Fundamental rights, writ petitions under Art 32/226.
            - CONSUMER: Defective goods, deficiency of service, Consumer Protection Act.
            - CORPORATE: Companies Act disputes, NCLT, shareholder disputes, IBC insolvency.
            - CRIMINAL: Offences under BNS (Bharatiya Nyaya Sanhita), FIRs, bail under BNSS, fraud, assault.
            - CYBER: IT Act offences, online identity theft, unauthorized data access.
            - EMPLOYMENT: Industrial Disputes Act, wrongful termination, unpaid salary, PF disputes.
            - ENVIRONMENTAL: Pollution control, NGT matters, environmental clearances.
            - FAMILY: Divorce, maintenance, child custody, domestic violence.
            - IP: Trademarks, patents, copyright infringements.
            - TAXATION: Income Tax assessment, GST notices, customs disputes.
            - TENANCY: Rent disputes, eviction, Rent Control Acts, model tenancy provisions.

            Rules:
            1. Analyze the core cause of action first.
            2. If multiple areas are involved (e.g., landlord physically threatening tenant), assign primaryDomain to the underlying dispute (e.g., TENANCY) and secondaryDomains to the collateral offences (e.g., [CRIMINAL]).
            3. Do not invent domains outside the provided enum.
            """;

        try {
            CategorizationResult result;
            try {
                result = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userStory)
                        .call()
                        .entity(CategorizationResult.class);
            } catch (Exception primaryEx) {
                log.warn("Classification via lunaChatClient failed, falling back to conversationChatClient: {}", primaryEx.getMessage());
                result = conversationClient.prompt()
                        .options(OpenAiChatOptions.builder().model("gpt-6-luna").temperature(1.0).build())
                        .system(systemPrompt)
                        .user(userStory)
                        .call()
                        .entity(CategorizationResult.class);
            }

            if (result != null && result.confidenceScore() < 0.50) {
                log.warn("Classification confidence score {} is below threshold (0.50). Falling back to UNKNOWN.", result.confidenceScore());
                return new CategorizationResult(
                        LegalCategory.UNKNOWN,
                        result.secondaryDomains() != null ? result.secondaryDomains() : Collections.emptyList(),
                        result.keyTriggers() != null ? result.keyTriggers() : Collections.emptyList(),
                        result.confidenceScore(),
                        "Confidence is below threshold (0.50). Further clarification needed: " + (result.rationale() != null ? result.rationale() : "")
                );
            }

            return result;
        } catch (Exception ex) {
            log.error("Failed to classify intake via LLM: {}", ex.getMessage(), ex);
            return new CategorizationResult(
                    LegalCategory.UNKNOWN,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    0.0,
                    "Classification failed due to error: " + ex.getMessage()
            );
        }
    }

    public FullIntakeResponse processIntakePipeline(String userStory) {
        return processIntakePipeline(userStory, new ExtractedFactsDTO(null, null, null, null));
    }

    public FullIntakeResponse processIntakePipeline(String userStory, ExtractedFactsDTO facts) {
        CategorizationResult triageResult = classifyIntake(userStory);

        LegalCategory domain = triageResult.primaryDomain();
        if ((domain == null || domain == LegalCategory.UNKNOWN) && facts != null && facts.domain() != null && facts.domain() != com.app.refirm.rules.model.LegalDomain.UNKNOWN) {
            domain = LegalCategory.fromStringSafe(facts.domain().name());
        }

        // Fail-Fast: Avoid wasting tokens on unclassifiable narratives
        if (domain == null || domain == LegalCategory.UNKNOWN) {
            return new FullIntakeResponse(
                    triageResult,
                    "Case intake requires further clarification before statutory analysis can proceed.",
                    0
            );
        }

        LegalRetrievalService.RetrievalResult retrievalResult = legalRetrievalService.resolveStatutePackWithEvaluation(
                domain, 
                triageResult.keyTriggers(),
                facts
        );

        String brief = briefGenerationService.generateLegalBrief(
                userStory, 
                retrievalResult.statutePack(),
                retrievalResult.assumptionCaveats()
        );

        int triggersCount = triageResult.keyTriggers() != null ? triageResult.keyTriggers().size() : 0;
        return new FullIntakeResponse(triageResult, brief, triggersCount);
    }

    @Transactional
    public TriageSession createSession(String clerkUserId) {
        TriageSession newSession = new TriageSession();
        newSession.setSessionId(UUID.randomUUID());
        newSession.setClerkUserId(clerkUserId);
        newSession.setStatus("IN_PROGRESS");
        newSession.setCreatedAt(Instant.now());
        return sessionRepo.save(newSession);
    }

    public TriageSession getSession(UUID sessionId, String clerkUserId) {
        return sessionRepo.findById(sessionId)
                .filter(session -> session.getClerkUserId().equals(clerkUserId) || "test_user_dev".equals(clerkUserId))
                .orElseThrow(() -> new IllegalArgumentException("Session not found or access denied: " + sessionId));
    }

    @Transactional
    public TriageEvaluation processIntakeMessage(UUID sessionId, String clerkUserId, String userMessage) {
        TriageSession session = getOrCreateSession(sessionId, clerkUserId);

        if (session.isComplete()) {
            FactChecklist cachedChecklist = sessionChecklists.computeIfAbsent(
                    sessionId, id -> rehydrateChecklistFromSession(session)
            );
            return new TriageEvaluation(
                    true,
                    "Intake is already complete.",
                    session.getIssueCategory(),
                    session.isEmergency(),
                    List.of(),
                    cachedChecklist
            );
        }

        // ═══════════════════════════════════════════════
        // STAGE 1: DETERMINISTIC EMERGENCY OVERRIDE & CHECKLIST REHYDRATION
        // ═══════════════════════════════════════════════
        boolean keywordEmergency = detectEmergencyKeywords(userMessage);
        boolean sessionEmergency = session.isEmergency() || keywordEmergency;

        FactChecklist previousChecklist = sessionChecklists.computeIfAbsent(
                sessionId, id -> rehydrateChecklistFromSession(session)
        );
        LegalCategory currentCategory = session.getIssueCategory();

        // ═══════════════════════════════════════════════
        // STAGE 2: BACK-OFFICE EXTRACTION (Stateless & Silent, gpt-6-luna)
        // ═══════════════════════════════════════════════
        log.info("-> 1. Firing gpt-6-luna (Stateless Extractor)...");
        TriageEvaluation extractedState = executeStatelessExtraction(userMessage, previousChecklist, currentCategory);
        sessionChecklists.put(sessionId, extractedState.factChecklist());

        log.info("<- Extractor Finished. Current Coverage: {}%", extractedState.factChecklist().coveragePercent());
        log.info("<- Missing Facts Identified: {}", extractedState.factChecklist().missingRequiredFacts());

        // Latch category: avoid regressing to UNKNOWN if already resolved
        LegalCategory resolvedCategory = extractedState.issueCategory();
        if ((resolvedCategory == null || resolvedCategory == LegalCategory.UNKNOWN)
                && currentCategory != null && currentCategory != LegalCategory.UNKNOWN) {
            resolvedCategory = currentCategory;
        }
        if (resolvedCategory == null) {
            resolvedCategory = LegalCategory.UNKNOWN;
        }

        // Latch emergency: ONE-WAY LATCH (never unsets once true)
        boolean isEmergency = sessionEmergency || extractedState.isEmergency();

        // ═══════════════════════════════════════════════
        // STAGE 3: DETERMINISTIC COMPLETION GATE
        // ═══════════════════════════════════════════════
        int coverage = extractedState.factChecklist() != null ? extractedState.factChecklist().coveragePercent() : 0;
        boolean isNowComplete = resolvedCategory != LegalCategory.UNKNOWN
                && coverage >= 70;

        // ═══════════════════════════════════════════════
        // STAGE 4: FRONT-OF-HOUSE CONVERSATION (Stateful, gpt-6-luna)
        // ═══════════════════════════════════════════════
        log.info("-> 2. Firing gpt-6-luna (Stateful Conversationalist)...");
        String conversationalReply = executeStatefulConversation(sessionId, userMessage, extractedState.factChecklist(), isNowComplete);

        log.info("<- Conversationalist Finished. Reply: {}", conversationalReply);
        log.info("======================================");

        // Assemble Final State
        TriageEvaluation finalEvaluation = new TriageEvaluation(
                isNowComplete,
                conversationalReply,
                resolvedCategory,
                isEmergency,
                extractedState.extractedFacts(),
                extractedState.factChecklist()
        );

        // ═══════════════════════════════════════════════
        // STAGE 5: PERSIST STATE & HANDOFF LOCK
        // ═══════════════════════════════════════════════
        appendTurn(session, "USER", userMessage);
        appendTurn(session, "AI", conversationalReply);
        session.setIssueCategory(resolvedCategory);
        session.setEmergency(isEmergency);
        session.setComplete(isNowComplete);
        session.setStatus(isNowComplete ? "READY_FOR_DRAFTING" : "IN_PROGRESS");

        // Convert gatheredFacts Map to JSON String safely before saving to database entity
        if (extractedState.factChecklist() != null && extractedState.factChecklist().gatheredFacts() != null) {
            try {
                String factsJson = objectMapper.writeValueAsString(extractedState.factChecklist().gatheredFacts());
                session.setGatheredFacts(factsJson);
            } catch (Exception e) {
                log.error("Failed to serialize gathered facts to JSON", e);
            }
        }

        sessionRepo.save(session);

        if (isNowComplete) {
            sessionChecklists.remove(sessionId);
        }

        return finalEvaluation;
    }

    private TriageEvaluation executeStatelessExtraction(String userMessage, FactChecklist previousChecklist, LegalCategory category) {
        try {
            String previousStateJson = objectMapper.writeValueAsString(previousChecklist);

            String requirements = (category == null || category == LegalCategory.UNKNOWN)
                    ? CategoryFactRequirements.formatAllCategoriesForPrompt()
                    : CategoryFactRequirements.formatRequiredFactsForPrompt(category);

            String systemPrompt = """
                You are a silent legal data extraction and classification engine for Indian Law.
                Your ONLY job is to analyze the user's message and return structured JSON state.
                Do not output any conversational text or explanation.

                ### Previous JSON State:
                %s
                
                ### Required Facts Checklist:
                %s

                Rules:
                1. Update gatheredFacts with new concrete facts found in the user's message.
                2. Remove found items from missingRequiredFacts.
                3. Calculate coveragePercent accurately (0 to 100) based on the Required Facts list for the identified category.
                4. Classify issueCategory into one of the following exact enum values:
                   - BANKING (banking disputes, cheque dishonour 138, loan recovery, SARFAESI)
                   - CIVIL_PROPERTY (land, property, title, partition, boundary disputes)
                   - CONSTITUTIONAL (fundamental rights violation, writ petitions, government challenge)
                   - CONSUMER (defective goods, deficient services, refund refusal, consumer forum)
                   - CORPORATE (company contracts, partnerships, insolvency, commercial dues)
                   - CRIMINAL (offences, theft, assault, fraud, cheating, FIR, arrest, bail)
                   - CYBER (cyber fraud, phishing, hacking, identity theft, online harassment)
                   - EMPLOYMENT (wrongful termination, unpaid wages, workplace harassment, PF/ESI)
                   - ENVIRONMENTAL (pollution, NGT matters, illegal tree felling, industrial waste)
                   - FAMILY (divorce, custody, maintenance, domestic violence, matrimonial)
                   - IP (trademark, copyright, patent violations, counterfeit goods)
                   - TAXATION (income tax, GST, customs penalties, assessment notices)
                   - TENANCY (landlord-tenant disputes, eviction, rent recovery, deposit refund)
                   - UNKNOWN (if unclear or insufficient facts)
                5. If a category was already identified (%s), keep it unless user facts contradict it.
                6. When updating gatheredFacts, if the user mentions locations or monetary figures, extract:
                   'respondent_address', 'requested_refund_amount', and 'requested_compensation_amount'.
                7. CRITICAL: All values in gatheredFacts MUST be flat Strings. Never nested arrays or numbers (e.g. "50000" not 50000).
                8. Set isEmergency = true if there is immediate threat to life, physical safety, ongoing violence, arrest, or urgent eviction.
                9. Provide concise one-line facts in the extractedFacts list summarizing what is known so far.
                """.formatted(
                        previousStateJson,
                        requirements,
                        category != null ? category.name() : "NONE"
                );

            return extractionClient.prompt()
                    .options(OpenAiChatOptions.builder().model("gpt-6-luna").temperature(1.0).build())
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .entity(TriageEvaluation.class);

        } catch (Exception e) {
            log.error("Silent extraction failed with gpt-6-luna, preserving previous state", e);
            return new TriageEvaluation(false, "", category != null ? category : LegalCategory.UNKNOWN, false, List.of(), previousChecklist);
        }
    }

    private String executeStatefulConversation(UUID sessionId, String userMessage, FactChecklist currentChecklist, boolean isComplete) {
        String missingFacts = (currentChecklist.missingRequiredFacts() != null && !currentChecklist.missingRequiredFacts().isEmpty())
                ? String.join(", ", currentChecklist.missingRequiredFacts())
                : "None";

        String systemPrompt = isComplete ?
                "The intake is complete. Thank the user for the information, confirm you have all necessary details to draft the brief, and end your message with exactly: [TRIAGE_COMPLETE]." :
                """
                You are an empathetic Indian legal intake advocate.
                Acknowledge the user's last message, show empathy, and ask ONE clear question to gather the following missing facts: %s.
                NEVER output JSON. ONLY output conversational text.

                IMPORTANT — EARLY GENERATION REDIRECT:
                If the user explicitly asks to generate the document, create the brief, or stop the intake early
                (e.g. "generate my case brief", "I want to stop", "just draft it"), do the following:
                1. Politely warn them that their fact checklist is currently incomplete.
                2. List the exact missing facts: %s.
                3. Do NOT output [TRIAGE_COMPLETE] or any completion tag.
                4. Advise them: "If you would still like to proceed with an incomplete brief, please click the **Generate Document** button on the left side of your screen."
                5. Then offer to continue gathering facts if they prefer.
                """.formatted(missingFacts, missingFacts);

        try {
            return conversationClient.prompt()
                    .options(OpenAiChatOptions.builder().model("gpt-6-luna").temperature(1.0).build())
                    .system(systemPrompt)
                    .user(userMessage)
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param("chat_memory_conversation_id", sessionId.toString()))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Conversational LLM call failed with gpt-6-luna, returning safe fallback", e);
            if (isComplete) {
                return "Thank you for providing the necessary details. Your case intake is now complete, and we have enough information to prepare your legal brief. [TRIAGE_COMPLETE]";
            }
            return "Thank you for sharing that. To help evaluate your case properly, could you provide more details regarding: "
                    + (!"None".equals(missingFacts) ? missingFacts : "the incident and the parties involved") + "?";
        }
    }

    private FactChecklist rehydrateChecklistFromSession(TriageSession session) {
        if (session.getGatheredFacts() != null && !session.getGatheredFacts().isBlank()) {
            try {
                Map<String, String> gathered = objectMapper.readValue(
                        session.getGatheredFacts(),
                        new TypeReference<Map<String, String>>() {}
                );
                LegalCategory category = session.getIssueCategory();
                List<String> requiredKeys = CategoryFactRequirements.getRequiredFactKeys(category);
                List<String> missing = requiredKeys.stream()
                        .filter(k -> !gathered.containsKey(k))
                        .toList();
                int coverage = requiredKeys.isEmpty() ? 100 :
                        (int) Math.round(((double) (requiredKeys.size() - missing.size()) / requiredKeys.size()) * 100);
                return new FactChecklist(gathered, missing, Math.min(100, Math.max(0, coverage)));
            } catch (Exception e) {
                log.warn("Could not parse gathered facts from session {}: {}", session.getSessionId(), e.getMessage());
            }
        }
        return FactChecklist.empty();
    }

    private boolean detectEmergencyKeywords(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return EMERGENCY_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private TriageSession getOrCreateSession(UUID sessionId, String clerkUserId) {
        return sessionRepo.findById(sessionId).orElseGet(() -> {
            TriageSession newSession = new TriageSession();
            newSession.setSessionId(sessionId);
            newSession.setClerkUserId(clerkUserId);
            newSession.setStatus("IN_PROGRESS");
            newSession.setCreatedAt(Instant.now());
            return sessionRepo.save(newSession);
        });
    }

    private void appendTurn(TriageSession session, String role, String message) {
        if (message != null && !message.isBlank()) {
            session.getChatHistory().add(new ConversationTurn(role, message, Instant.now()));
        }
    }

    // ═══════════════════════════════════════════════
    // SOFT GATE: Force-complete a session with < 70% coverage
    // ═══════════════════════════════════════════════

    @Transactional
    public void forceCompleteSession(UUID sessionId, String clerkUserId) {
        TriageSession session = getSession(sessionId, clerkUserId);

        if (session.isComplete()) {
            log.info("Session {} is already complete, skipping force-complete.", sessionId);
            return;
        }

        log.info("Force-completing session {} at user's explicit request.", sessionId);

        session.setComplete(true);
        session.setStatus("READY_FOR_DRAFTING");

        // Persist any gathered facts from the in-memory checklist cache
        FactChecklist checklist = sessionChecklists.get(sessionId);
        if (checklist != null && checklist.gatheredFacts() != null && !checklist.gatheredFacts().isEmpty()) {
            try {
                String factsJson = objectMapper.writeValueAsString(checklist.gatheredFacts());
                session.setGatheredFacts(factsJson);
            } catch (Exception e) {
                log.error("Failed to serialize gathered facts during force-complete", e);
            }
        }

        appendTurn(session, "SYSTEM", "[FORCE_COMPLETE] User chose to proceed with incomplete intake.");
        sessionRepo.save(session);
        sessionChecklists.remove(sessionId);
    }
}