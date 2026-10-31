package com.app.refirm.workspace;

import com.app.refirm.workspace.domain.ExtractedPage;
import com.app.refirm.workspace.ingestion.OcrQualityGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OcrQualityGateTest {

    private final OcrQualityGate qualityGate = new OcrQualityGate();

    @Test
    @DisplayName("Legible multi-page document passes quality gate")
    void legibleMultiPageDocumentPasses() {
        List<ExtractedPage> pages = List.of(
                new ExtractedPage(1, "IN THE HIGH COURT OF DELHI AT NEW DELHI\nCIVIL WRIT PETITION NO. 4501 OF 2023\nIN THE MATTER OF: ABC CORP VS XYZ LTD"),
                new ExtractedPage(2, "The petitioner claims compensation of INR 50,00,000 for breach of commercial contract dated 12th January 2022.")
        );

        OcrQualityGate.QualityCheckResult result = qualityGate.evaluate(pages);

        assertThat(result.passed()).isTrue();
        assertThat(result.failureReason()).isNull();
        assertThat(result.sanitizedPages()).hasSize(2);
        assertThat(result.sanitizedPages().get(0).pageNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Empty or null page list is rejected")
    void emptyOrNullPageListIsRejected() {
        OcrQualityGate.QualityCheckResult nullResult = qualityGate.evaluate(null);
        assertThat(nullResult.passed()).isFalse();
        assertThat(nullResult.failureReason()).contains("No pages extracted");

        OcrQualityGate.QualityCheckResult emptyResult = qualityGate.evaluate(Collections.emptyList());
        assertThat(emptyResult.passed()).isFalse();
        assertThat(emptyResult.failureReason()).contains("No pages extracted");
    }

    @Test
    @DisplayName("Document with < 40 alphanumeric characters is rejected")
    void shortTextIsRejected() {
        List<ExtractedPage> pages = List.of(
                new ExtractedPage(1, "Short scan ok")
        );

        OcrQualityGate.QualityCheckResult result = qualityGate.evaluate(pages);

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).contains("insufficient text");
    }

    @Test
    @DisplayName("Corrupt scan with excessive non-alphanumeric noise (< 0.35 ratio) is rejected")
    void symbolHeavyGibberishIsRejected() {
        // Mostly printable symbols: $$$$%%%%####@@@@@@!!!!!!! with very few alphanumeric characters
        String gibberish = "ABC $$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$ "
                + "&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&& "
                + "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! "
                + "1234567890 1234567890 1234567890 1234567890"; // Enough chars total, but overwhelming symbols

        List<ExtractedPage> pages = List.of(new ExtractedPage(1, gibberish));

        OcrQualityGate.QualityCheckResult result = qualityGate.evaluate(pages);

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReason()).contains("quality is too low");
    }
}
