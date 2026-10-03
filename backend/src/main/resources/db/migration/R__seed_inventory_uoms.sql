-- Global units of measure (DATABASE.md §5.5). Each category has one reference unit (factor 1);
-- conversions within a category go through factor_to_reference. Idempotent; rows are never deleted
-- (documents reference them) — retire a unit with is_active = false.
INSERT INTO inventory.uom_categories (code, name) VALUES
  ('UNIT', 'Unit'),
  ('WEIGHT', 'Weight'),
  ('LENGTH', 'Length'),
  ('VOLUME', 'Volume'),
  ('TIME', 'Time')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO inventory.uoms (category_id, code, name, factor_to_reference, rounding_scale)
SELECT c.id, u.code, u.name, u.factor, u.scale
  FROM (VALUES
    ('UNIT',   'EA',   'Each',         1::numeric,       0),
    ('UNIT',   'PAIR', 'Pair',         2,                0),
    ('UNIT',   'DOZ',  'Dozen',        12,               0),
    ('WEIGHT', 'KG',   'Kilogram',     1,                3),
    ('WEIGHT', 'G',    'Gram',         0.001,            0),
    ('WEIGHT', 'T',    'Tonne',        1000,             6),
    ('WEIGHT', 'LB',   'Pound',        0.45359237,       3),
    ('LENGTH', 'M',    'Metre',        1,                3),
    ('LENGTH', 'CM',   'Centimetre',   0.01,             1),
    ('LENGTH', 'MM',   'Millimetre',   0.001,            0),
    ('LENGTH', 'KM',   'Kilometre',    1000,             6),
    ('LENGTH', 'FT',   'Foot',         0.3048,           3),
    ('VOLUME', 'L',    'Litre',        1,                3),
    ('VOLUME', 'ML',   'Millilitre',   0.001,            0),
    ('VOLUME', 'M3',   'Cubic metre',  1000,             6),
    ('TIME',   'H',    'Hour',         1,                2),
    ('TIME',   'DAY',  'Day',          24,               2)
  ) AS u(category, code, name, factor, scale)
  JOIN inventory.uom_categories c ON c.code = u.category
ON CONFLICT (code) DO UPDATE
  SET name = EXCLUDED.name, factor_to_reference = EXCLUDED.factor_to_reference, rounding_scale = EXCLUDED.rounding_scale;
