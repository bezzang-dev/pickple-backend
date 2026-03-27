-- Insert dummy data into p_vendors
INSERT INTO p_vendors (vendor_id, vendor_name, vendor_address, username)
VALUES
    ('123e4567-e89b-12d3-a456-426614174000', 'Vendor A', '123 Main St', 'vendor-a'),
    ('123e4567-e89b-12d3-a456-426614174001', 'Vendor B', '456 Side St', 'vendor-b'),
    ('123e4567-e89b-12d3-a456-426614174002', 'Vendor C', '789 High St', 'vendor-c');

-- Insert dummy data into p_products
INSERT INTO p_products (product_id, product_name, description, product_price, product_image, is_public, vendor_id)
VALUES
    ('223e4567-e89b-12d3-a456-426614174000', 'Product A1', 'Description for Product A1', 29.99, 'productA1.jpg', TRUE, '123e4567-e89b-12d3-a456-426614174000'),
    ('223e4567-e89b-12d3-a456-426614174001', 'Product B1', 'Description for Product B1', 49.99, 'productB1.jpg', TRUE, '123e4567-e89b-12d3-a456-426614174001'),
    ('223e4567-e89b-12d3-a456-426614174002', 'Product C1', 'Description for Product C1', 19.99, 'productC1.jpg', TRUE, '123e4567-e89b-12d3-a456-426614174002');

-- Insert dummy data into p_orders
INSERT INTO p_orders (
    order_id,
    order_status,
    amount,
    username,
    email,
    payment_id,
    payment_amount,
    payment_method,
    payment_status,
    delivery_id,
    delivery_status,
    delivery_type,
    carrier_name,
    tracking_number,
    delivery_requirement,
    recipient_name,
    recipient_address,
    recipient_contact
)
VALUES
    (
        '323e4567-e89b-12d3-a456-426614174000',
        'PENDING',
        79.98,
        'user1',
        'user1@pickple.com',
        '423e4567-e89b-12d3-a456-426614174000',
        79.98,
        'CREDIT-CARD',
        'COMPLETED',
        '523e4567-e89b-12d3-a456-426614174000',
        'PENDING',
        NULL,
        NULL,
        NULL,
        '문 앞에 놓아주세요',
        '홍길동',
        '서울시 강남구 테헤란로 1',
        '010-1111-1111'
    ),
    (
        '323e4567-e89b-12d3-a456-426614174001',
        'COMPLETED',
        99.98,
        'user2',
        'user2@pickple.com',
        '423e4567-e89b-12d3-a456-426614174001',
        99.98,
        'CREDIT-CARD',
        'COMPLETED',
        '523e4567-e89b-12d3-a456-426614174001',
        'DELIVERED',
        'COURIER',
        'CJ대한통운',
        'TRK-0001',
        '경비실에 맡겨주세요',
        '김철수',
        '서울시 서초구 서초대로 10',
        '010-2222-2222'
    ),
    (
        '323e4567-e89b-12d3-a456-426614174002',
        'CANCELED',
        19.99,
        'user3',
        'user3@pickple.com',
        '423e4567-e89b-12d3-a456-426614174002',
        NULL,
        NULL,
        NULL,
        '523e4567-e89b-12d3-a456-426614174002',
        'CANCELED',
        'COURIER',
        '한진택배',
        'TRK-0002',
        '빠른 배송 부탁드립니다',
        '이영희',
        '부산시 해운대구 센텀로 20',
        '010-3333-3333'
    );

-- Insert dummy data into p_order_details
INSERT INTO p_order_details (order_detail_id, unit_price, total_price, order_quantity, product_id, order_id)
VALUES
    -- Order 1: Two products
    ('623e4567-e89b-12d3-a456-426614174000', 29.99, 29.99, 1, '223e4567-e89b-12d3-a456-426614174000', '323e4567-e89b-12d3-a456-426614174000'),
    ('623e4567-e89b-12d3-a456-426614174003', 49.99, 49.99, 1, '223e4567-e89b-12d3-a456-426614174001', '323e4567-e89b-12d3-a456-426614174000'),

    -- Order 2: Two products
    ('623e4567-e89b-12d3-a456-426614174001', 49.99, 49.99, 1, '223e4567-e89b-12d3-a456-426614174001', '323e4567-e89b-12d3-a456-426614174001'),
    ('623e4567-e89b-12d3-a456-426614174004', 49.99, 49.99, 1, '223e4567-e89b-12d3-a456-426614174002', '323e4567-e89b-12d3-a456-426614174001'),

    -- Order 3: One product
    ('623e4567-e89b-12d3-a456-426614174002', 19.99, 19.99, 1, '223e4567-e89b-12d3-a456-426614174002', '323e4567-e89b-12d3-a456-426614174002');
