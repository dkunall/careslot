package com.careslot.interop.adapter.web;

import com.careslot.shared.error.ApiException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/** FHIR clients expect OperationOutcome, not problem+json, so errors on /fhir/r4 are translated here. */
@RestControllerAdvice(assignableTypes = FhirR4Controller.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class FhirErrors {

    private static final MediaType FHIR = MediaType.parseMediaType("application/fhir+json");

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        String code = switch (e.status()) {
            case 404 -> "not-found";
            case 403 -> "forbidden";
            case 409 -> "conflict";
            case 429 -> "throttled";
            default -> "processing";
        };
        return outcome(e.status(), code, e.code() + ": " + e.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, Object>> bad(Exception e) {
        return outcome(400, "invalid", "invalid id or parameter");
    }

    private static ResponseEntity<Map<String, Object>> outcome(int status, String code, String text) {
        return ResponseEntity.status(status).contentType(FHIR).body(Map.of("resourceType", "OperationOutcome",
                "issue", List.of(Map.of("severity", "error", "code", code, "diagnostics", text))));
    }
}
