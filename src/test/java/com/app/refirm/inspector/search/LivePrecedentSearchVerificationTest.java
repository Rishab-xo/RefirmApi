package com.app.refirm.inspector.search;

import com.app.refirm.inspector.dto.InspectorDTOs.JudgmentAuthorityCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
class LivePrecedentSearchVerificationTest {

    @Autowired
    private PrecedentHybridSearchService searchService;

    @Test
    @DisplayName("Run 5 Milestone 2B Final Polish benchmark queries against live Neon endpoint")
    void run5FinalPolishBenchmarkQueries() {
        // Query 1: Exact formal citation
        runAndPrintQuery(
                "Benchmark 1: (2014) 8 SCC 273 (Exact citation routing)",
                "(2014) 8 SCC 273", 5
        );

        // Query 2: Section 438 CrPC (Statute prominence & crosswalk)
        runAndPrintQuery(
                "Benchmark 2: Section 438 CrPC (Pari Materia crosswalk & core landmark prominence)",
                "Section 438 CrPC", 5
        );

        // Query 3: Anticipatory bail custodial interrogation (Negative treatment detection & penalty)
        runAndPrintQuery(
                "Benchmark 3: anticipatory bail Section 438 CrPC custodial interrogation not required",
                "anticipatory bail Section 438 CrPC custodial interrogation not required", 5
        );

        // Query 4: Fact-pattern arrest without notice
        runAndPrintQuery(
                "Benchmark 4: police arresting husband in dowry harassment case without notice punishment under 7 years",
                "police arresting husband in dowry harassment case without notice punishment under 7 years", 5
        );

        // Query 5: Fact-pattern life insurance nominee vs legal heirs
        runAndPrintQuery(
                "Benchmark 5: can a nominee in life insurance policy claim money over legal heirs",
                "can a nominee in life insurance policy claim money over legal heirs", 5
        );
    }

    private void runAndPrintQuery(String header, String queryText, int limit) {
        System.out.println("\n=======================================================");
        System.out.println(">>> " + header);
        System.out.println("Query: \"" + queryText + "\"");
        System.out.println("-------------------------------------------------------");

        List<JudgmentAuthorityCard> results = searchService.search(
                queryText, null, null, null, null, limit, RankingProfile.SEARCH_LIBRARY, true);

        System.out.println("Total returned: " + results.size());
        for (int i = 0; i < results.size(); i++) {
            JudgmentAuthorityCard card = results.get(i);
            RankingScoreBreakdown b = card.scoreBreakdown();
            System.out.println(String.format("  #%d [%.2f pts] %s | %s (%d) | Status: %s | OverruledBy: %s",
                    (i + 1),
                    b != null ? b.finalScore() : 0.0,
                    card.caseTitle(),
                    card.primaryCitation(),
                    card.decisionYear(),
                    card.goodLawStatus(),
                    card.overruledByAuthorityId()));
            if (b != null) {
                System.out.println(String.format("     Intent: %s | Relevance: %.2f | Statute: %.2f | Authority: %.2f | Recency: %.2f | Posture: %.2f | Penalty: %.2f",
                        b.intent(), b.relevancePts(), b.statutePts(), b.authorityPts(), b.recencyPts(), b.domainPosturePts(), b.goodLawPenalty()));
                System.out.println("     Channel Ranks: " + b.channelRanks());
            }
            System.out.println("     Tuples: " + card.actSectionTuples());
            if (card.summary() != null && !card.summary().isBlank()) {
                String briefSummary = card.summary().length() > 160 ? card.summary().substring(0, 160) + "..." : card.summary();
                System.out.println("     Summary: " + briefSummary);
            }
        }
        System.out.println("=======================================================");
    }
}
