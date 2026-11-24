# Refirm Backend Map (ParichayApi)

```text
ParichayApi/
├── Dockerfile
├── HELP.md
├── docker-compose.yml
├── mvnw
├── mvnw.cmd
├── pom.xml
└── src/
    ├── main/
    │   ├── java/
    │   │   └── com/
    │   │       └── app/
    │   │           └── refirm/
    │   │               ├── RefirmApiApplication.java
    │   │               ├── arguments/
    │   │               │   ├── controller/
    │   │               │   │   ├── ArgumentPrepController.java
    │   │               │   │   └── dto/
    │   │               │   │       ├── AppendRebuttalRequest.java
    │   │               │   │       ├── ArgumentSheetResponse.java
    │   │               │   │       ├── GenerateArgumentSheetRequest.java
    │   │               │   │       └── UpdateSubmissionRequest.java
    │   │               │   ├── domain/
    │   │               │   │   ├── ArgumentDossier.java
    │   │               │   │   ├── ArgumentSubmission.java
    │   │               │   │   ├── BenchQuestionItem.java
    │   │               │   │   ├── FramedLegalIssue.java
    │   │               │   │   ├── RebuttalMatrixItem.java
    │   │               │   │   └── VerifiedPrecedentAuthority.java
    │   │               │   ├── entity/
    │   │               │   │   └── WorkspaceArgumentSheet.java
    │   │               │   ├── repository/
    │   │               │   │   └── WorkspaceArgumentSheetRepository.java
    │   │               │   └── service/
    │   │               │       ├── ArgumentMarkdownRenderer.java
    │   │               │       ├── ArgumentPrepService.java
    │   │               │       ├── AuthorityFirewallSanitizer.java
    │   │               │       ├── PrecedentAuthorityProvider.java
    │   │               │       └── WorkspaceUploadedPrecedentProvider.java
    │   │               ├── auth/
    │   │               │   ├── ClerkJwksProvider.java
    │   │               │   ├── ClerkJwtAuthFilter.java
    │   │               │   ├── ClerkWebhookController.java
    │   │               │   └── SecurityConfig.java
    │   │               ├── core/
    │   │               │   ├── config/
    │   │               │   │   ├── AiConfig.java
    │   │               │   │   ├── DatabaseSchemaMigrationConfig.java
    │   │               │   │   ├── JacksonConfig.java
    │   │               │   │   ├── MinioConfig.java
    │   │               │   │   └── StaticResourceConfig.java
    │   │               │   ├── controller/
    │   │               │   │   ├── AdminController.java
    │   │               │   │   ├── AiTestController.java
    │   │               │   │   └── DraftController.java
    │   │               │   ├── dto/
    │   │               │   │   ├── GenerateBriefRequest.java
    │   │               │   │   └── GenerateBriefResponse.java
    │   │               │   ├── exception/
    │   │               │   │   └── GlobalExceptionHandler.java
    │   │               │   └── service/
    │   │               │       ├── BriefGenerationService.java
    │   │               │       ├── DocumentExportService.java
    │   │               │       ├── LegalIngestionService.java
    │   │               │       └── LegalRetrievalService.java
    │   │               ├── dashboard/
    │   │               │   ├── dto/
    │   │               │   │   └── MatterDashboardDTOs.java
    │   │               │   └── service/
    │   │               │       └── MatterDashboardService.java
    │   │               ├── export/
    │   │               │   ├── ExportController.java
    │   │               │   └── PdfExportService.java
    │   │               ├── file/
    │   │               │   ├── controller/
    │   │               │   │   ├── CloudFileContainer.java
    │   │               │   │   └── FileController.java
    │   │               │   ├── dto/
    │   │               │   │   └── FileMetaDataDTO.java
    │   │               │   ├── entities/
    │   │               │   │   ├── CloudFile.java
    │   │               │   │   └── FileMetaDataDocument.java
    │   │               │   ├── repo/
    │   │               │   │   ├── CloudFileRepo.java
    │   │               │   │   └── FileMetadataRepo.java
    │   │               │   └── service/
    │   │               │       ├── CloudFileService.java
    │   │               │       └── FileMetadataService.java
    │   │               ├── inspector/
    │   │               │   ├── controller/
    │   │               │   │   └── InspectorController.java
    │   │               │   ├── domain/
    │   │               │   │   └── CorpusPrecedentEntry.java
    │   │               │   ├── dto/
    │   │               │   │   └── InspectorDTOs.java
    │   │               │   └── service/
    │   │               │       ├── AuthorityDrawerService.java
    │   │               │       ├── CorpusPrecedentRegistryService.java
    │   │               │       └── InspectorService.java
    │   │               ├── intake/
    │   │               │   ├── IntakeController.java
    │   │               │   ├── IntakeOrchestratorService.java
    │   │               │   ├── LunaFactExtractorService.java
    │   │               │   └── payload/
    │   │               │       ├── IntakeRequest.java
    │   │               │       ├── IntakeResponse.java
    │   │               │       └── IntakeStatus.java
    │   │               ├── payment/
    │   │               │   ├── controller/
    │   │               │   │   ├── PaymentController.java
    │   │               │   │   └── TransactionController.java
    │   │               │   ├── dto/
    │   │               │   │   ├── PaymentDTO.java
    │   │               │   │   └── PaymentVerificationDTO.java
    │   │               │   ├── entities/
    │   │               │   │   └── PaymentTransaction.java
    │   │               │   ├── repo/
    │   │               │   │   └── PaymentTransactionRepo.java
    │   │               │   └── service/
    │   │               │       └── PaymentService.java
    │   │               ├── profile/
    │   │               │   ├── controller/
    │   │               │   │   ├── ProfileController.java
    │   │               │   │   └── UserCreditsController.java
    │   │               │   ├── dto/
    │   │               │   │   ├── ProfileDTO.java
    │   │               │   │   └── UserCreditsDTO.java
    │   │               │   ├── entities/
    │   │               │   │   ├── ProfileDocument.java
    │   │               │   │   └── UserCredits.java
    │   │               │   ├── repo/
    │   │               │   │   ├── ProfileRepo.java
    │   │               │   │   └── UserCreditsRepo.java
    │   │               │   └── service/
    │   │               │       ├── ProfileService.java
    │   │               │       └── UserCreditsService.java
    │   │               ├── rules/
    │   │               │   ├── evaluation/
    │   │               │   │   ├── EvaluationDecision.java
    │   │               │   │   ├── ExtractedFactsDTO.java
    │   │               │   │   ├── PecuniarySpecification.java
    │   │               │   │   ├── PhysicalSizeSpecification.java
    │   │               │   │   ├── RuleEvaluationStatus.java
    │   │               │   │   ├── RuleSpecification.java
    │   │               │   │   └── StatuteRuleEvaluator.java
    │   │               │   ├── model/
    │   │               │   │   ├── ApplicationScope.java
    │   │               │   │   ├── DateWindowTrigger.java
    │   │               │   │   ├── GeographicTier.java
    │   │               │   │   ├── JurisdictionalRouting.java
    │   │               │   │   ├── LegalDomain.java
    │   │               │   │   ├── MaintainabilityBarRule.java
    │   │               │   │   ├── PecuniaryJurisdiction.java
    │   │               │   │   ├── PhysicalSizeExemption.java
    │   │               │   │   ├── StatuteRule.java
    │   │               │   │   ├── StatutoryPreconditions.java
    │   │               │   │   └── TribunalTier.java
    │   │               │   └── registry/
    │   │               │       └── StatuteRegistry.java
    │   │               ├── templates/
    │   │               │   ├── controller/
    │   │               │   │   ├── TemplateDraftController.java
    │   │               │   │   └── dto/
    │   │               │   │       ├── AiPopulateRequest.java
    │   │               │   │       ├── InitializeDraftRequest.java
    │   │               │   │       ├── RefineBlockRequest.java
    │   │               │   │       ├── UpdateBlockRequest.java
    │   │               │   │       └── WorkspaceDraftDTO.java
    │   │               │   ├── domain/
    │   │               │   │   ├── TemplateAiBlockSpec.java
    │   │               │   │   ├── TemplateCategory.java
    │   │               │   │   ├── TemplateManifest.java
    │   │               │   │   ├── TemplateParticular.java
    │   │               │   │   ├── TemplateRecommendation.java
    │   │               │   │   └── TemplateStage.java
    │   │               │   ├── entity/
    │   │               │   │   └── WorkspaceDraft.java
    │   │               │   ├── repository/
    │   │               │   │   └── WorkspaceDraftRepository.java
    │   │               │   └── service/
    │   │               │       ├── HybridTemplateDraftService.java
    │   │               │       ├── TemplateFactResolver.java
    │   │               │       ├── TemplateRecommendationService.java
    │   │               │       ├── TemplateRegistryService.java
    │   │               │       └── TemplateRenderer.java
    │   │               ├── triage/
    │   │               │   ├── config/
    │   │               │   │   └── LlmConfig.java
    │   │               │   ├── controller/
    │   │               │   │   └── TriageController.java
    │   │               │   ├── domain/
    │   │               │   │   ├── DraftingContext.java
    │   │               │   │   ├── EntityRole.java
    │   │               │   │   ├── ExtractedFacts.java
    │   │               │   │   ├── FinancialRole.java
    │   │               │   │   ├── LegalDomain.java
    │   │               │   │   ├── PartyEntity.java
    │   │               │   │   ├── RelationshipType.java
    │   │               │   │   ├── ResolvedForum.java
    │   │               │   │   ├── StatutePin.java
    │   │               │   │   ├── StatuteType.java
    │   │               │   │   └── TriagePipelineResult.java
    │   │               │   ├── dto/
    │   │               │   │   ├── CategorizationResult.java
    │   │               │   │   ├── FactChecklist.java
    │   │               │   │   ├── FullIntakeResponse.java
    │   │               │   │   ├── IntakeMessageRequest.java
    │   │               │   │   ├── IntakeMessageResponse.java
    │   │               │   │   ├── TriageBriefResponse.java
    │   │               │   │   └── TriageEvaluation.java
    │   │               │   ├── entities/
    │   │               │   │   ├── ConversationTurn.java
    │   │               │   │   ├── LegalCategory.java
    │   │               │   │   └── TriageSession.java
    │   │               │   ├── pipeline/
    │   │               │   │   ├── BriefGenerator.java
    │   │               │   │   ├── FactExtractor.java
    │   │               │   │   ├── ForumResolver.java
    │   │               │   │   ├── StatuteRetriever.java
    │   │               │   │   ├── TriagePipelineOrchestrator.java
    │   │               │   │   └── impl/
    │   │               │   │       ├── DefaultForumResolver.java
    │   │               │   │       ├── DslForumResolver.java
    │   │               │   │       ├── OkfStatuteRetrieverAdapter.java
    │   │               │   │       ├── OpenAiBriefGenerator.java
    │   │               │   │       ├── OpenAiFactExtractor.java
    │   │               │   │       ├── TriagePipelineOrchestratorImpl.java
    │   │               │   │       └── TwoPassPipelineOrchestrator.java
    │   │               │   ├── repo/
    │   │               │   │   └── TriageSessionRepo.java
    │   │               │   └── service/
    │   │               │       ├── CategoryFactRequirements.java
    │   │               │       ├── PromptConstants.java
    │   │               │       └── TriageService.java
    │   │               └── workspace/
    │   │                   ├── controller/
    │   │                   │   ├── WorkspaceController.java
    │   │                   │   └── dto/
    │   │                   │       ├── CreateCaseProjectRequest.java
    │   │                   │       ├── CreateQuickConsultRequest.java
    │   │                   │       ├── DocumentDetailSummaryResponse.java
    │   │                   │       ├── FactOverrideRequest.java
    │   │                   │       ├── NextStepSuggestionCard.java
    │   │                   │       ├── PromoteWorkspaceRequest.java
    │   │                   │       ├── TriageWorkspaceRequest.java
    │   │                   │       ├── UpdateWorkspaceFactsRequest.java
    │   │                   │       ├── WorkspaceChatRequest.java
    │   │                   │       ├── WorkspaceDetailResponse.java
    │   │                   │       ├── WorkspaceDocumentDTO.java
    │   │                   │       ├── WorkspaceDocumentSummary.java
    │   │                   │       ├── WorkspacePromptRequest.java
    │   │                   │       ├── WorkspacePromptResponse.java
    │   │                   │       └── WorkspaceSummaryItemDTO.java
    │   │                   ├── domain/
    │   │                   │   ├── CaseFactLedger.java
    │   │                   │   ├── DocumentOkfManifest.java
    │   │                   │   ├── DocumentType.java
    │   │                   │   ├── ExtractedPage.java
    │   │                   │   ├── IngestionStatus.java
    │   │                   │   ├── IracBrief.java
    │   │                   │   ├── PartyPerspective.java
    │   │                   │   ├── PinnedPageCitation.java
    │   │                   │   ├── PromptExecutionMode.java
    │   │                   │   ├── StructuredDocumentSummary.java
    │   │                   │   ├── WorkspaceChatResponse.java
    │   │                   │   └── WorkspaceMode.java
    │   │                   ├── entity/
    │   │                   │   ├── LegalWorkspace.java
    │   │                   │   ├── WorkspaceDocument.java
    │   │                   │   ├── WorkspaceDocumentPage.java
    │   │                   │   └── WorkspaceSavedAuthority.java
    │   │                   ├── ingestion/
    │   │                   │   ├── DocumentOkfIndexer.java
    │   │                   │   ├── DocumentPageExtractor.java
    │   │                   │   ├── OcrQualityGate.java
    │   │                   │   ├── OpenAiDocumentOkfIndexer.java
    │   │                   │   └── PdfBoxDocumentPageExtractor.java
    │   │                   ├── repository/
    │   │                   │   ├── LegalWorkspaceRepository.java
    │   │                   │   ├── WorkspaceDocumentPageRepository.java
    │   │                   │   ├── WorkspaceDocumentRepository.java
    │   │                   │   └── WorkspaceSavedAuthorityRepository.java
    │   │                   ├── service/
    │   │                   │   ├── CaseDocumentOkfRetriever.java
    │   │                   │   ├── FactReconcilerService.java
    │   │                   │   ├── WorkspaceAssistantService.java
    │   │                   │   ├── WorkspaceOrchestratorService.java
    │   │                   │   └── WorkspacePromptRouterService.java
    │   │                   └── util/
    │   │                       └── JsonFenceSanitizer.java
    │   └── resources/
    │       ├── application-dev.properties
    │       ├── application.properties
    │       ├── data/
    │       │   ├── Remaining-acts.json
    │       │   └── clean_core_laws.json
    │       ├── master_rules_engine.json
    │       ├── okf-bundles/
    │       │   ├── BANKING/ ...
    │       │   ├── CIVIL_PROPERTY/ ...
    │       │   ├── CONSTITUTIONAL/ ...
    │       │   ├── CONSUMER/ ...
    │       │   ├── CORPORATE/ ...
    │       │   ├── CRIMINAL/ ...
    │       │   ├── CROSS_CUTTING/ ...
    │       │   ├── CYBER/ ...
    │       │   ├── EMPLOYMENT/ ...
    │       │   ├── ENVIRONMENTAL/ ...
    │       │   ├── FAMILY/ ...
    │       │   ├── IP/ ...
    │       │   ├── TAXATION/ ...
    │       │   └── TENANCY/ ...
    │       ├── okf-precedents/
    │       │   └── verified_precedents_catalog.json
    │       ├── okf-templates/
    │       │   ├── BANKING/ ...
    │       │   ├── CIVIL_PROPERTY/ ...
    │       │   ├── CONSTITUTIONAL/ ...
    │       │   ├── CORPORATE/ ...
    │       │   ├── CRIMINAL/ ...
    │       │   ├── CYBER/ ...
    │       │   ├── EMPLOYMENT/ ...
    │       │   ├── ENVIRONMENTAL/ ...
    │       │   ├── FAMILY/ ...
    │       │   ├── IP/ ...
    │       │   ├── TAXATION/ ...
    │       │   └── TENANCY/ ...
    │       ├── prompts/
    │       │   ├── brief-system.st
    │       │   └── intake-system.st
    │       ├── static/
    │       └── templates/
    └── test/
        ├── java/
        │   └── com/
        │       └── app/
        │           └── refirm/
        │               ├── arguments/
        │               │   └── ArgumentPrepServiceTest.java
        │               ├── core/
        │               │   └── service/
        │               │       ├── LegalRetrievalAndDraftingIntegrationTest.java
        │               │       └── PdfExportServiceTest.java
        │               ├── intake/
        │               │   ├── IntakeControllerTest.java
        │               │   ├── IntakeOrchestratorServiceTest.java
        │               │   └── LunaFactExtractorServiceTest.java
        │               ├── rules/
        │               │   └── evaluation/
        │               │       └── StatuteRuleEvaluatorTest.java
        │               ├── scratch/
        │               ├── templates/
        │               │   ├── StatefulHybridDraftLifecycleTest.java
        │               │   ├── Step3HardeningTest.java
        │               │   ├── TemplateCorpusIntegrityTest.java
        │               │   ├── TemplateRegistryAndRecommendationTest.java
        │               │   └── ZeroTokenTemplateAutoFillTest.java
        │               ├── triage/
        │               │   ├── DslForumResolverTest.java
        │               │   ├── ExtractedFactsAndBriefDtoTest.java
        │               │   ├── OpenAiBriefGeneratorTest.java
        │               │   ├── OpenAiFactExtractorIntegrationTest.java
        │               │   ├── OpenAiFactExtractorTest.java
        │               │   ├── TriagePipelineOrchestratorIntegrationTest.java
        │               │   ├── TriagePipelineOrchestratorTest.java
        │               │   ├── TwoPassPipelineOrchestratorTest.java
        │               │   └── pipeline/
        │               │       └── ReraPipelineOrchestratorIntegrationTest.java
        │               └── workspace/
        │                   ├── CaseDocumentOkfRetrieverTest.java
        │                   ├── DocumentIngestionSafetyTest.java
        │                   ├── FactReconcilerServiceTest.java
        │                   ├── OcrQualityGateTest.java
        │                   ├── RefirmSteps1To4EndToEndTest.java
        │                   ├── WorkspaceAssistantServiceTest.java
        │                   └── WorkspacePromotionTest.java
        └── resources/
            └── application-test.properties

```
