package com.example.lineage.api;

import com.example.lineage.assistant.AssistantModelUnavailableException;
import com.example.lineage.assistant.AssistantProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {LineageAssistantController.class, FieldAnalysisController.class})
public class AssistantExceptionHandler {

    @ExceptionHandler(AssistantModelUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleUnavailableModel(AssistantModelUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage()
        );
        problem.setTitle("Assistant model unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(AssistantProviderException.class)
    public ResponseEntity<ProblemDetail> handleProviderFailure(AssistantProviderException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, exception.getMessage()
        );
        problem.setTitle("Assistant provider request failed");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }
}
