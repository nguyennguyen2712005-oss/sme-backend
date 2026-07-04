-- Sau cải cách hành chính 2025, Việt Nam bỏ cấp quận/huyện (chỉ còn Tỉnh/Thành phố -> Phường/Xã).
-- Cột district trong customer_addresses không còn bắt buộc phải có giá trị.
ALTER TABLE customer_addresses ALTER COLUMN district DROP NOT NULL;
