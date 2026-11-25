package com.app.refirm.inspector.controller;

import com.app.refirm.inspector.dto.InspectorDTOs.InAppJudgmentDrawerResponse;
import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.JudgmentSearchResponse;
import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.TopicDetailResponse;
import com.app.refirm.inspector.dto.JudgmentLibraryDTOs.TopicSummaryItem;
import com.app.refirm.inspector.service.AuthorityDrawerService;
import com.app.refirm.inspector.service.JudgmentLibraryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller powering the Standalone Judgments Library UI:
 * Topics directory, live NARROW BY ISSUE counts, Pari Materia statutory banners, and smart hybrid search.
 */
@RestController
public class JudgmentLibraryController {

    private final JudgmentLibraryService judgmentLibraryService;
    private final AuthorityDrawerService authorityDrawerService;

    @Autowired
    public JudgmentLibraryController(
            JudgmentLibraryService judgmentLibraryService,
            AuthorityDrawerService authorityDrawerService
    ) {
        this.judgmentLibraryService = judgmentLibraryService;
        this.authorityDrawerService = authorityDrawerService;
    }

    /**
     * GET /judgments/topics (and /api/judgments/topics, /api/v1.0/judgments/topics):
     * Returns all taxonomy topics with live total judgment counts and Supreme Court counts.
     */
    @GetMapping({"/judgments/topics", "/api/judgments/topics", "/api/v1.0/judgments/topics"})
    public ResponseEntity<List<TopicSummaryItem>> getTopics() {
        return ResponseEntity.ok(judgmentLibraryService.getTopicSummaries());
    }

    /**
     * GET /judgments/topics/{topicSlug}:
     * Returns topic metadata, issue pills with live counts, and ranked precedent cards.
     */
    @GetMapping({"/judgments/topics/{topicSlug}", "/api/judgments/topics/{topicSlug}", "/api/v1.0/judgments/topics/{topicSlug}"})
    public ResponseEntity<TopicDetailResponse> getTopicDetail(
            @PathVariable String topicSlug,
            @RequestParam(required = false) String issue,
            @RequestParam(required = false) String court,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(judgmentLibraryService.getTopicDetail(topicSlug, issue, court, q, limit));
    }

    /**
     * GET /judgments/search:
     * Parses statutory section references, expands into twin tuples, and runs 2-stage hybrid retrieval & legal reranking.
     */
    @GetMapping({"/judgments/search", "/api/judgments/search", "/api/v1.0/judgments/search"})
    public ResponseEntity<JudgmentSearchResponse> searchJudgments(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String court,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String issue,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean debug
    ) {
        return ResponseEntity.ok(judgmentLibraryService.searchJudgments(q, domain, court, topic, issue, limit, debug));
    }

    /**
     * GET /judgments/{authorityId}/drawer:
     * Returns full In-App Judgment Drawer (IRAC brief + Cloudflare R2 / Neon DrawerPage[]).
     */
    @GetMapping({"/judgments/{authorityId}/drawer", "/api/judgments/{authorityId}/drawer", "/api/v1.0/judgments/{authorityId}/drawer"})
    public ResponseEntity<InAppJudgmentDrawerResponse> getJudgmentDrawer(
            @PathVariable String authorityId
    ) {
        return ResponseEntity.ok(authorityDrawerService.getCorpusAuthorityDrawer(authorityId));
    }
}

