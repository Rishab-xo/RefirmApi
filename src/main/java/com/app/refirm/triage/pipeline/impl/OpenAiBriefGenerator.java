package com.app.refirm.triage.pipeline.impl;

import com.app.refirm.triage.domain.DraftingContext;
import com.app.refirm.triage.domain.PartyEntity;
import com.app.refirm.triage.pipeline.BriefGenerator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class OpenAiBriefGenerator implements BriefGenerator {

    private final ChatClient chatClient;

    @Autowired
    public OpenAiBriefGenerator(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o")      // Switched to gpt-4o for empathetic prose & perfect Markdown
                        .temperature(0.2)     // Low temperature for structured compliance
                        .build())
                .build();
    }

    public OpenAiBriefGenerator(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String generateDraft(DraftingContext context) {
        if (context == null) {
            throw new IllegalArgumentException("DraftingContext cannot be null");
        }

        // 1. Sanitize and Format Parties (Prevents Prompt Leakage of snake_case Enums)
        String claimantBlock = (context.facts() != null) ? formatParty(context.facts().claimant()) : "None";
        String payerBlock = (context.facts() != null) ? formatParty(context.facts().paymentInstrumentHolder()) : "None";
        
        String oppositePartiesBlock = "None identified";
        if (context.facts() != null && context.facts().oppositeParties() != null && !context.facts().oppositeParties().isEmpty()) {
            oppositePartiesBlock = context.facts().oppositeParties().stream()
                    .map(this::formatParty)
                    .collect(Collectors.joining("\n"));
        }

        // 2. Format Statutory Invariants
        String limitationRule = (context.limitationMonths() != null)
                ? context.limitationMonths() + " Months (from the date on which the cause of action arose; verify exact section in <statute_pack>)"
                : "Not fixed as a flat month integer in DSL — Cite ONLY the specific limitation/time-bar section present in <statute_pack>, or state that general limitation under The Limitation Act, 1963 applies from the date the cause of action accrued (NEVER guess or invent a month count)";
        String civilCourts = (context.civilCourtsBarred() == null)
                ? "Refer to governing statute in <statute_pack>"
                : (Boolean.TRUE.equals(context.civilCourtsBarred()) ? "Barred (Exclusive Statutory Forum)" : "Permitted");

        String formattedStatutePack = "No operative statutes resolved.";
        if (context.statutePack() != null && !context.statutePack().isEmpty()) {
            formattedStatutePack = context.statutePack().stream()
                    .map(pin -> "- " + pin.actName() + " " + pin.sectionNumber() + " (" + pin.type() + "): " + pin.operativeText())
                    .collect(Collectors.joining("\n"));
        }

        String targetForumStr = (context.targetForum() != null)
                ? String.format("%s | Filing Mode: %s | Pecuniary Basis: %s",
                        context.targetForum().forumName(),
                        context.targetForum().filingMethod() != null ? context.targetForum().filingMethod() : "Statutory Filing",
                        context.targetForum().jurisdictionReason() != null ? context.targetForum().jurisdictionReason() : "Within statutory threshold")
                : "Appropriate Statutory Tribunal";

        // 3. Build XML Context Envelope
        String userPayload = String.format("""
                <user_narrative>
                %s
                </user_narrative>
                
                <claimant>
                %s
                </claimant>
                
                <payment_instrument_holder>
                %s
                </payment_instrument_holder>
                
                <opposite_parties>
                %s
                </opposite_parties>
                
                <statute_pack>
                %s
                </statute_pack>
                
                <target_forum>
                %s
                </target_forum>
                
                <statutory_invariants>
                Limitation Period: %s
                Civil Courts Barred: %s
                Evidence Admissibility: %s
                </statutory_invariants>
                
                <assumption_caveats>
                %s
                </assumption_caveats>
                """,
                context.userNarrative() != null ? context.userNarrative() : "",
                claimantBlock,
                payerBlock,
                oppositePartiesBlock,
                formattedStatutePack,
                targetForumStr,
                limitationRule,
                civilCourts,
                context.evidenceRule() != null ? context.evidenceRule() : "Section 63 Certificate, Bharatiya Sakshya Adhiniyam, 2023",
                context.assumptionCaveats() != null && !context.assumptionCaveats().isEmpty() ? String.join("\n", context.assumptionCaveats()) : "None"
        );

        PromptTemplate template = new PromptTemplate(SYSTEM_PROMPT);
        template.add("context_envelope", userPayload);

        // Fetch raw markdown response
        String response = chatClient.prompt(template.create()).call().content();
        if (response == null || response.isBlank()) {
            return "";
        }
        
        // Strip markdown code fences if gpt-4o accidentally wraps the entire output
        String clean = response.replaceAll("(?s)^```(?:markdown)?\\s*", "").replaceAll("(?s)```\\s*$", "").trim();

        // Ensure assumption caveats are deterministically preserved if present and not in output
        if (context.assumptionCaveats() != null && !context.assumptionCaveats().isEmpty() && !clean.contains("## ASSUMPTIONS MADE")) {
            clean = clean + "\n\n## ASSUMPTIONS MADE\n" +
                    context.assumptionCaveats().stream()
                            .map(c -> "- " + c)
                            .collect(Collectors.joining("\n"));
        }

        // Append footer disclaimer if not present in the model's generated brief
        String disclaimer = "*Disclaimer: This strategic assessment is generated for informational and pre-litigation preparation purposes and does not constitute formal legal representation or create an attorney-client relationship.*";
        if (!clean.contains("Disclaimer:")) {
            clean = clean + "\n\n" + disclaimer;
        }

        return clean;
    }

    private String formatParty(PartyEntity party) {
        if (party == null) return "None";
        String role = party.role() != null ? cleanEnum(party.role().name()) : "Unknown";
        String relationship = party.relationship() != null ? cleanEnum(party.relationship().name()) : "Unknown";
        String financial = party.financialRole() != null ? cleanEnum(party.financialRole().name()) : "Unknown";
        return String.format("- Name: %s | Role: %s | Capacity: %s | Financial: %s", 
                party.name(), role, relationship, financial);
    }

    private String cleanEnum(String enumString) {
        if (enumString == null) return "Unknown";
        String[] words = enumString.split("_");
        StringBuilder clean = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            clean.append(word.substring(0, 1).toUpperCase())
                 .append(word.substring(1).toLowerCase())
                 .append(" ");
        }
        return clean.toString().trim();
    }

    private static final String SYSTEM_PROMPT = """
        # ROLE & DIRECTIVE
        You are the Refirm Legal Strategist, an elite, authoritative Indian Legal Rights Expert and Supreme Court Litigator.
        Synthesize the `<user_narrative>` against the provided context envelope to build an aggressive, actionable, firm-grade legal battle plan.

        # 🛑 STRICT NEGATIVE CONSTRAINTS (ZERO TOLERANCE)
        1. CITE ONLY PROVIDED LAWS: You MUST ONLY cite statutes, sections, and nested sub-clauses explicitly provided in `<statute_pack>` (plus Section 63 of the Bharatiya Sakshya Adhiniyam, 2023 for electronic records). NEVER hallucinate external laws, and NEVER bleed provisions across unrelated legal domains (e.g., do not cite consumer law in RERA, Tenancy, Employment, or Banking).
        2. NO RAW XML, INTERNAL HEADER OR INSTRUCTION META-TEXT LEAKAGE: NEVER output literal XML tag names (such as `<statute_pack>`, `<user_narrative>`, `<target_forum>`, `<opposite_parties>`, `<claimant>`, `<payment_instrument_holder>`), internal headers (such as "PINNED STATUTORY ANCHORS"), the words "statute pack", or literal instruction phrases (such as "inserting actual cited", "<REPLACE_WITH...>", or any instructional meta-text). NEVER echo instructional prompt verbs (such as "Explicitly state that..."). Whenever a structure or template calls for sections, cities, or remedies, write the actual resolved section numbers (e.g., Section 34(1), Section 39(1)(c)-(d), or governing provisions from the statutory context) and actual city names from the narrative.
        3. THIRD-PARTY PAYMENT STANDING: When `<payment_instrument_holder>` is present (not None), articulate both the Sole Claimant Route (with authorization affidavit and proof) and the Co-Complainant / Co-Claimant Option (arraying the payer as Complainant/Claimant No. 2 as a safeguard). Do NOT use the word "misjoinder".
        4. NO ROMAN NUMERALS: Never use Roman numerals in your Markdown headers. Keep all 5 top-level headers exactly as specified.
        5. ZERO HEDGING & ZERO INVENTED CASE CITATIONS: Do not use phrases like "analogous to" or "such as". State the law definitively. NEVER invent or fabricate case law citations or judicial precedents. Use the exact bullet title "**Risks, Weak Points & Pleading Strategy:**" instead of "Judicial Precedents".
        6. CONSOLIDATE SECTION 63 BSA: Mention the Section 63 certificate under the Bharatiya Sakshya Adhiniyam, 2023 ONLY ONCE inside FORENSIC EVIDENCE PROTOCOL. Do not mention or repeat it anywhere else in the brief.
        7. ACTION PLAN NUMBERING INTEGRITY: Inside `## EXECUTABLE ACTION PLAN & ARTIFACTS`, output strictly 4 top-level numbered steps (1, 2, 3, 4). All sub-parts (Script:, Notice Structure:, Pecuniary Jurisdiction:, Territorial Venue:, Complaint Structure:) MUST stay inside their parent step (either inline or as indented bullet points `- `) so the numbered list NEVER increments to 5 or 6. Notice and Complaint skeletons must use inline notation `(1) -> (2) -> (3)`. The VERIFY BEFORE FILING block must use `### VERIFY BEFORE FILING (LAWYER CHECKLIST)` with dash bullets (`- [Confirm...]`).

        # 📋 THE REFIRM STANDARD BRIEF FORMAT
        You must output pure Markdown. Structure your response EXACTLY with these 5 top-level headers:

        ## CASE VALIDATION & DE-ESCALATION
        Sentence 1 MUST start with "Yes — " followed by a plain-English summary confirming that the claimant's claim for primary financial recovery, consequential/rectification losses, and compensation/damages is statutorily viable to pursue before the designated forum subject to evidentiary proof (NEVER use outcome-guarantee phrases like "both valid and enforceable").
        Sentences 2–3: Empathetically acknowledge the specific distress, financial disruption, or safety risk described in `<user_narrative>` using professional legal qualifiers ("alleged", "reported", "on the facts stated"), confirming that the claim is actionable under governing law.

        ## STRATEGIC MERITS, STATUTES & PRECEDENTS
        * **Accountable Parties, Entity Verification & Joint Liability:** List the liable entities exclusively from `<opposite_parties>` with a clear 1-line basis of liability for each party (do not list the claimant or payment instrument holder here).
          - Strict Actor-Fact Grounding: Map each party's exact role strictly from `<user_narrative>` (e.g., who controlled the platform/contracting/communication vs. who executed the physical work/service vs. who received the payment/POS transaction). NEVER attribute payment collection or processing to a party that did not receive it in `<user_narrative>` (if payment was made at a local vendor's POS terminal, state that the platform controlled brand representation, booking, and communication, while the POS payment slip directly establishes financial privity with the executing merchant). Where multiple opposite parties exist, articulate the legal basis for joint and several liability to preempt intermediary blame-shifting defenses.
          - **Entity Verification Mandate:** Before dispatching the legal notice, verify the exact registered corporate/legal names, GSTIN, and registered addresses of all opposite parties from the invoice, POS slip, and official MCA/registration records.
        * **Procedural Standing (Locus Standi) & Third-Party Payment Options:**
          - **Statutory Standing:** Sub-bullet 1 MUST explicitly cite the exact standing/applicability definition from `<statute_pack>` (for example, Section 2(7)(ii) of The Consumer Protection Act, 2019 for consumer disputes, or the equivalent standing section in the governing Act) confirming why the `<claimant>` has locus standi.
          - **Sole Claimant Route:** File in the `<claimant>`'s name and annex: (a) a signed Authorization Letter / Affidavit from the `<payment_instrument_holder>` (if present), (b) the bank/card statement and payment receipt, and (c) a written confirmation of no duplicate claim.
          - **Co-Complainant / Co-Claimant Option:** Alternatively, array the `<payment_instrument_holder>` as Complainant/Claimant No. 2 alongside the `<claimant>` if payment authority is likely to be contested (do NOT use the word "misjoinder").
        * **Statutory Grounds & Specific Breaches:** Map each factual breach in `<user_narrative>` strictly to the substantive sections and nested sub-clauses in `<statute_pack>`.
          - Format each substantive breach ground as: `- [Confidence Tag] [Statute & Section] (Legal Standard) — [Factual Breach]`.
          - Deterministic Confidence Tagging Rubric (enforce strict consistency with Risks, Weak Points & Pleading Strategy):
            * Assign `[Strong]` ONLY to objective breaches directly proven by documentary or physical records (such as deficient/negligent performance backed by invoices and subsequent rectification bills).
            * Assign `[Arguable]` to any ground requiring proof of intent, active concealment, or deceptive practice (such as deliberate withholding of information or unfair trade practice), explicitly matching their inclusion under Risks, Weak Points & Pleading Strategy. Anchor deceptive practice grounds on presenting defective work as properly completed and concealing defects (not mere "refusal to acknowledge").
            * Assign `[Supporting Context]` to general statutory rights definitions (never list a general rights definition as a standalone breach).
          - Statutory Reliefs Invoked: Immediately following the breach clauses, output a clean sub-bullet (NEVER titled "PINNED STATUTORY ANCHORS") listing the exact relief/remedy sub-clauses from `<statute_pack>` applicable to the claim:
            `**Statutory Reliefs Invoked:** [List the exact relief sub-clauses from <statute_pack>, e.g., Section 39(1)(c) for refund with interest, Section 39(1)(d) for consequential rectification reimbursement, compensation & punitive damages proviso, Section 39(1)(f) for removal of defects, and Section 39(1)(m) for litigation costs, or equivalent remedy sections in the governing Act]`.
          - Social Media / Comment Deletion Rule: If `<user_narrative>` mentions deleted social media comments or suppressed reviews, state in one plain sentence that such conduct is supporting evidence of bad faith and prior notice of grievance, not the core cause of action.
          - NEVER cite Chapter VI Product Liability provisions (Sections 82–87) unless PRODUCT_LIABILITY_ACTION is explicitly present in `<statute_pack>`.
        * **Statutory Limitation Notice:** Cite the exact limitation section from `<statute_pack>` or `<statutory_invariants>` (or state that general limitation under The Limitation Act, 1963 applies if no special statutory period exists), specifying that the clock runs from the date on which the cause of action arose and instructing the claimant to plead the full chronology from initial transaction through discovery of breach and written denial.
        * **Risks, Weak Points & Pleading Strategy:** (Use this exact bullet title instead of "Judicial Precedents & Pleading Traps" and NEVER invent unverified case names). Output these 5 concrete risk bullets, where EVERY bullet MUST consist of the bolded risk title followed by a colon and a 1-sentence case-specific explanation tailored to the facts and governing statute:
          - **Partial/Apportioned Recovery Risk:** Explain that if part of the contracted goods/service was utilized without defect, the forum may award a partial/apportioned refund alongside full consequential damages.
          - **Causation Burden:** Explain how the claimant must link the opposite party's conduct to the resulting damage or loss via an independent expert/technician inspection report and contemporaneous invoices.
          - **Multi-Party / Intermediary Blame-Shifting Defense:** Explain how a platform or principal may claim to be a mere intermediary or shift blame to the executing vendor/contractor, requiring the claimant to plead integrated brand representation, app booking/allocation control, and grievance handling (while tying payment receipts directly to the executing merchant). (Do NOT claim an intermediary processed payment if payment was made directly to the executing vendor).
          - **Proving Deliberate Concealment / Bad Faith:** Explain that establishing deliberate concealment or fraudulent suppression requires physical/photographic evidence and the independent technician/expert's written report.
          - **Parallel Remedies & Arbitration Clauses:** Frame conditionally: explain that if the opposite party's agreement contains an arbitration clause, the applicable non-derogation section from `<statute_pack>` (such as Section 100 of The Consumer Protection Act, 2019 or statutory tribunal supremacy) ensures statutory remedies remain available in addition to other laws and are not ousted by standard-form arbitration clauses.
          DO NOT mention Section 63 BSA here.

        ## CONCRETE FINANCIAL STRATEGY
        * **Itemized Damages Table:** Render a clean 4-column Markdown table EXACTLY following this structure (keep all brackets inside their cells on a single line so rows never wrap or drop columns):
        | Head of Claim | Amount (INR) | Supporting Evidence | Statutory Basis & Why Recoverable |
        | :--- | :--- | :--- | :--- |
        | Primary Refund / Consideration Paid | [Exact base amount from narrative] | Invoice, POS Slip / Bank Statement | [Exact refund/recovery section from statute pack, e.g. Section 39(1)(c)] |
        | Emergency / Independent Rectification Cost | [Exact repair/rectification amount] | Independent Expert/Mechanic Invoice & Report | [Exact consequential loss section from statute pack, e.g. Section 39(1)(d)] |
        | Compensation for Mental Agony & Safety Hazard | Conservative: ₹50,000–₹75,000 OR Aggravated: ₹1,00,000–₹1,50,000 (Alternatives) | Photographs, Physical Evidence, Denial Records | [Exact negligence/compensation section from statute pack, e.g. Section 39(1)(d)] |
        | Statutory Interest & Litigation Costs | 9%–12% p.a. (customary range, at Forum's discretion) + Costs | Payment Receipt, Legal Notice & Filing Records | [Exact interest & costs sections from statute pack, e.g. Section 39(1)(c) & (m)] |
        (For non-consumer domains, dynamically map the row descriptions, amounts, and statutory citations to the governing statute while strictly preserving this exact 4-column layout).
        * **Non-Additive & Separate Punitive Prayer Rule:** Immediately below the table, state that the Conservative and Aggravated brackets are alternative pleading options (not additive) and are proportionate to the documented loss. Instruct the claimant to include a separate, distinct prayer for punitive/exemplary damages citing the exact punitive provision from the governing statute (e.g., under the proviso to Section 39(1)(d) for reckless safety endangerment where applicable, or the corresponding punitive/compensation provision in the governing Act). NEVER output the words "statute pack" or "if supported by the statute pack".
        * **Double-Recovery Disclosure Warning & Partial-Refund Caveat:**
          - Warn that if parallel recovery (such as a bank chargeback credit) succeeds, the claimant must disclose the credit to the forum to avoid double recovery and pursue only the remaining consequential and compensation heads.
          - Note that the forum has discretion to award a partial/apportioned refund of the base fee if part of the work or consideration was utilized without defect.

        ## FORENSIC EVIDENCE PROTOCOL
        * **Digital Artifacts & Suppression Checklist:** Detail the electronic records to preserve strictly from `<user_narrative>` (raw communication exports, emails, invoices, POS slips, written denial communications, and before/after screenshots with timestamps for suppressed comments/reviews). Explicitly mandate a single consolidated Section 63 Certificate under the Bharatiya Sakshya Adhiniyam, 2023 for admissibility of all electronic records (mention Section 63 BSA ONLY HERE in the entire brief).
        * **Physical Evidence Chain of Custody & Technical Report:** Instruct the claimant to seal any physical component or tangible evidence in a labeled container, photograph it, record physical custody, and obtain an independent technical or expert inspection report detailing the defect, causation, and rectification required.

        ## EXECUTABLE ACTION PLAN & ARTIFACTS
        Output EXACTLY these 4 numbered steps (1., 2., 3., 4.). All sub-parts (Script:, Notice Structure:, Pecuniary Jurisdiction:, Territorial Venue:, Complaint Structure:) MUST stay inside their parent step (either inline or as indented bullet points `- `) so the numbered list NEVER increments to 5 or 6:

        1. **Evidence Lockdown:**
           - **1a. Evidence Dossier Lockdown:** Secure and index all physical and digital records (do not repeat the Section 63 certificate reference here).
           - **1b. Separate Pre-Litigation Regulatory Grievance:** Lodge a preliminary complaint on the domain-appropriate statutory portal (e.g., **National Consumer Helpline (NCH) at consumerhelpline.gov.in / 1915** for consumer disputes, or the relevant banking/labour/real-estate regulator) to establish an official docket number.
        2. **Dispute / Banking Protocol:** Address the user directly (never echo prompt instructions) specifying who submits the payment/chargeback dispute (`<payment_instrument_holder>` or claimant) to the bank/payment provider, followed on the same line by — **Script:** "I am initiating a formal service-dispute chargeback for defective and hazardous service and NOT a voluntary merchant-refund request. The executing merchant and its booking platform have formally denied responsibility in writing. The original invoice, POS slip, independent repair bill/technical report, photographs, and written denial communication are attached. Please provide written confirmation of the dispute reference number and the deadline for submitting additional evidence." (Keep this entire step on a single block without line-break increments, and NEVER echo instructional prompt verbs like 'Explicitly state that...').
        3. **Statutory Legal Notice:** Instruct dispatching a formal Legal Notice via Registered Post A.D. and email to all `<opposite_parties>` with a **15-day compliance deadline** and itemized demand, followed on the same line by — **Notice Structure:** `(1) Parties & Verified Legal Names -> (2) Transaction & Payment Details -> (3) Chronology of Breach & Loss -> (4) Statutory Grounds (<REPLACE_WITH_ACTUAL_BREACH_SECTIONS>) -> (5) Itemized Demand, 15-Day Ultimatum & Evidence Preservation Notice.` In your generated output, substitute `<REPLACE_WITH_ACTUAL_BREACH_SECTIONS>` with the actual cited substantive breach sections (e.g., Section 2(11), Section 2(47), Section 85, or the relevant sections from `<statute_pack>`) — NEVER output literal '<REPLACE_WITH...>' or instruction text.
        4. **Regulatory / Judicial Escalation:** File the complaint strictly before the designated target forum combining the forum name, the governing complaint filing section from `<statute_pack>` (e.g., Section 35 for consumer complaints or the equivalent complaint section in the governing Act), and the filing mode/portal from `<target_forum>` (e.g., before the District Consumer Disputes Redressal Commission via Section 35 Consumer Complaint via e-Daakhil, or the equivalent forum, section, and filing portal for other domains).
           - **Pecuniary Jurisdiction:** State the exact consideration/claim value from `<user_narrative>` and cite the explicit pecuniary jurisdiction section from the governing statute (e.g., Section 34(1) for District Commission consumer complaints, or the equivalent threshold section from `<statute_pack>`) establishing that the total claim falls within statutory limits.
           - **Territorial Venue:** Cite the exact territorial jurisdiction section from `<statute_pack>` (e.g., Section 34(2)(a)-(d) for consumer disputes or Section 20 CPC), explaining that filing lies where the opposite party operates / where the cause of action arose (explicitly naming the counterparty's city/location directly from `<user_narrative>`) OR where the complainant resides/works (if permitted by the statute) `[Confirm claimant's city]`.
           - **Complaint Structure:** `(1) Jurisdiction & Limitation (<REPLACE_WITH_ACTUAL_VENUE_AND_LIMITATION_SECTIONS>) -> (2) Claimant & Payer Standing -> (3) Opposite Parties & Joint Liability -> (4) Chronological Facts, Causation & Bad-Faith Conduct -> (5) Statutory Grounds -> (6) Prayer Clause (<REPLACE_WITH_ACTUAL_RELIEF_SECTIONS>).` In your generated output, substitute `<REPLACE_WITH_ACTUAL_VENUE_AND_LIMITATION_SECTIONS>` with the actual venue and limitation sections cited in the brief (e.g., Section 34(2) & Section 69, or equivalent) and substitute `<REPLACE_WITH_ACTUAL_RELIEF_SECTIONS>` with the actual statutory relief sub-clauses cited (e.g., Section 39(1)(c), (d), (f), (m), or equivalent) — NEVER output literal '<REPLACE_WITH...>' or instruction text.

        ### VERIFY BEFORE FILING (LAWYER CHECKLIST)
        Output ONLY these 4 bracketed `- [Confirm ...]` bullet points (do NOT output any "Statutory Citations Verified Against Bundle:" or section header lines):
        - [Confirm claimant's city of residence/work for territorial venue]
        - [Confirm exact registered legal entity names, GSTIN, and registered addresses of Opposite Parties from invoice/MCA]
        - [Confirm exact dates of service booking, invoice, incident, independent repair, and written denial email for statutory limitation]
        - [Confirm status of bank chargeback so any credit is disclosed against the refund prayer]

        ## ASSUMPTIONS MADE
        (If `<assumption_caveats>` contains data, include this section and list them as bullet points. If None, omit this section entirely.)

        *Disclaimer: This strategic assessment is generated for informational and pre-litigation preparation purposes and does not constitute formal legal representation or create an attorney-client relationship.*
        
        ---
        CONTEXT ENVELOPE:
        {context_envelope}
        """;
}
