alter table public.rooms
    add column if not exists host_nat_type text not null default 'unknown',
    add column if not exists nat_confidence text not null default '',
    add column if not exists nat_classified_at timestamp with time zone,
    add column if not exists route_hint text not null default '';

alter table public.quic_sessions
    add column if not exists host_nat_type text not null default 'unknown',
    add column if not exists route_decision text not null default '',
    add column if not exists route_decided_at timestamp with time zone,
    add column if not exists relay_reason text not null default '';

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'rooms_host_nat_type_check'
    ) then
        alter table public.rooms
            add constraint rooms_host_nat_type_check
            check (host_nat_type in ('unknown', 'open', 'port_restricted', 'symmetric'));
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conname = 'quic_sessions_host_nat_type_check'
    ) then
        alter table public.quic_sessions
            add constraint quic_sessions_host_nat_type_check
            check (host_nat_type in ('unknown', 'open', 'port_restricted', 'symmetric'));
    end if;
end
$$;

create table if not exists public.quic_peer_attempts (
    room_name text not null,
    client_key text not null,
    attempt_id text not null,
    client_nat_type text not null default 'unknown',
    decision text not null default '',
    punch_status text not null default 'idle',
    last_error_code text not null default '',
    started_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    closed_at timestamp with time zone,
    primary key (room_name, client_key, attempt_id),
    constraint quic_peer_attempts_client_nat_type_check
        check (client_nat_type in ('unknown', 'open', 'port_restricted', 'symmetric'))
);

create index if not exists quic_peer_attempts_room_updated_idx
    on public.quic_peer_attempts (room_name, updated_at desc);

create index if not exists quic_peer_attempts_client_updated_idx
    on public.quic_peer_attempts (client_key, updated_at desc);

alter table public.quic_peer_attempts enable row level security;

create policy "public read quic_peer_attempts"
on public.quic_peer_attempts
for select
using (true);

create policy "public insert quic_peer_attempts"
on public.quic_peer_attempts
for insert
with check (true);

create policy "public update quic_peer_attempts"
on public.quic_peer_attempts
for update
using (true);

create policy "public delete quic_peer_attempts"
on public.quic_peer_attempts
for delete
using (true);

grant all on table public.quic_peer_attempts to anon;
grant all on table public.quic_peer_attempts to authenticated;
grant all on table public.quic_peer_attempts to service_role;

