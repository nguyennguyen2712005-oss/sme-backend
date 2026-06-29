package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.DiscountRuleRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.DiscountCalculationResponse;
import sme.backend.entity.DiscountRule;
import sme.backend.service.DiscountRuleService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/discount-rules")
@RequiredArgsConstructor
public class DiscountRuleController {

    private final DiscountRuleService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    public ResponseEntity<ApiResponse<List<DiscountRule>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.getAll()));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DiscountRule>> create(@RequestBody DiscountRuleRequest req) {
        return ResponseEntity.ok(ApiResponse.created(service.create(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DiscountRule>> update(@PathVariable UUID id,
                                                             @RequestBody DiscountRuleRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/calculate")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    public ResponseEntity<ApiResponse<DiscountCalculationResponse>> calculate(
            @RequestParam BigDecimal totalAmount,
            @RequestParam(required = false) UUID warehouseId) {
        return ResponseEntity.ok(ApiResponse.ok(service.calculate(totalAmount, warehouseId)));
    }
}
