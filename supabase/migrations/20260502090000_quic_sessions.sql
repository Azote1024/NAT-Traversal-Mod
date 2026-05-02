create table if not exists public.quic_sessions (
    room_name text primary key,
    quic_endpoint text not null default '',
    quic_status text not null default 'down',
    punch_endpoint text not null default '',
    punch_status text not null default 'idle',
    punch_token text not null default '',
    client_punch_sent_at timestamp with time zone,
    status text not null default 'open',
    updated_at timestamp with time zone not null default now()
);

alter table public.quic_sessions enable row level security;

create policy "public read quic_sessions"
on public.quic_sessions
for select
using (true);

create policy "public insert quic_sessions"
on public.quic_sessions
for insert
with check (true);

create policy "public update quic_sessions"
on public.quic_sessions
for update
using (true);

create policy "public delete quic_sessions"
on public.quic_sessions
for delete
using (true);

grant all on table public.quic_sessions to anon;
grant all on table public.quic_sessions to authenticated;
grant all on table public.quic_sessions to service_role;

