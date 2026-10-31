package com.app.refirm.workspace.domain;

import java.util.UUID;

/**
 * Citation linking assistant statements back to the exact document and page number.
 */
public record PinnedPageCitation(
        UUID documentId,
        String fileName,
        int pageNumber,
        String matchedTopic
) {
    public PinnedPageCitation {
        fileName = fileName != null ? fileName : "";
        matchedTopic = matchedTopic != null ? matchedTopic : "";
    }
}
