package com.app.refirm.triage.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationTurn {
    private String role; // "USER" or "AI"
    private String message;
    private Instant timestamp;
}
