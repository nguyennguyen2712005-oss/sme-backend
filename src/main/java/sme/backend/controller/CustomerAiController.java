package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sme.backend.ai.CustomerAiService;
import sme.backend.dto.response.ApiResponse;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/public/ai")
@RequiredArgsConstructor
public class CustomerAiController {

    private final CustomerAiService customerAiService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, String>>> chat(@RequestBody Map<String, Object> body) {
        String message = (String) body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.<Map<String, String>>builder()
                            .success(false)
                            .message("Nội dung tin nhắn bắt buộc")
                            .build()
            );
        }
        
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = (List<Map<String, String>>) body.get("history");

        String reply = customerAiService.chat(message, history);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("reply", reply)));
    }
}
