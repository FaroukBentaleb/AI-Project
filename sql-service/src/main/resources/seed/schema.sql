-- Seed Corpus demo schema (Story 5.5, addendum §F). DDL plus deterministic sample data for the evaluation
-- harness (addendum §H) and the seed-query tests. NEVER loaded into sql_db: load it into a throwaway PostgreSQL.
-- Dates are relative to now() so "last week" and "last month" filters always return rows.

CREATE TABLE customers (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    country    VARCHAR(2)   NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE products (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(100)  NOT NULL,
    category VARCHAR(50)   NOT NULL,
    price    NUMERIC(10,2) NOT NULL CHECK (price >= 0)
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT        NOT NULL REFERENCES customers (id),
    region      VARCHAR(20)   NOT NULL,
    total       NUMERIC(12,2) NOT NULL DEFAULT 0,
    status      VARCHAR(20)   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL
);

CREATE TABLE order_items (
    order_id   BIGINT        NOT NULL REFERENCES orders (id),
    product_id BIGINT        NOT NULL REFERENCES products (id),
    quantity   INT           NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10,2) NOT NULL,
    PRIMARY KEY (order_id, product_id)
);

CREATE INDEX orders_created_at_idx ON orders (created_at);
CREATE INDEX orders_customer_id_idx ON orders (customer_id);

-- 40 customers; 37..40 never order (inactive customers).
INSERT INTO customers (name, country, created_at)
SELECT 'Customer ' || g,
       (ARRAY['FR', 'DE', 'ES', 'IT', 'US'])[1 + g % 5],
       now() - (200 + g * 5) * interval '1 day'
FROM generate_series(1, 40) AS g;

INSERT INTO products (name, category, price) VALUES
    ('Laptop 14"',        'electronics', 899.00),
    ('Wireless Mouse',    'electronics',  24.90),
    ('USB-C Hub',         'electronics',  39.50),
    ('Office Chair',      'furniture',   189.00),
    ('Standing Desk',     'furniture',   449.00),
    ('Desk Lamp',         'furniture',    34.00),
    ('Coffee Beans 1kg',  'grocery',      18.50),
    ('Green Tea 100 bags','grocery',       7.90),
    ('Notebook A5',       'stationery',    4.20),
    ('Gel Pens (10)',     'stationery',    6.80),
    ('Backpack',          'accessories',  59.00),
    ('Water Bottle',      'accessories',  15.00);

-- 600 orders spread over the last ~150 days, 4 regions, mostly paid/shipped/delivered, some cancelled.
INSERT INTO orders (customer_id, region, status, created_at)
SELECT 1 + (g * 7) % 36,
       (ARRAY['north', 'south', 'east', 'west'])[1 + g % 4],
       CASE WHEN g % 11 = 0 THEN 'cancelled' ELSE (ARRAY['paid', 'shipped', 'delivered'])[1 + g % 3] END,
       date_trunc('day', now()) - ((g * 13) % 150) * interval '1 day' + (g % 24) * interval '1 hour'
FROM generate_series(1, 600) AS g;

-- Three line items per order.
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
SELECT o.id, p.id, 1 + (o.id + p.id) % 3, p.price
FROM orders o
JOIN products p ON p.id % 4 = o.id % 4;

UPDATE orders o
SET total = s.amount
FROM (SELECT order_id, SUM(quantity * unit_price) AS amount FROM order_items GROUP BY order_id) s
WHERE s.order_id = o.id;
