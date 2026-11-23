-- Split Together schema. Run once in the Supabase SQL Editor (project klxskdrnvslhqrwmuiuu).
-- Only the account holder logs in; the people they split with are participants with an optional phone number for SMS.
-- All money is stored as integer cents. Only the Java backend reads or writes these tables.
begin;

create table public.users (
    user_id uuid primary key,
    email text not null,
    display_name text not null,
    password_hash text not null,
    expense_notifications boolean not null default true,
    monthly_summary_email boolean not null default false,
    currency text not null default 'USD',
    created_at timestamptz not null default now()
);
create unique index users_email_key on public.users (lower(email));

-- Only a SHA-256 hash of each bearer token is stored, so a database leak cannot replay sessions.
create table public.sessions (
    token_hash text primary key,
    user_id uuid not null references public.users (user_id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null
);
create index sessions_user_idx on public.sessions (user_id);

-- The account holder's people. Exactly one row per owner has is_self = true.
-- Archived participants keep their history but are hidden from pickers.
create table public.participants (
    participant_id uuid primary key,
    owner_id uuid not null references public.users (user_id) on delete cascade,
    display_name text not null,
    phone_e164 text check (phone_e164 ~ '^\+[1-9][0-9]{7,14}$'),
    is_self boolean not null default false,
    archived boolean not null default false,
    created_at timestamptz not null default now()
);
create index participants_owner_idx on public.participants (owner_id);
create unique index participants_one_self on public.participants (owner_id) where is_self;

create table public.expenses (
    expense_id uuid primary key,
    owner_id uuid not null references public.users (user_id) on delete cascade,
    description text not null,
    category text not null,
    expense_date date not null,
    amount_cents bigint not null check (amount_cents > 0),
    tax_cents bigint not null default 0 check (tax_cents >= 0),
    tip_cents bigint not null default 0 check (tip_cents >= 0),
    entry_method text not null check (entry_method in ('manual', 'receipt')),
    split_method text not null check (split_method in ('equal', 'custom', 'itemized')),
    notes text,
    created_at timestamptz not null default now()
);
create index expenses_owner_date_idx on public.expenses (owner_id, expense_date desc);

create table public.expense_items (
    item_id uuid primary key,
    expense_id uuid not null references public.expenses (expense_id) on delete cascade,
    position integer not null,
    name text not null,
    quantity integer not null check (quantity > 0),
    total_cents bigint not null check (total_cents >= 0)
);
create index expense_items_expense_idx on public.expense_items (expense_id);

create table public.item_allocations (
    item_id uuid not null references public.expense_items (item_id) on delete cascade,
    participant_id uuid not null references public.participants (participant_id) on delete cascade,
    amount_cents bigint not null check (amount_cents >= 0),
    primary key (item_id, participant_id)
);

create table public.expense_payments (
    expense_id uuid not null references public.expenses (expense_id) on delete cascade,
    participant_id uuid not null references public.participants (participant_id) on delete cascade,
    amount_cents bigint not null check (amount_cents > 0),
    primary key (expense_id, participant_id)
);

create table public.expense_shares (
    expense_id uuid not null references public.expenses (expense_id) on delete cascade,
    participant_id uuid not null references public.participants (participant_id) on delete cascade,
    amount_cents bigint not null check (amount_cents >= 0),
    primary key (expense_id, participant_id)
);
create index expense_shares_participant_idx on public.expense_shares (participant_id);

-- Payments made outside the app (Venmo, Zelle, cash). Only confirmed rows change balances.
create table public.settlements (
    settlement_id uuid primary key,
    owner_id uuid not null references public.users (user_id) on delete cascade,
    from_participant_id uuid not null references public.participants (participant_id) on delete cascade,
    to_participant_id uuid not null references public.participants (participant_id) on delete cascade,
    amount_cents bigint not null check (amount_cents > 0),
    settled_on date not null,
    method text,
    note text,
    status text not null check (status in ('pending', 'confirmed', 'rejected')),
    created_at timestamptz not null default now(),
    confirmed_at timestamptz,
    check (from_participant_id <> to_participant_id)
);
create index settlements_owner_idx on public.settlements (owner_id, created_at desc);

create table public.monthly_summaries (
    summary_id uuid primary key,
    user_id uuid not null references public.users (user_id) on delete cascade,
    month date not null,
    stats_fingerprint text not null,
    summary text not null,
    created_at timestamptz not null default now(),
    unique (user_id, month)
);

-- Per-user daily limits for paid or expensive operations (OCR, Gemini, SMS).
create table public.usage_counters (
    user_id uuid not null references public.users (user_id) on delete cascade,
    operation text not null,
    day date not null,
    used integer not null,
    primary key (user_id, operation, day)
);

-- The backend connects as the table owner, which bypasses RLS.
-- RLS with no policies plus revoked grants keeps the public Supabase API from reading anything.
do $$
declare t text;
begin
    foreach t in array array['users', 'sessions', 'participants', 'expenses', 'expense_items',
            'item_allocations', 'expense_payments', 'expense_shares', 'settlements',
            'monthly_summaries', 'usage_counters'] loop
        execute format('alter table public.%I enable row level security', t);
        execute format('revoke all on table public.%I from anon, authenticated', t);
    end loop;
end $$;

commit;
