package com.app.refirm.triage.service;

import com.app.refirm.triage.entities.LegalCategory;

import java.util.*;

/**
 * Static registry of required and optional facts for each {@link LegalCategory}.
 * <p>
 * The intake prompt is dynamically enriched with these lists once the AI
 * has classified the issue, ensuring category-specific questions are asked
 * before the session can be marked complete.
 * <p>
 * Keys use snake_case identifiers that the AI returns in its
 * {@code factChecklist.gatheredFacts} map. Display labels are used
 * in the prompt and in the frontend progress panel.
 */
public final class CategoryFactRequirements {

    private CategoryFactRequirements() { /* utility class */ }

    // ──────────────────────────────────────────────
    //  REQUIRED FACTS — must be gathered (or explicitly "UNKNOWN") before completion
    // ──────────────────────────────────────────────

    private static final Map<LegalCategory, LinkedHashMap<String, String>> REQUIRED_FACTS = Map.ofEntries(

            Map.entry(LegalCategory.CRIMINAL_LAW, orderedMap(
                    "nature_of_offence", "Nature of offence (e.g., theft, assault, cheating)",
                    "date_of_incident", "Date or approximate timeframe of the incident",
                    "victim_details", "Victim / complainant's name and relationship",
                    "accused_details", "Accused person's name, relationship, and any known details",
                    "incident_location", "Location where the incident occurred (city, area)",
                    "fir_status", "Whether an FIR has been filed (Yes/No) and at which police station",
                    "police_station", "Name of the police station involved",
                    "current_status", "Current status (arrested, on bail, absconding, etc.)"
            )),

            Map.entry(LegalCategory.FAMILY_MATRIMONIAL, orderedMap(
                    "nature_of_dispute", "Nature of dispute (divorce, custody, domestic violence, maintenance, dowry)",
                    "marriage_date", "Date of marriage (or approximate year)",
                    "parties_involved", "Names of husband and wife / parties involved",
                    "children_details", "Whether children are involved (Yes/No), ages if applicable",
                    "current_living", "Current living arrangement (together, separated, at parental home)",
                    "violence_or_threats", "Whether physical violence or threats are involved",
                    "prior_legal_action", "Any previous legal actions (police complaint, protection order, etc.)",
                    "income_details", "Approximate income of both parties (for maintenance/alimony context)"
            )),

            Map.entry(LegalCategory.CIVIL_PROPERTY, orderedMap(
                    "nature_of_dispute", "Nature of property dispute (tenancy/rent, eviction, lockout, title, partition, encroachment)",
                    "property_location", "Property address / location (city, area, state)",
                    "parties_involved", "Parties involved and roles (e.g., tenant & landlord, buyer & seller, co-owners, neighbours)",
                    "possession_status", "Current possession / occupancy status (who is staying there, who holds keys, locked out)",
                    "relevant_documents", "Relevant documents available (rent/lease agreement, receipts, sale deed, title, or notice)",
                    "financial_aspect", "Financial details (monthly rent, security deposit amount, property value, or damages claimed)",
                    "dispute_timeline", "When the dispute started and key sequence of events"
            )),

            Map.entry(LegalCategory.LABOUR_EMPLOYMENT, orderedMap(
                    "nature_of_issue", "Nature of issue (wrongful termination, unpaid wages, harassment, PF/ESI)",
                    "employer_details", "Employer name and type of organization",
                    "employment_duration", "Employment duration (start date to end date or current)",
                    "designation_role", "Designation / role of the employee",
                    "salary_details", "Salary amount and payment mode (monthly, daily wage)",
                    "last_working_day", "Last working day (if terminated/resigned)",
                    "grievance_raised", "Whether any internal complaint or grievance was filed",
                    "termination_letter", "Whether a termination/warning letter was received (Yes/No)"
            )),

            Map.entry(LegalCategory.CONSUMER_GRIEVANCE, orderedMap(
                    "product_or_service", "Product or service involved",
                    "seller_provider", "Seller / service provider name",
                    "purchase_date", "Date of purchase or service agreement",
                    "amount_paid", "Amount paid",
                    "nature_of_defect", "Nature of defect or issue faced",
                    "complaint_to_seller", "Whether a complaint was made to the seller (Yes/No, response received)",
                    "invoice_receipt", "Whether invoice / receipt / warranty card is available"
            )),

            Map.entry(LegalCategory.CORPORATE_COMMERCIAL, orderedMap(
                    "nature_of_dispute", "Nature of dispute (contract breach, partnership, insolvency, arbitration)",
                    "parties_involved", "Parties involved (company names, individuals, designations)",
                    "agreement_details", "Whether a written agreement/contract exists (Yes/No, type)",
                    "breach_specifics", "Specific breach or issue (what was violated, when)",
                    "disputed_amount", "Disputed amount or financial impact",
                    "arbitration_clause", "Whether the agreement has an arbitration clause",
                    "company_registration", "Whether the company is registered (type: Pvt Ltd, LLP, Partnership)"
            )),

            Map.entry(LegalCategory.TAX_CUSTOMS, orderedMap(
                    "tax_type", "Type of tax (Income Tax, GST, Customs Duty, Property Tax)",
                    "assessment_year", "Assessment year or period in question",
                    "notice_received", "Whether any notice or order has been received (Yes/No)",
                    "issuing_authority", "Authority that issued the notice (IT Department, GST Commissioner, etc.)",
                    "disputed_amount", "Disputed tax amount or penalty",
                    "due_dates", "Relevant due dates or deadlines",
                    "ca_consultation", "Whether a Chartered Accountant has been consulted"
            )),

            Map.entry(LegalCategory.INTELLECTUAL_PROPERTY, orderedMap(
                    "ip_type", "Type of IP (Trademark, Copyright, Patent, Domain dispute)",
                    "registration_status", "Whether the IP is registered (Yes/No, registration number if available)",
                    "infringement_details", "Details of infringement (what is being copied/violated, by whom)",
                    "opposing_party", "Opposing party's name and details",
                    "prior_use_dates", "First use or creation date of the IP",
                    "commercial_impact", "Commercial impact of the infringement",
                    "evidence_available", "What evidence is available (screenshots, documents, receipts)"
            )),

            Map.entry(LegalCategory.CONSTITUTIONAL_WRIT, orderedMap(
                    "right_violated", "Which fundamental right is alleged to be violated",
                    "government_authority", "Government authority or body involved",
                    "action_challenged", "Specific action, order, or decision being challenged",
                    "date_of_action", "Date of the action or order",
                    "impact_description", "How the action impacts the petitioner",
                    "representations_made", "Any prior representations or complaints made to authorities"
            ))
    );

