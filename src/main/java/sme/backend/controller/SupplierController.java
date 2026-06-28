package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.entity.Supplier;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.SupplierRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierRepository supplierRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<Supplier>>> getAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        String kw = (keyword == null) ? "" : keyword.trim();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(supplierRepository.searchAllByKeyword(kw, pageable))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Supplier>> getOne(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(
                supplierRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Supplier", id))));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Supplier>> create(@RequestBody Map<String, Object> body) {
        String taxCode = (String) body.get("taxCode");

        if (taxCode != null && !taxCode.isBlank() && supplierRepository.existsByTaxCode(taxCode)) {
            throw new BusinessException("DUPLICATE_TAX_CODE",
                    "Mã số thuế '" + taxCode + "' đã tồn tại");
        }
        Supplier supplier = Supplier.builder()
                .taxCode(taxCode)
                .name((String) body.get("name"))
                .contactPerson((String) body.get("contactPerson"))
                .phone((String) body.get("phone"))
                .email((String) body.get("email"))
                .address((String) body.get("address"))
                .bankAccount((String) body.get("bankAccount"))
                .bankName((String) body.get("bankName"))
                .paymentTerms(body.get("paymentTerms") != null
                        ? Integer.parseInt(body.get("paymentTerms").toString()) : 30)
                .isActive(true)
                .notes((String) body.get("notes"))
                .build();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(supplierRepository.save(supplier)));
    }

    // ĐÃ THÊM MỚI BƯỚC 2: API IMPORT EXCEL HÀNG LOẠT
    @PostMapping("/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> importBulk(@RequestBody List<Map<String, Object>> payloadList) {
        List<Supplier> suppliersToSave = new ArrayList<>();
        int successCount = 0;
        
        for (Map<String, Object> row : payloadList) {
            String taxCode = (String) row.get("taxCode");
            
            // Kiểm tra trùng lặp mã số thuế, nếu trùng thì bỏ qua dòng này
            if (taxCode != null && !taxCode.isBlank() && supplierRepository.existsByTaxCode(taxCode)) {
                continue; 
            }

            Supplier s = Supplier.builder()
                    .taxCode(taxCode)
                    .name((String) row.get("name"))
                    .contactPerson((String) row.get("contactPerson"))
                    .phone((String) row.get("phone"))
                    .email((String) row.get("email"))
                    .address((String) row.get("address"))
                    .bankAccount((String) row.get("bankAccount"))
                    .bankName((String) row.get("bankName"))
                    .paymentTerms(row.get("paymentTerms") != null ? Integer.parseInt(row.get("paymentTerms").toString()) : 30)
                    .isActive(true)
                    .notes((String) row.get("notes"))
                    .build();

            suppliersToSave.add(s);
            successCount++;
        }

        // Lưu toàn bộ vào database 1 lần
        supplierRepository.saveAll(suppliersToSave);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Import thành công " + successCount + " nhà cung cấp!")));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Supplier>> update(
            @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Supplier s = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", id));

        // ĐÃ SỬA BƯỚC 1: KIỂM TRA TRÙNG LẶP MÃ SỐ THUẾ KHI SỬA
        if (body.containsKey("taxCode")) {
            String newTaxCode = body.get("taxCode") != null ? body.get("taxCode").toString().trim() : null;
            
            // Chỉ kiểm tra khi mã số thuế thay đổi so với hiện tại và không rỗng
            if (newTaxCode != null && !newTaxCode.isBlank() && !newTaxCode.equals(s.getTaxCode())) {
                boolean isDuplicate = supplierRepository.existsByTaxCode(newTaxCode);
                if (isDuplicate) {
                    throw new BusinessException("DUPLICATE_TAX_CODE", "Mã số thuế này đã tồn tại trong hệ thống!");
                }
            }
            s.setTaxCode(newTaxCode);
        }

        if (body.containsKey("name"))          s.setName((String) body.get("name"));
        if (body.containsKey("contactPerson")) s.setContactPerson((String) body.get("contactPerson"));
        if (body.containsKey("phone"))         s.setPhone((String) body.get("phone"));
        if (body.containsKey("email"))         s.setEmail((String) body.get("email"));
        if (body.containsKey("address"))       s.setAddress((String) body.get("address"));
        if (body.containsKey("bankAccount"))   s.setBankAccount((String) body.get("bankAccount"));
        if (body.containsKey("bankName"))      s.setBankName((String) body.get("bankName"));
        if (body.containsKey("paymentTerms"))  s.setPaymentTerms(Integer.parseInt(body.get("paymentTerms").toString()));
        if (body.containsKey("notes"))         s.setNotes((String) body.get("notes"));
        if (body.containsKey("isActive"))      s.setIsActive((Boolean) body.get("isActive"));

        return ResponseEntity.ok(ApiResponse.ok(supplierRepository.save(s)));
    }
}