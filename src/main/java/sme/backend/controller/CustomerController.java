package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CustomerAddressRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.dto.response.WishlistItemResponse;
import sme.backend.entity.Customer;
import sme.backend.entity.CustomerAddress;
import sme.backend.entity.Invoice;
import sme.backend.entity.Order;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.CustomerRepository;
import sme.backend.repository.InvoiceRepository;
import sme.backend.repository.OrderRepository;
import sme.backend.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import sme.backend.service.CustomerService;
import sme.backend.service.CustomerAddressService;
import sme.backend.service.WishlistService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository; 
    private final OrderRepository orderRepository;
    private final CustomerService customerService; 
    private final CustomerAddressService customerAddressService;
    private final WishlistService wishlistService;
    private final sme.backend.repository.ProductRepository productRepository;
    private final sme.backend.repository.ProductReviewRepository productReviewRepository;

    // =========================================================================
    // ENDPOINT DÀNH CHO KHÁCH HÀNG (STOREFRONT)
    // =========================================================================

    /** GET /customers/me — Lấy thông tin cá nhân của khách hàng */
    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Customer>> getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        return ResponseEntity.ok(ApiResponse.ok(customer));
    }

    /** GET /customers/me/history — Lấy lịch sử mua hàng của khách hàng */
    @GetMapping("/me/history")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyHistory(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        return getCustomerHistory(customer.getId(), page, size);
    }

    /** PUT /customers/me — Cập nhật thông tin cá nhân của khách hàng */
    @PutMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Customer>> updateMyProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> body) {
        
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));

        if (body.containsKey("fullName")) customer.setFullName((String) body.get("fullName"));
        if (body.containsKey("email")) customer.setEmail((String) body.get("email"));
        if (body.containsKey("address")) customer.setAddress((String) body.get("address"));
        if (body.containsKey("gender")) customer.setGender((String) body.get("gender"));
        if (body.containsKey("avatarUrl")) customer.setAvatarUrl((String) body.get("avatarUrl"));
        if (body.containsKey("dateOfBirth")) {
            String dobStr = (String) body.get("dateOfBirth");
            if (dobStr != null && !dobStr.isBlank()) {
                customer.setDateOfBirth(java.time.LocalDate.parse(dobStr));
            } else {
                customer.setDateOfBirth(null);
            }
        }
        
        if (body.containsKey("phoneNumber") && body.get("phoneNumber") != null) {
            String newPhone = (String) body.get("phoneNumber");
            if (!newPhone.equals(customer.getPhoneNumber()) && customerRepository.existsByPhoneNumber(newPhone)) {
                throw new sme.backend.exception.BusinessException("DUPLICATE_PHONE", "Số điện thoại này đã được đăng ký bởi người khác");
            }
            customer.setPhoneNumber(newPhone);
        }
        
        if (body.containsKey("provinceCode")) {
            customer.setProvinceCode((String) body.get("provinceCode"));
        }
        
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật thành công", customerRepository.save(customer)));
    }

    // --- WISHLIST ---
    @GetMapping("/me/wishlist")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<WishlistItemResponse>>> getMyWishlist(@AuthenticationPrincipal UserPrincipal principal) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        return ResponseEntity.ok(ApiResponse.ok(wishlistService.getWishlist(customer.getId())));
    }

    @PostMapping("/me/wishlist/{productId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Void>> addToWishlist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID productId) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        wishlistService.addToWishlist(customer.getId(), productId);
        return ResponseEntity.ok(ApiResponse.ok("Đã thêm vào yêu thích", null));
    }

    @DeleteMapping("/me/wishlist/{productId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Void>> removeFromWishlist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID productId) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        wishlistService.removeFromWishlist(customer.getId(), productId);
        return ResponseEntity.ok(ApiResponse.ok("Đã xóa khỏi yêu thích", null));
    }

    // --- ADDRESSES ---
    @GetMapping("/me/addresses")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<CustomerAddress>>> getMyAddresses(@AuthenticationPrincipal UserPrincipal principal) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        return ResponseEntity.ok(ApiResponse.ok(customerAddressService.getAddresses(customer.getId())));
    }

    @PostMapping("/me/addresses")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<CustomerAddress>> addAddress(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CustomerAddressRequest req) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        return ResponseEntity.ok(ApiResponse.ok(customerAddressService.addAddress(customer.getId(), req)));
    }

    @PutMapping("/me/addresses/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<CustomerAddress>> updateAddress(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody CustomerAddressRequest req) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        return ResponseEntity.ok(ApiResponse.ok(customerAddressService.updateAddress(customer.getId(), id, req)));
    }

    @DeleteMapping("/me/addresses/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Void>> deleteAddress(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        customerAddressService.deleteAddress(customer.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok("Xóa địa chỉ thành công", null));
    }

    @PutMapping("/me/addresses/{id}/default")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Void>> setDefaultAddress(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        Customer customer = customerRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ khách hàng"));
        customerAddressService.setDefault(customer.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok("Đặt địa chỉ mặc định thành công", null));
    }


    // =========================================================================
    // ENDPOINT DÀNH CHO NHÂN VIÊN (POS & ADMIN)
    // =========================================================================

    /** GET /customers/lookup?phone=... — POS-03: Định danh khách (F3) */
    @GetMapping("/lookup")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Customer>> lookupByPhone(@RequestParam String phone) {
        Customer customer = customerRepository.findByPhoneNumberAndIsActiveTrue(phone)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy khách hàng với SĐT: " + phone));
        return ResponseEntity.ok(ApiResponse.ok(customer));
    }

    /** GET /customers — Tìm kiếm CRM (ĐÃ NÂNG CẤP THÊM LỌC THEO TIER) */
    @GetMapping
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')") 
    public ResponseEntity<ApiResponse<PageResponse<Customer>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tier,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        String kw = (keyword == null) ? "" : keyword.trim();
        
        Customer.CustomerTier customerTier = null;
        if (tier != null && !tier.isBlank()) {
            try {
                customerTier = Customer.CustomerTier.valueOf(tier.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(customerRepository.searchWithFilters(kw, customerTier, pageable))));
    }

    /** GET /customers/top — Lấy top khách hàng chi tiêu cao */
    @GetMapping("/top")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<Customer>>> getTopSpenders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        var pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(customerRepository.findTopCustomers(pageable))));
    }

    /** GET /customers/{id}/history — Lấy lịch sử mua hàng (POS & Online) */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN','CASHIER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCustomerHistory(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        Page<Invoice> invoices = invoiceRepository.findByCustomerIdOrderByCreatedAtDesc(id, pageable);
        Page<Order> orders = orderRepository.findByCustomerIdOrderByCreatedAtDesc(id, pageable);
        
        Customer customer = customerRepository.findById(id).orElse(null);
        String custName = customer != null ? customer.getFullName() : null;
        String custPhone = customer != null ? customer.getPhoneNumber() : null;

        List<Map<String, Object>> invoiceSummary = new java.util.ArrayList<>();
        for (Invoice inv : invoices.getContent()) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", inv.getId());
            map.put("code", inv.getCode());
            map.put("type", inv.getType().name());
            map.put("totalAmount", inv.getTotalAmount());
            map.put("finalAmount", inv.getFinalAmount());
            map.put("createdAt", inv.getCreatedAt());
            map.put("customerName", custName);
            map.put("customerPhone", custPhone);
            
            List<Map<String, Object>> itemsList = new java.util.ArrayList<>();
            for (sme.backend.entity.InvoiceItem item : inv.getItems()) {
                String productName = productRepository.findById(item.getProductId())
                        .map(sme.backend.entity.Product::getName)
                        .orElse("Sản phẩm không xác định");
                Map<String, Object> itemMap = new java.util.HashMap<>();
                itemMap.put("productId", item.getProductId());
                itemMap.put("productName", productName);
                itemMap.put("quantity", item.getQuantity());
                itemMap.put("unitPrice", item.getUnitPrice());
                itemMap.put("subtotal", item.getSubtotal());
                productRepository.findById(item.getProductId()).ifPresent(p -> itemMap.put("imageUrl", p.getImageUrl()));
                itemsList.add(itemMap);
            }
            map.put("items", itemsList);
            invoiceSummary.add(map);
        }

        List<Map<String, Object>> orderSummary = new java.util.ArrayList<>();
        for (Order ord : orders.getContent()) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", ord.getId());
            map.put("code", ord.getCode());
            map.put("type", "ONLINE");
            map.put("status", ord.getStatus().name());
            map.put("totalAmount", ord.getTotalAmount());
            map.put("finalAmount", ord.getFinalAmount());
            map.put("paymentMethod", ord.getPaymentMethod() != null ? ord.getPaymentMethod() : "COD");
            map.put("paymentStatus", ord.getPaymentStatus() != null ? ord.getPaymentStatus().name() : "UNPAID");
            map.put("createdAt", ord.getCreatedAt());
            map.put("shippingName", ord.getShippingName());
            map.put("shippingPhone", ord.getShippingPhone());
            map.put("shippingAddress", ord.getShippingAddress());
            map.put("customerName", custName);
            map.put("customerPhone", custPhone);
            map.put("shippingFee", ord.getShippingFee());
            map.put("discountAmount", ord.getDiscountAmount());
            
            List<Map<String, Object>> itemsList = new java.util.ArrayList<>();
            for (sme.backend.entity.OrderItem item : ord.getItems()) {
                String productName = productRepository.findById(item.getProductId())
                        .map(sme.backend.entity.Product::getName)
                        .orElse("Sản phẩm không xác định");
                boolean isRev = productReviewRepository.existsByProductIdAndCustomerIdAndOrderId(
                        item.getProductId(), id, ord.getId());
                Map<String, Object> itemMap = new java.util.HashMap<>();
                itemMap.put("productId", item.getProductId());
                itemMap.put("productName", productName);
                itemMap.put("quantity", item.getQuantity());
                itemMap.put("unitPrice", item.getUnitPrice());
                itemMap.put("subtotal", item.getSubtotal());
                itemMap.put("isReviewed", isRev);
                productRepository.findById(item.getProductId()).ifPresent(p -> itemMap.put("imageUrl", p.getImageUrl()));
                itemsList.add(itemMap);
            }
            map.put("items", itemsList);
            orderSummary.add(map);
        }

        Map<String, Object> responseData = new java.util.HashMap<>();
        responseData.put("invoices", invoiceSummary);
        responseData.put("orders", orderSummary);

        return ResponseEntity.ok(ApiResponse.ok(responseData));
    }

    /** POST /customers — Tạo khách hàng mới */
    @PostMapping
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Customer>> create(
            @RequestBody java.util.Map<String, String> body) {
        String phone    = body.get("phoneNumber");
        String fullName = body.get("fullName");

        if (phone == null || fullName == null) {
            throw new BusinessException("MISSING_FIELDS", "phoneNumber và fullName bắt buộc");
        }

        if (customerRepository.existsByPhoneNumber(phone)) {
            throw new BusinessException("DUPLICATE_PHONE",
                    "Số điện thoại '" + phone + "' đã được đăng ký");
        }

        Customer customer = Customer.builder()
                .phoneNumber(phone)
                .fullName(fullName)
                .email(body.get("email"))
                .address(body.get("address"))
                .provinceCode(body.get("provinceCode"))
                .gender(body.get("gender"))
                .notes(body.get("notes"))
                .isActive(true)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(customerRepository.save(customer)));
    }

    // === THÊM ENDPOINT MỚI: POST /customers/bulk ===
    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Integer>> importBulk(@RequestBody List<Customer> requests) {
        int importedCount = customerService.importBulkCustomers(requests);
        return ResponseEntity.ok(ApiResponse.ok("Import thành công " + importedCount + " khách hàng", importedCount));
    }

    /** PUT /customers/{id} — Cập nhật thông tin / Khóa tài khoản */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Customer>> update(
            @PathVariable UUID id,
            @RequestBody java.util.Map<String, Object> body) {
        
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));

        if (body.containsKey("phoneNumber") && body.get("phoneNumber") != null) {
            String newPhone = (String) body.get("phoneNumber");
            if (!newPhone.equals(customer.getPhoneNumber()) && customerRepository.existsByPhoneNumber(newPhone)) {
                throw new BusinessException("DUPLICATE_PHONE", "Số điện thoại này đã được sử dụng bởi khách hàng khác");
            }
            customer.setPhoneNumber(newPhone);
        }

        if (body.containsKey("fullName")) customer.setFullName((String) body.get("fullName"));
        if (body.containsKey("email")) customer.setEmail((String) body.get("email"));
        if (body.containsKey("address")) customer.setAddress((String) body.get("address"));
        if (body.containsKey("provinceCode")) customer.setProvinceCode((String) body.get("provinceCode"));
        
        if (body.containsKey("dateOfBirth")) {
            String dobStr = (String) body.get("dateOfBirth");
            if (dobStr != null && !dobStr.isBlank()) {
                customer.setDateOfBirth(java.time.LocalDate.parse(dobStr));
            } else {
                customer.setDateOfBirth(null);
            }
        }
        
        if (body.containsKey("gender")) customer.setGender((String) body.get("gender"));
        if (body.containsKey("notes")) customer.setNotes((String) body.get("notes"));
        if (body.containsKey("isActive")) customer.setIsActive((Boolean) body.get("isActive"));

        return ResponseEntity.ok(ApiResponse.ok("Cập nhật thành công", customerRepository.save(customer)));
    }

    /** GET /customers/{id} */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Customer>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(
                customerRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Customer", id))));
    }

    @GetMapping("/recalculate-spent-all")
    public String recalculateAll() {
        List<Customer> customers = customerRepository.findAll();
        for (Customer c : customers) {
            BigDecimal totalInvoice = invoiceRepository.findByCustomerIdOrderByCreatedAtDesc(c.getId(), PageRequest.of(0, 10000))
                    .stream().filter(i -> i.getType() == Invoice.InvoiceType.SALE)
                    .map(Invoice::getFinalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalOrder = orderRepository.findByCustomerIdOrderByCreatedAtDesc(c.getId(), PageRequest.of(0, 10000))
                    .stream().filter(o -> o.getStatus() == Order.OrderStatus.DELIVERED)
                    .map(Order::getFinalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            c.setTotalSpent(totalInvoice.add(totalOrder));
            customerRepository.save(c);
        }
        return "OK";
    }
}