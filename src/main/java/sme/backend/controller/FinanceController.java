package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CreateCashbookEntryRequest;
import sme.backend.dto.request.PaySupplierDebtRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.CashbookSummaryResponse;
import sme.backend.dto.response.SupplierDebtResponse;
import sme.backend.dto.response.SupplierDebtSummaryResponse;
import sme.backend.entity.CashbookTransaction;
import sme.backend.entity.SupplierDebt;
import sme.backend.entity.User;
import sme.backend.security.UserPrincipal;
import sme.backend.service.FinanceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;

    // ĐÃ THÊM YÊU CẦU 7: API lấy quỹ Toàn hệ thống
    @GetMapping("/cashbook/balance/total")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<BigDecimal>> getTotalFundBalance() {
        BigDecimal cash = financeService.getCurrentBalance(null, "CASH_111");
        BigDecimal bank = financeService.getCurrentBalance(null, "BANK_112");
        return ResponseEntity.ok(ApiResponse.ok(cash.add(bank)));
    }

    // Hàm tiện ích để đảm bảo Manager luôn chỉ truy cập được kho của họ
    private UUID getEffectiveWarehouseId(UserPrincipal principal, UUID requestedWarehouseId) {
        if (principal.getRole() == User.UserRole.ROLE_ADMIN) {
            return requestedWarehouseId;
        }
        return principal.getWarehouseId();
    }

    @PostMapping("/cashbook")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<CashbookTransaction>> createEntry(
            @Valid @RequestBody CreateCashbookEntryRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
            
        if (principal.getRole() != User.UserRole.ROLE_ADMIN || req.getWarehouseId() == null) {
            req.setWarehouseId(principal.getWarehouseId());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(
                financeService.createManualEntry(req, principal.getUsername())));
    }

    @GetMapping("/cashbook/balance")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, BigDecimal>>> getBalance(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId) {
            
        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        BigDecimal cash = financeService.getCurrentBalance(wid, "CASH_111");
        BigDecimal bank = financeService.getCurrentBalance(wid, "BANK_112");

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "CASH_111", cash,
                "BANK_112", bank,
                "total",    cash.add(bank)
        )));
    }

    @GetMapping("/cashbook")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<CashbookTransaction>>> getCashbook(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam Instant from,
            @RequestParam Instant to) {
            
        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        return ResponseEntity.ok(ApiResponse.ok(
                financeService.getCashbookReport(wid, from, to)));
    }

    @GetMapping("/supplier-debts")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<SupplierDebtResponse>>> getOutstandingDebts(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId) {
            
        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        return ResponseEntity.ok(ApiResponse.ok(financeService.getOutstandingDebts(wid)));
    }

    @GetMapping("/supplier-debts/supplier/{supplierId}/total")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<BigDecimal>> getTotalOutstandingBySupplier(
            @PathVariable UUID supplierId) {
        return ResponseEntity.ok(ApiResponse.ok(
                financeService.getTotalOutstandingBySupplier(supplierId)));
    }

    @PostMapping("/supplier-debts/pay")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<SupplierDebt>> payDebt(
            @Valid @RequestBody PaySupplierDebtRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Thanh toán công nợ thành công",
                financeService.paySupplierDebt(req, principal.getUsername())));
    }

    @PostMapping("/cod-reconciliation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FinanceService.CodReconciliationResult>> reconcileCOD(
            @RequestBody List<Map<String, Object>> body,
            @RequestParam UUID warehouseId,
            @AuthenticationPrincipal UserPrincipal principal) {

        List<FinanceService.CodReconciliationItem> items = body.stream().map(row ->
                new FinanceService.CodReconciliationItem(
                        (String) row.get("orderCode"),
                        new BigDecimal(row.get("amountReceived").toString()),
                        new BigDecimal(row.get("shippingFee").toString()),
                        (String) row.get("shippingProvider")
                )).toList();

        return ResponseEntity.ok(ApiResponse.ok(
                financeService.reconcileCOD(items, warehouseId, principal.getUsername())));
    }

    @GetMapping("/cashbook/search")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Page<CashbookTransaction>>> searchCashbook(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "ALL") String fundType,
            @RequestParam(defaultValue = "ALL") String transactionType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
            
        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        return ResponseEntity.ok(ApiResponse.ok(
                financeService.searchCashbook(wid, from, to, fundType, transactionType, keyword, pageable)));
    }

    // =========================================================================
    // CASHBOOK SUMMARY — SUM tại DB level, thay thế .reduce() sai ở frontend
    // Params filter khớp hoàn toàn với /cashbook/search
    // =========================================================================
    @GetMapping("/cashbook/search/summary")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<CashbookSummaryResponse>> getCashbookSummary(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "ALL") String fundType,
            @RequestParam(defaultValue = "ALL") String transactionType,
            @RequestParam(required = false) String keyword) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        return ResponseEntity.ok(ApiResponse.ok(
                financeService.getCashbookSummary(wid, from, to, fundType, transactionType, keyword)));
    }

    // =========================================================================
    // SUPPLIER DEBTS — search có phân trang, thay thế fetch-all ở frontend
    // =========================================================================
    @GetMapping("/supplier-debts/search")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Page<SupplierDebtResponse>>> searchDebts(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);
        Pageable pageable = PageRequest.of(page, size);

        return ResponseEntity.ok(ApiResponse.ok(
                financeService.searchDebtsPaged(wid, search, pageable)));
    }

    // =========================================================================
    // SUPPLIER DEBTS SUMMARY — SUM tại DB level, thay thế .reduce() sai ở frontend
    // Params filter khớp hoàn toàn với /supplier-debts/search
    // =========================================================================
    @GetMapping("/supplier-debts/search/summary")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<SupplierDebtSummaryResponse>> getDebtSummary(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String search) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        return ResponseEntity.ok(ApiResponse.ok(
                financeService.getDebtSummary(wid, search)));
    }
}