package sme.backend.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CustomerAiService {

    private final ChatClient chatClient;

    public CustomerAiService(@Qualifier("customerChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String chat(String userMessage, java.util.List<java.util.Map<String, String>> history) {
        try {
            String systemPrompt = """
                    Bạn là trợ lý tư vấn khách hàng của nhà sách trực tuyến.
                    Nhiệm vụ của bạn:
                    - Giúp khách hàng tìm kiếm sách theo tên, tác giả, thể loại.
                    - Cung cấp thông tin tóm tắt về sách và giá bán nếu khách hàng yêu cầu.
                    - Trả lời bằng tiếng Việt, giọng điệu nhiệt tình, lịch sự và thân thiện.
                    - Gợi ý thêm các đầu sách tương tự nếu thấy phù hợp.
                    
                    NGUYÊN TẮC BẢO MẬT:
                    - TUYỆT ĐỐI KHÔNG chia sẻ thông tin nhạy cảm.
                    - Chỉ sử dụng các công cụ tìm kiếm được cung cấp. Không tự ý bịa đặt.
                    - Nếu không tìm thấy sách khách yêu cầu, hãy xin lỗi và giới thiệu các chủ đề khác.
                    """;

            java.util.List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();

            if (history != null) {
                for (java.util.Map<String, String> msg : history) {
                    String role = msg.get("role");
                    String content = msg.get("content");
                    if ("user".equals(role)) {
                        messages.add(new org.springframework.ai.chat.messages.UserMessage(content));
                    } else if ("assistant".equals(role)) {
                        messages.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
                    }
                }
            }

            messages.add(new org.springframework.ai.chat.messages.UserMessage(userMessage));

            return chatClient.prompt()
                    .system(systemPrompt)
                    .messages(messages)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Customer AI chat error: {}", e.getMessage(), e);
            if (e.getMessage() != null && e.getMessage().contains("429")) {
                return "Xin lỗi, hiện tại có quá nhiều người đang nhắn tin cùng lúc khiến hệ thống AI bị quá tải. Bạn vui lòng chờ khoảng 30 giây rồi hỏi lại mình nhé!";
            }
            return "Xin lỗi, hiện tại tôi đang gặp chút sự cố kỹ thuật. Bạn vui lòng thử lại sau nhé!";
        }
    }
}
