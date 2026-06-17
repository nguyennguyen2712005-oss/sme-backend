package sme.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkAdjustResponse {
    private int successCount;
    private int errorCount;
    private List<AdjustError> errors;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdjustError {
        private int line;
        private String reason;
        private boolean retriable;
    }
}
