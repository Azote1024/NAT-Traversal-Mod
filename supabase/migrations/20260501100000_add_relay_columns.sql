-- Simple relay server integration columns (optional)
-- Backward compatible: existing clients can ignore these columns.

ALTER TABLE IF EXISTS public.rooms
    ADD COLUMN IF NOT EXISTS relay_endpoint text,
    ADD COLUMN IF NOT EXISTS relay_token text,
    ADD COLUMN IF NOT EXISTS relay_status text;

