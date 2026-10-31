package com.app.refirm.intake;

import com.app.refirm.intake.payload.IntakeRequest;
import com.app.refirm.intake.payload.IntakeResponse;
import com.app.refirm.intake.payload.IntakeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("IntakeController REST Endpoint Tests")
class IntakeControllerTest {

    @Test
    @DisplayName("POST /api/v1.0/intake delegates to IntakeOrchestratorService and returns 200 OK")
    void shouldDelegateToOrchestratorService() {
        IntakeOrchestratorService orchestratorService = mock(IntakeOrchestratorService.class);
        IntakeController controller = new IntakeController(orchestratorService);

        IntakeRequest request = new IntakeRequest("My story", 1);
        IntakeResponse expectedResponse = IntakeResponse.needsClarification("Clarify please");
        when(orchestratorService.processIntake(request)).thenReturn(expectedResponse);

        ResponseEntity<IntakeResponse> responseEntity = controller.handleIntake(request);

        assertNotNull(responseEntity);
        assertEquals(200, responseEntity.getStatusCode().value());
        assertEquals(IntakeStatus.MISSING_DATA, responseEntity.getBody().status());
        assertEquals("Clarify please", responseEntity.getBody().followUpQuestion());
        verify(orchestratorService).processIntake(request);
    }
}
