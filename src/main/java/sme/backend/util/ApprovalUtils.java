package sme.backend.util;

import sme.backend.entity.User;

import java.util.UUID;

public final class ApprovalUtils {

    private ApprovalUtils() {}

    /**
     * Kiểm tra quy tắc duyệt chéo (cross-approval).
     *
     * - Manager tạo → Admin duyệt
     * - Admin tạo   → Manager kho liên quan duyệt (warehouseId phải khớp)
     * - Không ai được tự duyệt phiếu mình tạo
     *
     * @param approver            Người muốn duyệt
     * @param createdById         UUID người tạo phiếu
     * @param creatorRole         Role của người tạo (ROLE_MANAGER hoặc ROLE_ADMIN)
     * @param documentWarehouseId Kho liên quan đến phiếu (để xác minh Manager đúng chi nhánh)
     */
    public static boolean canApprove(User approver, UUID createdById,
                                     String creatorRole, UUID documentWarehouseId) {
        if (approver == null || createdById == null || creatorRole == null) return false;
        // Không tự duyệt
        if (approver.getId().equals(createdById)) return false;

        // Manager tạo → Admin duyệt
        if ("ROLE_MANAGER".equals(creatorRole)
                && approver.getRole() == User.UserRole.ROLE_ADMIN) {
            return true;
        }
        // Admin tạo → Manager kho liên quan duyệt
        if ("ROLE_ADMIN".equals(creatorRole)
                && approver.getRole() == User.UserRole.ROLE_MANAGER) {
            return documentWarehouseId != null
                    && documentWarehouseId.equals(approver.getWarehouseId());
        }
        return false;
    }
}
