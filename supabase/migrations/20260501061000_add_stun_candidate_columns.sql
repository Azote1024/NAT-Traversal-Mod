-- STUN拡張用の任意カラムを追加する。
-- 既存クライアントは host_ip/host_port/status/updated_at のみを参照するため後方互換を維持。

ALTER TABLE IF EXISTS public.rooms
    ADD COLUMN IF NOT EXISTS nat_method text,
    ADD COLUMN IF NOT EXISTS public_endpoint text,
    ADD COLUMN IF NOT EXISTS candidates jsonb;

