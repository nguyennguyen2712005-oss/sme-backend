package sme.backend.entity;

public enum AdjustmentReasonType {
    DAMAGED,            // Hàng bị hư hỏng
    LOST,               // Mất hàng
    THEFT,              // Trộm cắp
    COUNTING_ERROR,     // Sai sót kiểm đếm
    UNRECORDED_RECEIPT, // Nhập hàng chưa ghi sổ
    EXPIRY,             // Hàng hết hạn
    OTHER               // Lý do khác
}
