package com.app.refirm.arguments.controller.dto;

import com.app.refirm.arguments.domain.ArgumentSubmission;

public record UpdateSubmissionRequest(
        ArgumentSubmission submission,
        boolean lockSubmission
) {}
