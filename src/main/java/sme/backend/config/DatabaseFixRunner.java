package sme.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseFixRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            log.info("Checking and fixing database constraints...");
            // Drop outdated check constraints that prevent inserting new enum values
            jdbcTemplate.execute("ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check");
            jdbcTemplate.execute("ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_payment_status_check");
            jdbcTemplate.execute("ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_type_check");
            
            // Also drop constraints on the Envers audit table
            jdbcTemplate.execute("ALTER TABLE orders_audit DROP CONSTRAINT IF EXISTS orders_audit_status_check");
            jdbcTemplate.execute("ALTER TABLE orders_audit DROP CONSTRAINT IF EXISTS orders_audit_payment_status_check");
            jdbcTemplate.execute("ALTER TABLE orders_audit DROP CONSTRAINT IF EXISTS orders_audit_type_check");
            
            // Also drop constraints on order_status_history just in case
            jdbcTemplate.execute("ALTER TABLE order_status_history DROP CONSTRAINT IF EXISTS order_status_history_new_status_check");
            jdbcTemplate.execute("ALTER TABLE order_status_history DROP CONSTRAINT IF EXISTS order_status_history_old_status_check");

            // Fix purchase_orders — thêm PENDING_APPROVAL, PARTIAL_RECEIVED
            jdbcTemplate.execute("ALTER TABLE purchase_orders DROP CONSTRAINT IF EXISTS purchase_orders_status_check");
            jdbcTemplate.execute("ALTER TABLE purchase_orders ADD CONSTRAINT purchase_orders_status_check " +
                    "CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','PARTIAL_RECEIVED','COMPLETED','CANCELLED'))");

            // Fix supplier_returns — thêm PENDING_APPROVAL, SHIPPED
            jdbcTemplate.execute("ALTER TABLE supplier_returns DROP CONSTRAINT IF EXISTS supplier_returns_status_check");
            jdbcTemplate.execute("ALTER TABLE supplier_returns ADD CONSTRAINT supplier_returns_status_check " +
                    "CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','SHIPPED','CONFIRMED','CANCELLED'))");

            log.info("Successfully cleaned up outdated enum constraints in database.");

            // Phase 5: Database Index Optimization for Search Performance
            log.info("Creating database indexes for performance optimization...");
            
            // Products
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_products_search ON products (name, sku, isbn_barcode)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_products_category ON products (category_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_products_is_active ON products (is_active)");

            // Customers
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_customers_search ON customers (phone_number, full_name)");

            // Invoices
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_invoices_customer ON invoices (customer_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_invoices_code ON invoices (code)");

            // Orders
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders (customer_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_orders_code ON orders (code)");

            log.info("Successfully created database indexes.");

        } catch (Exception e) {
            log.warn("Could not execute constraint cleanup: {}", e.getMessage());
        }
    }
}
