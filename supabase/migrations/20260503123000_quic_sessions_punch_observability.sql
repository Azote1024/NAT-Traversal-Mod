alter table public.quic_sessions
    add column if not exists host_probe_sent_at timestamp with time zone,
    add column if not exists attempt_id text not null default '',
    add column if not exists last_error_code text not null default '';

