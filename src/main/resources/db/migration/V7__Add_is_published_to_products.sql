-- Add is_published flag to products table
ALTER TABLE products ADD COLUMN IF NOT EXISTS is_published BOOLEAN NOT NULL DEFAULT false;

-- Preserve existing behavior: all currently active products stay visible on web
UPDATE products SET is_published = true WHERE is_active = true;

-- Add to audit table (required by Hibernate Envers)
ALTER TABLE products_audit ADD COLUMN IF NOT EXISTS is_published BOOLEAN;
