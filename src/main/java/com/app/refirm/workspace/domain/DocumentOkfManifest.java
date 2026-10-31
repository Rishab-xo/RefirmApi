package com.app.refirm.workspace.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lightweight per-PDF OKF manifest (~150 tokens) indexing critical document metadata,
 * entities, dates, and pinpoint page coordinates.
 *
 * @param executiveGist   Compact 2-line executive summary of the document's substance
 * @param keyEntities     Names of parties, merchants, banks, or entities appearing in the document
 * @param importantDates  Crucial dates (e.g. invoice date, incident date, legal notice date)
 * @param monetaryAmounts Key financial figures appearing in the document
 * @param topicPagePins   Mapping from topic/clause/evidence keys to 1-based page numbers
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentOkfManifest(
        String executiveGist,
        List<String> keyEntities,
        List<String> importantDates,
        List<String> monetaryAmounts,
        Map<String, List<Integer>> topicPagePins
) {
    public DocumentOkfManifest {
        executiveGist = executiveGist != null ? executiveGist : "";
        keyEntities = keyEntities != null ? List.copyOf(keyEntities) : List.of();
        importantDates = importantDates != null ? List.copyOf(importantDates) : List.of();
        monetaryAmounts = monetaryAmounts != null ? List.copyOf(monetaryAmounts) : List.of();
        topicPagePins = topicPagePins != null ? Collections.unmodifiableMap(topicPagePins) : Collections.emptyMap();
    }

    public static DocumentOkfManifest empty() {
        return new DocumentOkfManifest("", List.of(), List.of(), List.of(), Map.of());
    }
}
