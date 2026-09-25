package com.app.refirm.core.exception;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> duplicateEmailException(DuplicateKeyException ex){
        Map<String, Object> data = new HashMap<>();
        data.put("status",HttpStatus.CONFLICT);
        data.put("message","Email already Exists");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(data);
    }
}