    // ──────────────────────────────────────────────
    //  OPTIONAL FACTS — helpful for a better brief but not required for completion
    // ──────────────────────────────────────────────

    private static final Map<LegalCategory, LinkedHashMap<String, String>> OPTIONAL_FACTS = Map.ofEntries(

            Map.entry(LegalCategory.CRIMINAL_LAW, orderedMap(
                    "fir_number", "FIR number (if filed)",
                    "bail_status", "Bail status and any bail conditions",
                    "prior_criminal_history", "Prior criminal history of accused",
                    "witness_details", "Names or details of any witnesses",
                    "evidence_available", "Evidence available (CCTV, documents, medical reports)"
            )),

            Map.entry(LegalCategory.FAMILY_MATRIMONIAL, orderedMap(
                    "mutual_or_contested", "Whether divorce is mutual consent or contested",
                    "property_involved", "Whether shared property or assets are involved",
                    "stridhan_details", "Stridhan or dowry items details",
                    "counselling_attempted", "Whether marital counselling was attempted"
            )),

            Map.entry(LegalCategory.CIVIL_PROPERTY, orderedMap(
                    "registration_details", "Property registration number / details",
                    "prior_litigation", "Any prior litigation on this property",
                    "survey_numbers", "Survey numbers or plot details",
                    "encumbrance_certificate", "Whether encumbrance certificate is available"
            )),

            Map.entry(LegalCategory.LABOUR_EMPLOYMENT, orderedMap(
                    "employment_contract", "Whether a written employment contract exists",
                    "pf_esi_deductions", "PF/ESI deduction details",
                    "notice_period", "Notice period compliance details",
                    "union_membership", "Whether employee is a union member"
            )),

            Map.entry(LegalCategory.CONSUMER_GRIEVANCE, orderedMap(
                    "warranty_status", "Warranty status and period",
                    "online_offline", "Whether purchase was online or offline",
                    "consumer_forum_filed", "Whether consumer forum complaint has been filed",
                    "replacement_offered", "Whether replacement/refund was offered"
            )),

            Map.entry(LegalCategory.CORPORATE_COMMERCIAL, orderedMap(
                    "outstanding_amounts", "Outstanding amounts or dues",
                    "timeline_of_events", "Detailed timeline of events leading to dispute",
                    "board_resolutions", "Relevant board resolutions or meeting minutes",
                    "roc_filings", "ROC filing status"
            )),

            Map.entry(LegalCategory.TAX_CUSTOMS, orderedMap(
                    "previous_appeals", "Previous appeals filed",
                    "penalty_details", "Penalty details and amount",
                    "tax_returns_filed", "Whether tax returns were filed on time",
                    "seized_assets", "Whether any assets were seized or attached"
            )),

            Map.entry(LegalCategory.INTELLECTUAL_PROPERTY, orderedMap(
                    "registration_number", "Registration / application number",
                    "licensing_agreements", "Any existing licensing agreements",
                    "cease_desist_sent", "Whether cease and desist notice was sent",
                    "international_filings", "Whether international filings exist (Madrid, PCT)"
            )),

            Map.entry(LegalCategory.CONSTITUTIONAL_WRIT, orderedMap(
                    "similar_precedents", "Known similar precedents or cases",
                    "urgency_factors", "Urgency factors requiring immediate relief",
                    "public_interest", "Whether this is a matter of public interest",
                    "media_coverage", "Any media coverage or public attention"
            ))
    );

