alter table public.quic_peer_attempts
    add column if not exists client_public_endpoint text not null default '',
    add column if not exists host_public_endpoint text not null default '',
    add column if not exists punch_sync_token text not null default '',
    add column if not exists punch_window_opened_at timestamp with time zone,
    add column if not exists punch_window_ms integer not null default 0,
    add column if not exists last_transition text not null default '';


