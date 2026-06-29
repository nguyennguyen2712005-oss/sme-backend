package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CreateCashbookEntryRequest;
import sme.backend.dto.request.PaySupplierDebtRequest;
import sme.backend.dto.response.CashbookSummaryResponse;
import sme.backend.dto.response.SupplierDebtResponse;
import sme.backend.dto.response.SupplierDebtSummaryResponse;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FinanceService {

    private final CashbookTransactionRepository cashbookRepository;
    private final SupplierDebtRepository supplierDebtRepository;
    private final OrderRepository orderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final WarehouseRepository warehouseRepository;

    @Transactional
    public CashbookTransaction createManualEntry(CreateCashbookEntryRequest req, String createdBy) {
        CashbookTransaction.FundType fundType;
        CashbookTransaction.TransactionType txnType;
        try {
            fundType = CashbookTransaction.FundType.valueOf(req.getFundType().toUpperCase());
            txnType  = CashbookTransaction.TransactionType.valueOf(req.getTransactionType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ENUM", "fundType hoặc transactionType không hợp lệ");
        }

        BigDecimal balanceBefore = cashbookRepository.getCurrentBalanceByWarehouse(req.getWarehouseId(), fundType);
        if (balanceBefore == null) balanceBefore = BigDecimal.ZERO;

        BigDecimal balanceAfter  = txnType == CashbookTransaction.TransactionType.IN
                ? balanceBefore.add(req.getAmount())
                : balanceBefore.subtract(req.getAmount());

        if (balanceAfter.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("INSUFFICIENT_FUNDS",
                    "Số dư quỹ không đủ. Hiện có: " + balanceBefore);
        }

        CashbookTransaction txn = CashbookTransaction.builder()
                .warehouseId(req.getWarehouseId())
                .fundType(fundType)
                .transactionType(txnType)
                .referenceType(req.getReferenceType())
                .amount(req.getAmount())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .description(req.getDescription())
                .personName(req.getPersonName())
                .createdBy(createdBy)
                .approvalStatus(CashbookTransaction.ApprovalStatus.APPROVED)
                .build();

        return cashbookRepository.save(txn);
    }

    @Transactional
    public CashbookTransaction createPendingEntry(CreateCashbookEntryRequest req, String createdBy) {
        CashbookTransaction.FundType fundType;
        CashbookTransaction.TransactionType txnType;
        try {
            fundType = CashbookTransaction.FundType.valueOf(req.getFundType().toUpperCase());
            txnType  = CashbookTransaction.TransactionType.valueOf(req.getTransactionType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ENUM", "fundType hoặc transactionType không hợp lệ");
        }

        CashbookTransaction txn = CashbookTransaction.builder()
                .warehouseId(req.getWarehouseId())
                .fundType(fundType)
                .transactionType(txnType)
                .referenceType(req.getReferenceType())
                .amount(req.getAmount())
                .description(req.getDescription())
                .personName(req.getPersonName())
                .createdBy(createdBy)
                .approvalStatus(CashbookTransaction.ApprovalStatus.PENDING)
                .build();

        return cashbookRepository.save(txn);
    }

    @Transactional(readOnly = true)
    public List<CashbookTransaction> getPendingEntries(UUID warehouseId) {
        if (warehouseId == null) {
            return cashbookRepository.findByApprovalStatusOrderByCreatedAtDesc(
                    CashbookTransaction.ApprovalStatus.PENDING);
        }
        return cashbookRepository.findByWarehouseIdAndApprovalStatusOrderByCreatedAtDesc(
                warehouseId, CashbookTransaction.ApprovalStatus.PENDING);
    }

    @Transactional
    public CashbookTransaction approveEntry(UUID id) {
        CashbookTransaction txn = cashbookRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CashbookTransaction", id));

        if (txn.getApprovalStatus() != CashbookTransaction.ApprovalStatus.PENDING) {
            throw new BusinessException("NOT_PENDING", "Phiếu này không ở trạng thái chờ duyệt");
        }

        BigDecimal balanceBefore = cashbookRepository.getCurrentBalanceByWarehouse(
                txn.getWarehouseId(), txn.getFundType());
        if (balanceBefore == null) balanceBefore = BigDecimal.ZERO;

        BigDecimal balanceAfter = txn.getTransactionType() == CashbookTransaction.TransactionType.IN
                ? balanceBefore.add(txn.getAmount())
                : balanceBefore.subtract(txn.getAmount());

        if (balanceAfter.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("INSUFFICIENT_FUNDS",
                    "Số dư quỹ không đủ để duyệt. Hiện có: " + balanceBefore);
        }

        txn.setBalanceBefore(balanceBefore);
        txn.setBalanceAfter(balanceAfter);
        txn.setApprovalStatus(CashbookTransaction.ApprovalStatus.APPROVED);
        return cashbookRepository.save(txn);
    }

    @Transactional
    public CashbookTransaction rejectEntry(UUID id, String reason) {
        CashbookTransaction txn = cashbookRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CashbookTransaction", id));

        if (txn.getApprovalStatus() != CashbookTransaction.ApprovalStatus.PENDING) {
            throw new BusinessException("NOT_PENDING", "Phiếu này không ở trạng thái chờ duyệt");
        }

        txn.setApprovalStatus(CashbookTransaction.ApprovalStatus.REJECTED);
        txn.setRejectReason(reason);
        return cashbookRepository.save(txn);
    }

    @Transactional(readOnly = true)
    public List<CashbookTransaction> getDebtPaymentHistory(UUID debtId) {
        SupplierDebt debt = supplierDebtRepository.findById(debtId)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierDebt", debtId));
        return cashbookRepository.findByReferenceTypeAndReferenceIdOrderByCreatedAtDesc(
                "SUPPLIER_PAYMENT", debt.getPurchaseOrderId());
    }

    @Transactional(readOnly = true)
    public Page<CashbookTransaction> searchCashbook(
            UUID warehouseId, Instant from, Instant to, 
            String fundTypeStr, String txnTypeStr, String keyword, Pageable pageable) {
        
        List<CashbookTransaction.FundType> fundTypes = ("ALL".equalsIgnoreCase(fundTypeStr) || fundTypeStr == null) 
                ? List.of(CashbookTransaction.FundType.values()) 
                : List.of(CashbookTransaction.FundType.valueOf(fundTypeStr.toUpperCase()));
                
        List<CashbookTransaction.TransactionType> txnTypes = ("ALL".equalsIgnoreCase(txnTypeStr) || txnTypeStr == null) 
                ? List.of(CashbookTransaction.TransactionType.values()) 
                : List.of(CashbookTransaction.TransactionType.valueOf(txnTypeStr.toUpperCase()));
                
        String kw = (keyword == null) ? "" : keyword.trim();

        if (warehouseId == null) {
            return cashbookRepository.searchCashbookAll(from, to, fundTypes, txnTypes, kw, pageable);
        }
        return cashbookRepository.searchCashbookByWarehouse(warehouseId, from, to, fundTypes, txnTypes, kw, pageable);
    }

    @Transactional
    public SupplierDebt paySupplierDebt(PaySupplierDebtRequest req, String paidBy) {
        SupplierDebt debt = supplierDebtRepository.findById(req.getSupplierDebtId())
                .orElseThrow(() -> new ResourceNotFoundException("SupplierDebt", req.getSupplierDebtId()));

        if (debt.getStatus() == SupplierDebt.DebtStatus.PAID) {
            throw new BusinessException("DEBT_ALREADY_PAID", "Công nợ này đã được thanh toán đầy đủ");
        }

        debt.pay(req.getAmount());
        supplierDebtRepository.save(debt);

        CashbookTransaction.FundType fundType;
        try {
            fundType = CashbookTransaction.FundType.valueOf(req.getFundType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_FUND_TYPE", "fundType không hợp lệ: " + req.getFundType());
        }

        UUID warehouseId = getWarehouseFromDebt(debt);
        BigDecimal balanceBefore = cashbookRepository.getCurrentBalanceByWarehouse(warehouseId, fundType);
        if (balanceBefore == null) balanceBefore = BigDecimal.ZERO;
        
        BigDecimal balanceAfter = balanceBefore.subtract(req.getAmount());

        if (balanceAfter.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("INSUFFICIENT_FUNDS",
                    "Số dư quỹ không đủ để thanh toán. Hiện có: " + balanceBefore);
        }

        CashbookTransaction paymentTxn = CashbookTransaction.builder()
                .warehouseId(warehouseId)
                .fundType(fundType)
                .transactionType(CashbookTransaction.TransactionType.OUT)
                .referenceType("SUPPLIER_PAYMENT")
                .referenceId(debt.getPurchaseOrderId())
                .amount(req.getAmount())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .description(req.getNote() != null ? req.getNote()
                        : "Thanh toán công nợ NCC - PO: " + debt.getPurchaseOrderId())
                .createdBy(paidBy)
                .build();

        cashbookRepository.save(paymentTxn);
        return debt;
    }

    @Transactional
    public CodReconciliationResult reconcileCOD(List<CodReconciliationItem> items,
                                                UUID warehouseId, String reconciledBy) {
        int matched = 0, notFound = 0;
        BigDecimal totalReceived = BigDecimal.ZERO;
        BigDecimal totalShippingFee = BigDecimal.ZERO;

        for (CodReconciliationItem item : items) {
            Order order = orderRepository.findByCode(item.orderCode()).orElse(null);
            if (order == null) {
                notFound++;
                continue;
            }

            if (Boolean.TRUE.equals(order.getCodReconciled())) continue;

            order.setCodReconciled(true);
            order.setPaymentStatus(Order.PaymentStatus.PAID);
            orderRepository.save(order);

            cashbookRepository.save(CashbookTransaction.builder()
                    .warehouseId(warehouseId)
                    .fundType(CashbookTransaction.FundType.BANK_112)
                    .transactionType(CashbookTransaction.TransactionType.IN)
                    .referenceType("COD_RECONCILIATION")
                    .referenceId(order.getId())
                    .amount(item.amountReceived())
                    .description("COD đơn #" + order.getCode() + " - " + item.shippingProvider())
                    .createdBy(reconciledBy)
                    .build());

            if (item.shippingFee().compareTo(BigDecimal.ZERO) > 0) {
                cashbookRepository.save(CashbookTransaction.builder()
                        .warehouseId(warehouseId)
                        .fundType(CashbookTransaction.FundType.BANK_112)
                        .transactionType(CashbookTransaction.TransactionType.OUT)
                        .referenceType("COD_RECONCILIATION")
                        .referenceId(order.getId())
                        .amount(item.shippingFee())
                        .description("Phí ship đơn #" + order.getCode() + " - " + item.shippingProvider())
                        .createdBy(reconciledBy)
                        .build());
            }

            totalReceived   = totalReceived.add(item.amountReceived());
            totalShippingFee = totalShippingFee.add(item.shippingFee());
            matched++;
        }

        return new CodReconciliationResult(matched, notFound,
                totalReceived, totalShippingFee,
                totalReceived.subtract(totalShippingFee));
    }

    @Transactional(readOnly = true)
    public BigDecimal getCurrentBalance(UUID warehouseId, String fundType) {
        CashbookTransaction.FundType type = CashbookTransaction.FundType.valueOf(fundType.toUpperCase());
        BigDecimal bal;
        if (warehouseId == null) {
            bal = cashbookRepository.getCurrentBalanceAll(type);
        } else {
            bal = cashbookRepository.getCurrentBalanceByWarehouse(warehouseId, type);
        }
        return bal != null ? bal : BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public List<CashbookTransaction> getCashbookReport(UUID warehouseId, Instant from, Instant to) {
        if (warehouseId == null) {
            return cashbookRepository.findAllByDateRange(from, to);
        }
        return cashbookRepository.findByWarehouseAndDateRange(warehouseId, from, to);
    }

    @Transactional(readOnly = true)
    public List<SupplierDebtResponse> getOutstandingDebts(UUID warehouseId) {
        List<SupplierDebt> debts = supplierDebtRepository.findOutstandingDebtsByWarehouse(warehouseId);

        return debts.stream().map(debt -> {
            var po = purchaseOrderRepository.findById(debt.getPurchaseOrderId()).orElse(null);
            String warehouseName = "Không rõ";
            String poCode = null;
            UUID wid = null;

            if (po != null) {
                wid = po.getWarehouseId();
                poCode = po.getCode();
                var w = warehouseRepository.findById(wid).orElse(null);
                if (w != null) warehouseName = w.getName();
            }

            return SupplierDebtResponse.builder()
                    .id(debt.getId())
                    .supplierId(debt.getSupplierId())
                    .purchaseOrderId(debt.getPurchaseOrderId())
                    .purchaseOrderCode(poCode)
                    .warehouseId(wid)
                    .warehouseName(warehouseName)
                    .totalDebt(debt.getTotalDebt())
                    .paidAmount(debt.getPaidAmount())
                    .remainingAmount(debt.getRemainingAmount())
                    .status(debt.getStatus().name())
                    .dueDate(debt.getDueDate())
                    .createdAt(debt.getCreatedAt())
                    .build();
        }).toList();
    }

    // ĐÃ BỔ SUNG: Tính tổng công nợ của một Nhà cung cấp
    @Transactional(readOnly = true)
    public BigDecimal getTotalOutstandingBySupplier(UUID supplierId) {
        BigDecimal total = supplierDebtRepository.getTotalOutstandingBySupplierId(supplierId);
        return total != null ? total : BigDecimal.ZERO;
    }

    // =========================================================================
    // CASHBOOK SUMMARY — tính SUM tại DB level, thay thế .reduce() sai ở frontend
    // (Lý do: frontend cũ chỉ tính trên 1 trang data, không phải toàn bộ kỳ)
    // =========================================================================
    @Transactional(readOnly = true)
    public CashbookSummaryResponse getCashbookSummary(
            UUID warehouseId, Instant from, Instant to,
            String fundTypeStr, String txnTypeStr, String keyword) {

        List<CashbookTransaction.FundType> fundTypes = ("ALL".equalsIgnoreCase(fundTypeStr) || fundTypeStr == null)
                ? List.of(CashbookTransaction.FundType.values())
                : List.of(CashbookTransaction.FundType.valueOf(fundTypeStr.toUpperCase()));

        List<CashbookTransaction.TransactionType> txnTypes = ("ALL".equalsIgnoreCase(txnTypeStr) || txnTypeStr == null)
                ? List.of(CashbookTransaction.TransactionType.values())
                : List.of(CashbookTransaction.TransactionType.valueOf(txnTypeStr.toUpperCase()));

        String kw = (keyword == null) ? "" : keyword.trim();

        if (warehouseId == null) {
            return cashbookRepository.summaryCashbookAll(from, to, fundTypes, txnTypes, kw);
        }
        return cashbookRepository.summaryCashbookByWarehouse(warehouseId, from, to, fundTypes, txnTypes, kw);
    }

    // =========================================================================
    // SUPPLIER DEBT — search có phân trang + summary tại DB level
    // (Lý do: frontend cũ fetch toàn bộ list rồi .reduce() gây memory issue)
    // =========================================================================
    @Transactional(readOnly = true)
    public Page<SupplierDebtResponse> searchDebtsPaged(UUID warehouseId, String search, String statusStr, Pageable pageable) {
        String kw = (search == null) ? "" : search.trim();
        SupplierDebt.DebtStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try { status = SupplierDebt.DebtStatus.valueOf(statusStr.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        Page<SupplierDebt> page = (status == null)
                ? supplierDebtRepository.searchAllDebts(warehouseId, kw, pageable)
                : supplierDebtRepository.searchDebtsByStatus(warehouseId, kw, status, pageable);

        return page.map(debt -> {
            var po = purchaseOrderRepository.findById(debt.getPurchaseOrderId()).orElse(null);
            String warehouseName = "Không rõ";
            String poCode = null;
            UUID wid = null;

            if (po != null) {
                wid = po.getWarehouseId();
                poCode = po.getCode();
                var w = warehouseRepository.findById(wid).orElse(null);
                if (w != null) warehouseName = w.getName();
            }

            return SupplierDebtResponse.builder()
                    .id(debt.getId())
                    .supplierId(debt.getSupplierId())
                    .purchaseOrderId(debt.getPurchaseOrderId())
                    .purchaseOrderCode(poCode)
                    .warehouseId(wid)
                    .warehouseName(warehouseName)
                    .totalDebt(debt.getTotalDebt())
                    .paidAmount(debt.getPaidAmount())
                    .remainingAmount(debt.getRemainingAmount())
                    .status(debt.getStatus().name())
                    .dueDate(debt.getDueDate())
                    .createdAt(debt.getCreatedAt())
                    .build();
        });
    }

    @Transactional(readOnly = true)
    public SupplierDebtSummaryResponse getDebtSummary(UUID warehouseId, String search) {
        String kw = (search == null) ? "" : search.trim();
        return supplierDebtRepository.getSupplierDebtSummary(warehouseId, kw);
    }

    private UUID getWarehouseFromDebt(SupplierDebt debt) {
        return purchaseOrderRepository.findById(debt.getPurchaseOrderId())
                .map(PurchaseOrder::getWarehouseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PurchaseOrder", debt.getPurchaseOrderId()));
    }

    public record CodReconciliationItem(
            String orderCode,
            BigDecimal amountReceived,
            BigDecimal shippingFee,
            String shippingProvider
    ) {}

    public record CodReconciliationResult(
            int matched,
            int notFound,
            BigDecimal totalReceived,
            BigDecimal totalShippingFee,
            BigDecimal netAmount
    ) {}
}