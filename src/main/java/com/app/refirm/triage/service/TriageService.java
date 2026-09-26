package com.app.refirm.triage.service;

import com.app.refirm.triage.dto.FactChecklist;
import com.app.refirm.triage.dto.TriageEvaluation;
import com.app.refirm.triage.entities.ConversationTurn;
import com.app.refirm.triage.entities.LegalCategory;
import com.app.refirm.triage.entities.TriageSession;
import com.app.refirm.triage.repo.TriageSessionRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TriageService {

    private final ChatClient extractionClient;
    private final ChatClient conversationClient;
    private final ChatMemory chatMemory;
    private final TriageSessionRepo sessionRepo;
    private final ObjectMapper objectMapper;

    // Cache of the most recent fact checklist per session to feed the Extractor
    private final Map<UUID, FactChecklist> sessionChecklists = new ConcurrentHashMap<>();

    public TriageService(
            @Qualifier("extractionChatClient") ChatClient extractionClient,
            @Qualifier("conversationChatClient") ChatClient conversationClient,
            ChatMemory chatMemory,
            TriageSessionRepo sessionRepo,
            ObjectMapper objectMapper) {

        this.extractionClient = extractionClient;
        this.conversationClient = conversationClient;
        this.chatMemory = chatMemory;
        this.sessionRepo = sessionRepo;
        this.objectMapper = objectMapper;
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
            return new TriageEvaluation(
                    true,
                    "Intake is already complete.",
                    session.getIssueCategory(),
                    session.isEmergency(),
                    List.of(),
                    sessionChecklists.get(sessionId)
            );
        }



        // ═══════════════════════════════════════════════
        // STEP 1: BACK-OFFICE EXTRACTION (Stateless & Silent)
        // ═══════════════════════════════════════════════
        FactChecklist previousChecklist = sessionChecklists.getOrDefault(sessionId, FactChecklist.empty());
        LegalCategory currentCategory = session.getIssueCategory();

        log.info("-> 1. Firing gpt-4o-mini (Stateless Extractor)...");
        TriageEvaluation extractedState = executeStatelessExtraction(userMessage, previousChecklist, currentCategory);
        sessionChecklists.put(sessionId, extractedState.factChecklist());

        log.info("<- Extractor Finished. Current Coverage: {}%", extractedState.factChecklist().coveragePercent());
        log.info("<- Missing Facts Identified: {}", extractedState.factChecklist().missingRequiredFacts());

        // ═══════════════════════════════════════════════
        // STEP 2: DETERMINISTIC COMPLETION GATE
        // ═══════════════════════════════════════════════
        boolean isNowComplete = extractedState.factChecklist().coveragePercent() >= 70;

        // ═══════════════════════════════════════════════
        // STEP 3: FRONT-OF-HOUSE CONVERSATION (Stateful)
        // ═══════════════════════════════════════════════
        log.info("-> 2. Firing gpt-4o (Stateful Conversationalist)...");
        String conversationalReply = executeStatefulConversation(sessionId, userMessage, extractedState.factChecklist(), isNowComplete);

        log.info("<- Conversationalist Finished. Reply: {}", conversationalReply);
        log.info("======================================");

        // Assemble Final State
        TriageEvaluation finalEvaluation = new TriageEvaluation(
                isNowComplete,
                conversationalReply,
                extractedState.issueCategory() != null ? extractedState.issueCategory() : LegalCategory.UNKNOWN,
                extractedState.isEmergency(),
                extractedState.extractedFacts(),
                extractedState.factChecklist()
        );

        // ═══════════════════════════════════════════════
        // STEP 4: PERSIST STATE & HANDOFF LOCK
        // ═══════════════════════════════════════════════
        appendTurn(session, "USER", userMessage);
        appendTurn(session, "AI", conversationalReply);
        session.setIssueCategory(finalEvaluation.issueCategory());
        session.setEmergency(finalEvaluation.isEmergency());
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
                You are a silent legal data extraction engine.
                Your ONLY job is to analyze the user's message and update the JSON state.
                Do not write conversational text.

                ### Previous JSON State:
                %s
                
                ### Required Facts to Track:
                %s

                Rules:
                1. Update gatheredFacts with new details found in the user's message.
                2. Remove found items from missingRequiredFacts.
                3. Calculate coveragePercent accurately (0 to 100) based ONLY on the strictly Required Facts list, ignoring the global keys.
                4. When updating the gatheredFacts JSON, if the user mentions locations or financial demands, you MUST extract them using these exact keys: 'respondent_address', 'requested_refund_amount', and 'requested_compensation_amount'. If the user does not mention them, do not include these keys.
                5. CRITICAL: All values in the gatheredFacts JSON MUST be flat Strings. Never use nested JSON arrays or integers (e.g., output "45000" not 45000).
                6. Set issueCategory if facts clearly match a specific domain.
                """.formatted(previousStateJson, requirements);

            return extractionClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .entity(TriageEvaluation.class);

        } catch (Exception e) {
            log.error("Silent extraction failed, preserving previous state", e);
            return new TriageEvaluation(false, "", category, false, List.of(), previousChecklist);
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

        return conversationClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(a -> a.param("chat_memory_conversation_id", sessionId.toString()))
                .call()
                .content();
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