    // ──────────────────────────────────────────────
    //  PUBLIC API
    // ──────────────────────────────────────────────

    /**
     * Returns the ordered map of required facts for the given category.
     * Falls back to an empty map for UNKNOWN / NEEDS_HUMAN_TRIAGE.
     */
    public static LinkedHashMap<String, String> getRequiredFacts(LegalCategory category) {
        return REQUIRED_FACTS.getOrDefault(category, new LinkedHashMap<>());
    }

    /**
     * Returns the ordered map of optional facts for the given category.
     */
    public static LinkedHashMap<String, String> getOptionalFacts(LegalCategory category) {
        return OPTIONAL_FACTS.getOrDefault(category, new LinkedHashMap<>());
    }

    /**
     * Returns a formatted bullet list of required facts for prompt injection.
     * Example: "- nature_of_offence: Nature of offence (e.g., theft, assault, cheating)"
     */
    public static String formatRequiredFactsForPrompt(LegalCategory category) {
        LinkedHashMap<String, String> facts = getRequiredFacts(category);
        if (facts.isEmpty()) return "No specific checklist — gather Who, What, When, Where.";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Returns a formatted bullet list of optional facts for prompt injection.
     */
    public static String formatOptionalFactsForPrompt(LegalCategory category) {
        LinkedHashMap<String, String> facts = getOptionalFacts(category);
        if (facts.isEmpty()) return "None.";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Returns the list of required fact keys for the given category.
     */
    public static List<String> getRequiredFactKeys(LegalCategory category) {
        return new ArrayList<>(getRequiredFacts(category).keySet());
    }

    /**
     * Formats all legal categories with their required fact keys for turn-1 zero-shot prompt injection.
     */
    public static String formatAllCategoriesForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<LegalCategory, LinkedHashMap<String, String>> entry : REQUIRED_FACTS.entrySet()) {
            sb.append("### ").append(entry.getKey().name()).append(" (").append(entry.getKey().getDisplayLabel()).append("):\n");
            for (Map.Entry<String, String> fact : entry.getValue().entrySet()) {
                sb.append("  - ").append(fact.getKey()).append(": ").append(fact.getValue()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ──────────────────────────────────────────────
    //  PRIVATE HELPERS
    // ──────────────────────────────────────────────

    /**
     * Creates a LinkedHashMap preserving insertion order from key-value pairs.
     * Usage: orderedMap("k1", "v1", "k2", "v2", ...)
     */
    private static LinkedHashMap<String, String> orderedMap(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Must provide even number of arguments (key-value pairs)");
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
