package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sme.backend.service.BusinessReportService;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TestErrorController {

    private final BusinessReportService businessReportService;

    @GetMapping("/test-error")
    public ResponseEntity<String> testError() {
        try {
            businessReportService.getBusinessReport(null, Instant.now().minusSeconds(86400 * 30), Instant.now(), "day");
            return ResponseEntity.ok("Success");
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder();
            sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
            for (StackTraceElement element : e.getStackTrace()) {
                sb.append("  at ").append(element.toString()).append("\n");
            }
            if (e.getCause() != null) {
                sb.append("Caused by: ").append(e.getCause().getClass().getName()).append(": ").append(e.getCause().getMessage()).append("\n");
                for (StackTraceElement element : e.getCause().getStackTrace()) {
                    sb.append("  at ").append(element.toString()).append("\n");
                }
            }
            return ResponseEntity.internalServerError().body(sb.toString());
        }
    }
}
