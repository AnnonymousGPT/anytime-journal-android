-- Optional free Supabase backend for Anytime Journal collab.
-- Create these tables in a Supabase project, then enable Realtime for collab_entries.

create table if not exists public.collab_entries (
  id uuid primary key default gen_random_uuid(),
  source_id text not null,
  author text not null check (author ~ '^@[a-z0-9_-]{2,32}$'),
  body text not null,
  text text not null,
  kind text not null default 'collab' check (kind in ('collab', 'journal', 'idea', 'task')),
  created_at_millis bigint not null,
  inserted_at timestamptz not null default now()
);

create table if not exists public.collab_presence (
  source_id text primary key,
  profile text not null check (profile ~ '^@[a-z0-9_-]{2,32}$'),
  last_seen_millis bigint not null,
  updated_at timestamptz not null default now()
);

create table if not exists public.collab_profiles (
  profile text primary key check (profile ~ '^@[a-z0-9_-]{2,32}$'),
  display_name text not null check (length(display_name) between 2 and 40),
  last_source_id text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.collab_voice_chunks (
  id uuid primary key default gen_random_uuid(),
  call_id text not null check (length(call_id) between 8 and 120),
  source_id text not null,
  author text not null check (author ~ '^@[a-z0-9_-]{2,32}$'),
  target text not null check (target ~ '^@[a-z0-9_-]{2,32}$'),
  chunk_index bigint not null,
  audio_b64 text not null,
  created_at_millis bigint not null,
  inserted_at timestamptz not null default now()
);

create table if not exists public.collab_call_signals (
  id uuid primary key default gen_random_uuid(),
  call_id text not null check (length(call_id) between 8 and 120),
  source_id text not null,
  author text not null check (author ~ '^@[a-z0-9_-]{2,32}$'),
  target text not null check (target ~ '^@[a-z0-9_-]{2,32}$'),
  type text not null check (type in ('offer', 'answer', 'candidate', 'renegotiate_offer', 'renegotiate_answer', 'end')),
  payload jsonb not null default '{}'::jsonb,
  created_at_millis bigint not null,
  inserted_at timestamptz not null default now()
);

create table if not exists public.collab_call_transcripts (
  id uuid primary key default gen_random_uuid(),
  call_id text not null check (length(call_id) between 8 and 120),
  source_id text not null,
  author text not null check (author ~ '^@[a-z0-9_-]{2,32}$'),
  target text not null check (target ~ '^@[a-z0-9_-]{2,32}$'),
  transcript text not null,
  is_final boolean not null default false,
  sequence bigint not null default 0,
  created_at_millis bigint not null,
  inserted_at timestamptz not null default now()
);

create table if not exists public.collab_typing_events (
  id uuid primary key default gen_random_uuid(),
  source_id text not null,
  author text not null check (author ~ '^@[a-z0-9_-]{2,32}$'),
  target text not null check (target ~ '^@[a-z0-9_-]{2,32}$'),
  typing boolean not null default true,
  created_at_millis bigint not null,
  inserted_at timestamptz not null default now()
);

alter table public.collab_entries enable row level security;
alter table public.collab_presence enable row level security;
alter table public.collab_profiles enable row level security;
alter table public.collab_voice_chunks enable row level security;
alter table public.collab_call_signals enable row level security;
alter table public.collab_call_transcripts enable row level security;
alter table public.collab_typing_events enable row level security;

drop policy if exists "read collab entries" on public.collab_entries;
create policy "read collab entries"
on public.collab_entries for select
to anon
using (true);

drop policy if exists "insert collab entries" on public.collab_entries;
create policy "insert collab entries"
on public.collab_entries for insert
to anon
with check (true);

drop policy if exists "read collab presence" on public.collab_presence;
create policy "read collab presence"
on public.collab_presence for select
to anon
using (true);

drop policy if exists "upsert collab presence" on public.collab_presence;
drop policy if exists "insert collab presence" on public.collab_presence;
create policy "insert collab presence"
on public.collab_presence for insert
to anon
with check (true);

drop policy if exists "update collab presence" on public.collab_presence;
create policy "update collab presence"
on public.collab_presence for update
to anon
using (true)
with check (true);

drop policy if exists "read collab profiles" on public.collab_profiles;
create policy "read collab profiles"
on public.collab_profiles for select
to anon
using (true);

drop policy if exists "insert collab profiles" on public.collab_profiles;
create policy "insert collab profiles"
on public.collab_profiles for insert
to anon
with check (true);

drop policy if exists "update collab profiles" on public.collab_profiles;
create policy "update collab profiles"
on public.collab_profiles for update
to anon
using (true)
with check (true);

drop policy if exists "read collab voice chunks" on public.collab_voice_chunks;
create policy "read collab voice chunks"
on public.collab_voice_chunks for select
to anon
using (true);

drop policy if exists "insert collab voice chunks" on public.collab_voice_chunks;
create policy "insert collab voice chunks"
on public.collab_voice_chunks for insert
to anon
with check (
  source_id is not null
  and length(trim(source_id)) between 3 and 128
  and author ~ '^@[a-z0-9_-]{2,32}$'
  and target ~ '^@[a-z0-9_-]{2,32}$'
  and length(trim(audio_b64)) between 1 and 12000
  and chunk_index >= 0
  and created_at_millis > 0
);

drop policy if exists "read collab call signals" on public.collab_call_signals;
create policy "read collab call signals"
on public.collab_call_signals for select
to anon
using (true);

drop policy if exists "insert collab call signals" on public.collab_call_signals;
create policy "insert collab call signals"
on public.collab_call_signals for insert
to anon
with check (
  source_id is not null
  and length(trim(source_id)) between 3 and 128
  and author ~ '^@[a-z0-9_-]{2,32}$'
  and target ~ '^@[a-z0-9_-]{2,32}$'
  and type in ('offer', 'answer', 'candidate', 'renegotiate_offer', 'renegotiate_answer', 'end')
  and created_at_millis > 0
);

drop policy if exists "read collab call transcripts" on public.collab_call_transcripts;
create policy "read collab call transcripts"
on public.collab_call_transcripts for select
to anon
using (true);

drop policy if exists "insert collab call transcripts" on public.collab_call_transcripts;
create policy "insert collab call transcripts"
on public.collab_call_transcripts for insert
to anon
with check (
  source_id is not null
  and length(trim(source_id)) between 3 and 128
  and author ~ '^@[a-z0-9_-]{2,32}$'
  and target ~ '^@[a-z0-9_-]{2,32}$'
  and length(trim(transcript)) between 1 and 8000
  and sequence >= 0
  and created_at_millis > 0
);

drop policy if exists "read collab typing events" on public.collab_typing_events;
create policy "read collab typing events"
on public.collab_typing_events for select
to anon
using (true);

drop policy if exists "insert collab typing events" on public.collab_typing_events;
create policy "insert collab typing events"
on public.collab_typing_events for insert
to anon
with check (
  source_id is not null
  and length(trim(source_id)) between 3 and 128
  and author ~ '^@[a-z0-9_-]{2,32}$'
  and target ~ '^@[a-z0-9_-]{2,32}$'
  and author <> target
  and created_at_millis > 0
);

create or replace function public.set_collab_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists set_collab_profiles_updated_at on public.collab_profiles;
create trigger set_collab_profiles_updated_at
before update on public.collab_profiles
for each row execute function public.set_collab_updated_at();

drop trigger if exists set_collab_presence_updated_at on public.collab_presence;
create trigger set_collab_presence_updated_at
before update on public.collab_presence
for each row execute function public.set_collab_updated_at();

grant select, insert on public.collab_entries to anon;
grant select, insert, update on public.collab_presence to anon;
grant select, insert, update on public.collab_profiles to anon;
grant select, insert on public.collab_voice_chunks to anon;
grant select, insert on public.collab_call_signals to anon;
grant select, insert on public.collab_call_transcripts to anon;
grant select, insert on public.collab_typing_events to anon;
revoke execute on function public.set_collab_updated_at() from anon, authenticated, public;

create index if not exists collab_voice_chunks_target_author_created_idx
on public.collab_voice_chunks (target, author, created_at_millis);

create index if not exists collab_voice_chunks_call_idx
on public.collab_voice_chunks (call_id, chunk_index);

create index if not exists collab_call_signals_target_author_created_idx
on public.collab_call_signals (target, author, created_at_millis);

create index if not exists collab_call_signals_call_idx
on public.collab_call_signals (call_id, created_at_millis);

create index if not exists collab_call_transcripts_target_author_created_idx
on public.collab_call_transcripts (target, author, created_at_millis);

create index if not exists collab_call_transcripts_call_idx
on public.collab_call_transcripts (call_id, sequence, created_at_millis);

create index if not exists collab_typing_events_target_author_created_idx
on public.collab_typing_events (target, author, created_at_millis);

do $$
begin
  alter publication supabase_realtime add table public.collab_entries;
exception
  when duplicate_object then null;
  when undefined_object then null;
end;
$$;
