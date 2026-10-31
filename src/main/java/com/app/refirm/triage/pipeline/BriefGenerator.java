package com.app.refirm.triage.pipeline;

import com.app.refirm.triage.domain.DraftingContext;

/**
 * Step 4 Pipeline Interface: Final Brief Drafting.
 */
public interface BriefGenerator {
    /**
     * Pass 2: Generates the final strategic brief using o3-mini based STRICTLY on the assembled context.
     *
     * @param context Fully assembled drafting context
     * @return Markdown formatted legal brief
     */
    String generateDraft(DraftingContext context);

    default String generateBrief(DraftingContext context) {
        return generateDraft(context);
    }
}
