alter table public.quic_sessions
    add column if not exists host_public_endpoint text not null default '';

