
package sme.backend.ai;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.config.AppProperties;
import sme.backend.entity.KnowledgeDocument;
import sme.backend.entity.User;
import sme.backend.repository.KnowledgeDocumentRepository;
import sme.backend.repository.UserRepository;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AiService — RAG (Retrieval-Augmented Generation) Co-pilot
 *
 * Architecture:
 * 1. SYS-03: Upload file → Tika extract text → TokenTextSplitter chunking
 * → Gemini embedding → lưu vào pgvector
 * 2. Chat: User message → pgvector similarity search → top-K chunks
 * → System prompt + context + message → Gemini → response
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final EntityManager entityManager;

    // ─────────────────────────────────────────────────────────
    // SYS-03: UPLOAD & INDEX DOCUMENT
    // ─────────────────────────────────────────────────────────
    @Transactional
    public int indexDocument(Resource fileResource, String documentTitle, UUID uploadedBy) {
        log.info("Indexing document: {}", documentTitle);

        String fileName = fileResource.getFilename() != null ? fileResource.getFilename() : "unknown.txt";
        String fileExtension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".") + 1) : "txt";

        // 1. Lưu thông tin file vào DB trước để lấy ID
        KnowledgeDocument docRecord = KnowledgeDocument.builder()
                .title(documentTitle)
                .fileName(fileName)
                .fileType(fileExtension)
                .uploadedByUserId(uploadedBy)
                .isActive(true)
                .build();
        docRecord = documentRepository.save(docRecord);
        final String docIdStr = docRecord.getId().toString();

        // 2. Extract text với Apache Tika (hỗ trợ PDF, DOCX, PPTX...)
        TikaDocumentReader reader = new TikaDocumentReader(fileResource);
        List<Document> rawDocs = reader.get();

        // 3. Thêm metadata để RAG có thể lọc (Gắn ID tài liệu vào Metadata)
        rawDocs.forEach(doc -> {
            doc.getMetadata().put("documentId", docIdStr);
            doc.getMetadata().put("source", documentTitle);
            doc.getMetadata().put("uploadedBy", uploadedBy.toString());
            doc.getMetadata().put("indexedAt", Instant.now().toString());
        });

        // 4. Chunking: chia nhỏ văn bản
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(appProperties.getAi().getChunkSize())
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(rawDocs);

        // 5. Vector hóa + lưu vào pgvector
        vectorStore.add(chunks);

        log.info("Document indexed: {} chunks from '{}'", chunks.size(), documentTitle);
        return chunks.size();
    }

    // ─────────────────────────────────────────────────────────
    // GET ALL DOCUMENTS
    // ─────────────────────────────────────────────────────────
    public List<KnowledgeDocument> getAllDocuments() {
        List<KnowledgeDocument> docs = documentRepository.findAllByOrderByCreatedAtDesc();
        docs.forEach(d -> d.setChunkCount(documentRepository.countChunksByDocumentId(d.getId())));
        return docs;
    }

    // ─────────────────────────────────────────────────────────
    // DELETE DOCUMENT
    // ─────────────────────────────────────────────────────────
    @Transactional
    public void deleteDocument(UUID documentId) {
        // Xóa các vector nhúng trong pgvector (trực tiếp qua SQL Native)
        entityManager.createNativeQuery("DELETE FROM vector_store WHERE metadata->>'documentId' = :docId")
                .setParameter("docId", documentId.toString())
                .executeUpdate();

        // Xóa bản ghi trong bảng quản lý
        documentRepository.deleteById(documentId);
        log.info("Deleted document and its vectors: {}", documentId);
    }

    // ─────────────────────────────────────────────────────────
    // AI CHAT — RAG Co-pilot (SYS-03 + MODULE AI)
    // ─────────────────────────────────────────────────────────
    public String chat(String userMessage, UUID userId, String conversationHistory) {
        // Lấy thông tin user để personalise
        String userName = userRepository.findById(userId)
                .map(User::getFullName).orElse("người dùng");

        try {
            // 1. Tìm kiếm Vector thủ công (Manual RAG)
            List<Document> similarDocuments = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(userMessage)
                            .topK(appProperties.getAi().getTopKResults())
                            .similarityThreshold(0.65)
                            .build()
            );

            // 2. Nối nội dung các chunk tìm được thành chuỗi Context
            String context = similarDocuments.stream()
                    .map(Document::getFormattedContent)
                    .collect(Collectors.joining("\n---\n"));

            // 3. Đưa Context vào System Prompt
            String systemPrompt = buildSystemPrompt(userName, conversationHistory, context);

            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("AI chat error: {}", e.getMessage(), e);
            return "LỖI KẾT NỐI AI: " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────
    // VECTOR SEARCH (dùng cho sản phẩm tương tự)
    // ─────────────────────────────────────────────────────────
    public List<Document> searchSimilar(String query, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(0.6)
                        .build());
    }

    // ─────────────────────────────────────────────────────────
    // LẤY CHI TIẾT CÁC CHUNKS CỦA TÀI LIỆU
    // ─────────────────────────────────────────────────────────
    public List<String> getDocumentChunks(UUID documentId) {
        // Dùng Native Query truy vấn trực tiếp vào bảng vector_store
        @SuppressWarnings("unchecked")
        List<String> chunks = entityManager.createNativeQuery(
                "SELECT content FROM vector_store WHERE metadata->>'documentId' = :docId")
                .setParameter("docId", documentId.toString())
                .getResultList();

        return chunks;
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────
    private String buildSystemPrompt(String userName, String conversationHistory, String context) {
        return """
                Bạn là AI Co-pilot của hệ thống SME ERP & POS, trợ lý thông minh cho doanh nghiệp vừa và nhỏ.

                Vai trò của bạn:
                - Trả lời câu hỏi về nghiệp vụ: bán hàng, kho, tài chính, đơn hàng
                - Phân tích dữ liệu và đưa ra gợi ý kinh doanh
                - Hỗ trợ tra cứu chính sách, quy trình nội bộ từ tài liệu đã upload
                - Giao tiếp bằng tiếng Việt, thân thiện và chuyên nghiệp

                Tên người dùng: %s

                CÔNG CỤ DỮ LIỆU THỜI GIAN THỰC — luôn gọi công cụ phù hợp khi câu hỏi khớp, không tự suy đoán số liệu:
                - Hỏi doanh thu/lợi nhuận hôm nay → gọi getTodayRevenueFunction
                - Hỏi sản phẩm sắp hết hàng/tồn kho thấp → gọi getLowStockProductsFunction
                - Hỏi khách hàng thân thiết/chi tiêu nhiều nhất → gọi getTopCustomersFunction

                THÔNG TIN NGỮ CẢNH TỪ TÀI LIỆU (RAG):
                %s

                LỊCH SỬ HỘI THOẠI:
                %s

                Lưu ý quan trọng:
                - Với câu hỏi về số liệu kinh doanh thực tế (doanh thu, tồn kho, khách hàng), luôn gọi công cụ dữ liệu tương ứng ở trên để lấy số liệu thật.
                - Ưu tiên sử dụng "THÔNG TIN NGỮ CẢNH" để trả lời nếu câu hỏi liên quan đến chính sách, quy trình nội bộ từ tài liệu.
                - Nếu không tìm thấy thông tin (từ công cụ hoặc tài liệu), hãy nói rõ và tuyệt đối không bịa đặt (hallucinate).
                - Luôn trả lời ngắn gọn, có cấu trúc rõ ràng.
                """.formatted(
                userName,
                context.isBlank() ? "Không có tài liệu liên quan nào được tìm thấy." : context,
                conversationHistory != null ? conversationHistory : "Đây là đầu cuộc hội thoại");
    }
}
