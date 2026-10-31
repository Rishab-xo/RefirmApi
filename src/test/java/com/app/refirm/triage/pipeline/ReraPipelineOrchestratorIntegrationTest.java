package com.app.refirm.triage.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ReraPipelineOrchestratorIntegrationTest {

    @Autowired
    private TriagePipelineOrchestrator orchestrator;

    @Test
    @DisplayName("End-to-End Pipeline: Real Estate Developer Delay cleanly drafts without Consumer Law bleed")
    void shouldProcessRealEstateDelayWithoutDomainBleed() {
        String narrative = """
            In March 2021, I booked a 3BHK flat in 'Elysium Heights', Sector 65, Gurugram developed by Apex Skyline Promoters.
            As per the Builder-Buyer Agreement (BBA), the promised date of physical possession with occupancy certificate was June 30, 2024,
            with a 6-month grace period ending December 31, 2024.
            
            I have made timely installment payments totaling Rs. 45,00,000 via RTGS against demand letters.
            The developer has halted construction at 60% completion since late 2023.
            Recently, they issued an arbitrary demand notice demanding an additional Rs. 6,00,000 for raw material price escalation,
            while failing to commit to a revised possession date.
            When I demanded a refund of my deposited amount with interest, their customer care team refused and threatened forfeiture of my booking deposit.
            """;

        String userLocation = "Gurugram";

        String brief = orchestrator.processCase(narrative, userLocation);

        assertThat(brief).isNotNull().isNotBlank();

        // 1. Verify Forum Isolation
        assertThat(brief).doesNotContainIgnoringCase("District Consumer Commission");
        assertThat(brief).doesNotContainIgnoringCase("e-Daakhil");
        assertThat(brief).doesNotContainIgnoringCase("RBI Ombudsman");

        // 2. Verify Zero Consumer Law Bleed
        assertThat(brief).doesNotContainIgnoringCase("Consumer Protection Act");
        assertThat(brief).doesNotContainIgnoringCase("Section 2(11)");
        assertThat(brief).doesNotContainIgnoringCase("Section 85");

        // 3. Verify Statutory RERA Grounding
        assertThat(brief).containsAnyOf("Real Estate", "RERA");

        // 4. Verify Assumptions Made Section from Rules Engine Caveats
        assertThat(brief).contains("## ASSUMPTIONS MADE");

        // 5. Verify Markdown Action Plan List Integrity (Strictly 1, 2, 3, 4)
        assertThat(brief).contains("1. **Evidence Lockdown:**");
        assertThat(brief).contains("2. **Dispute / Banking Protocol:**");
        assertThat(brief).contains("3. **Statutory Legal Notice:**");
        assertThat(brief).contains("4. **Regulatory / Judicial Escalation:**");
        assertThat(brief).doesNotContain("\n5. ");
    }
}
