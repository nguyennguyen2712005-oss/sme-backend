package sme.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.response.AuditLogResponse;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getGlobalAuditLogs(int page, int size, String keyword, String actionFilter) {
        String baseSql = """
                    SELECT * FROM (
                        SELECT 'Người dùng' as entity_name, a.id as entity_id, a.revtype,
                               COALESCE(a.updated_by, a.created_by, 'SYSTEM') as changed_by,
                               a.rev, r.revtstmp as changed_at,
                               a.full_name as target_name
                        FROM users_audit a JOIN revinfo r ON a.rev = r.rev

                        UNION ALL

                        SELECT 'Sản phẩm' as entity_name, a.id as entity_id, a.revtype,
                               COALESCE(a.updated_by, a.created_by, 'SYSTEM') as changed_by,
                               a.rev, r.revtstmp as changed_at,
                               a.name as target_name
                        FROM products_audit a JOIN revinfo r ON a.rev = r.rev

                        UNION ALL

                        SELECT 'Chi nhánh' as entity_name, a.id as entity_id, a.revtype,
                               COALESCE(a.updated_by, a.created_by, 'SYSTEM') as changed_by,
                               a.rev, r.revtstmp as changed_at,
                               a.name as target_name
                        FROM warehouses_audit a JOIN revinfo r ON a.rev = r.rev

                        UNION ALL

                        SELECT 'Đơn hàng' as entity_name, a.id as entity_id, a.revtype,
                               COALESCE(a.updated_by, a.created_by, 'SYSTEM') as changed_by,
                               a.rev, r.revtstmp as changed_at,
                               a.code as target_name
                        FROM orders_audit a JOIN revinfo r ON a.rev = r.rev
                    ) AS combined_audit
                    WHERE changed_at IS NOT NULL
                """;

        StringBuilder whereClause = new StringBuilder();
        if (keyword != null && !keyword.isBlank()) {
            whereClause.append(" AND (LOWER(changed_by) LIKE LOWER(:keyword) OR LOWER(entity_name) LIKE LOWER(:keyword) OR LOWER(CAST(entity_id AS VARCHAR)) LIKE LOWER(:keyword) OR LOWER(target_name) LIKE LOWER(:keyword))");
        }
        if (actionFilter != null && !actionFilter.isBlank()) {
            int actionType = -1;
            if ("CREATE".equalsIgnoreCase(actionFilter)) actionType = 0;
            else if ("UPDATE".equalsIgnoreCase(actionFilter)) actionType = 1;
            else if ("DELETE".equalsIgnoreCase(actionFilter)) actionType = 2;
            
            if (actionType != -1) {
                whereClause.append(" AND revtype = :actionType");
            }
        }

        String countSql = "SELECT COUNT(*) FROM (" + baseSql + whereClause.toString() + ") AS temp_count";
        Query countQuery = entityManager.createNativeQuery(countSql);
        if (keyword != null && !keyword.isBlank()) {
            countQuery.setParameter("keyword", "%" + keyword + "%");
        }
        if (actionFilter != null && !actionFilter.isBlank() && (actionFilter.equalsIgnoreCase("CREATE") || actionFilter.equalsIgnoreCase("UPDATE") || actionFilter.equalsIgnoreCase("DELETE"))) {
            int actionType = actionFilter.equalsIgnoreCase("CREATE") ? 0 : (actionFilter.equalsIgnoreCase("UPDATE") ? 1 : 2);
            countQuery.setParameter("actionType", actionType);
        }

        long totalElements = ((Number) countQuery.getSingleResult()).longValue();

        String sql = baseSql + whereClause.toString() + " ORDER BY changed_at DESC LIMIT :limit OFFSET :offset";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("limit", size);
        query.setParameter("offset", page * size);
        
        if (keyword != null && !keyword.isBlank()) {
            query.setParameter("keyword", "%" + keyword + "%");
        }
        if (actionFilter != null && !actionFilter.isBlank() && (actionFilter.equalsIgnoreCase("CREATE") || actionFilter.equalsIgnoreCase("UPDATE") || actionFilter.equalsIgnoreCase("DELETE"))) {
            int actionType = actionFilter.equalsIgnoreCase("CREATE") ? 0 : (actionFilter.equalsIgnoreCase("UPDATE") ? 1 : 2);
            query.setParameter("actionType", actionType);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        List<AuditLogResponse> logs = new ArrayList<>();

        for (Object[] row : results) {
            String entityName = (String) row[0];
            UUID entityId = (UUID) row[1];

            Number revtypeNum = (Number) row[2];
            String actionTypeStr = switch (revtypeNum.intValue()) {
                case 0 -> "CREATE";
                case 1 -> "UPDATE";
                case 2 -> "DELETE";
                default -> "UNKNOWN";
            };

            String changedBy = (String) row[3];
            Integer revision = ((Number) row[4]).intValue();

            Instant changedAt = null;
            if (row[5] != null) {
                if (row[5] instanceof Timestamp ts) {
                    changedAt = ts.toInstant();
                } else if (row[5] instanceof java.util.Date d) {
                    changedAt = d.toInstant();
                } else if (row[5] instanceof java.time.LocalDateTime ldt) {
                    changedAt = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
                } else if (row[5] instanceof java.time.Instant inst) {
                    changedAt = inst;
                } else if (row[5] instanceof java.time.OffsetDateTime odt) {
                    changedAt = odt.toInstant();
                } else if (row[5] instanceof Long l) {
                    changedAt = Instant.ofEpochMilli(l);
                } else if (row[5] instanceof Number n) {
                    changedAt = Instant.ofEpochMilli(n.longValue());
                } else {
                    log.warn("Unknown timestamp type in getGlobalAuditLogs: {}", row[5].getClass().getName());
                }
            }

            String targetName = row[6] != null ? row[6].toString() : "N/A";

            logs.add(AuditLogResponse.builder()
                    .entityName(entityName)
                    .entityId(entityId)
                    .targetName(targetName)
                    .actionType(actionTypeStr)
                    .changedBy(changedBy)
                    .revision(revision)
                    .changedAt(changedAt)
                    .build());
        }

        return new PageImpl<>(logs, PageRequest.of(page, size), totalElements);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getProductPriceHistory(UUID productId) {
        String sql = """
                    SELECT a.retail_price, a.wholesale_price, a.mac_price, a.revtype,
                           COALESCE(a.updated_by, a.created_by, 'SYSTEM') as changed_by,
                           a.rev, r.revtstmp as changed_at
                    FROM products_audit a JOIN revinfo r ON a.rev = r.rev
                    WHERE a.id = :productId
                    ORDER BY changed_at DESC
                """;

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("productId", productId);

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> history = new ArrayList<>();

        for (Object[] row : results) {
            Instant changedAt = null;
            if (row[6] != null) {
                if (row[6] instanceof Timestamp ts) {
                    changedAt = ts.toInstant();
                } else if (row[6] instanceof java.util.Date d) {
                    changedAt = d.toInstant();
                } else if (row[6] instanceof java.time.LocalDateTime ldt) {
                    changedAt = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
                } else if (row[6] instanceof java.time.Instant inst) {
                    changedAt = inst;
                } else if (row[6] instanceof java.time.OffsetDateTime odt) {
                    changedAt = odt.toInstant();
                } else if (row[6] instanceof Long l) {
                    changedAt = Instant.ofEpochMilli(l);
                } else if (row[6] instanceof Number n) {
                    changedAt = Instant.ofEpochMilli(n.longValue());
                } else {
                    log.warn("Unknown timestamp type in getProductPriceHistory: {}", row[6].getClass().getName());
                }
            }

            history.add(Map.of(
                    "retailPrice", row[0] != null ? row[0] : 0,
                    "wholesalePrice", row[1] != null ? row[1] : 0,
                    "macPrice", row[2] != null ? row[2] : 0,
                    "revType", row[3],
                    "changedBy", row[4] != null ? row[4] : "SYSTEM",
                    "revision", row[5],
                    "changedAt", changedAt != null ? changedAt.toString() : ""));
        }
        return history;
    }
}