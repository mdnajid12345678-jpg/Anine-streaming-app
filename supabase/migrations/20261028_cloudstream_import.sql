-- =============================================================================================
-- CloudStream native Import system — Supabase schema
--
-- File:      supabase/migrations/20261028_cloudstream_import.sql
-- How to run: paste into the Supabase SQL Editor of your project and press Run.
--             Nothing here drops, truncates or alters an existing unrelated table.
-- Safety:     every object is created with IF NOT EXISTS / CREATE OR REPLACE, so re-running
--             the file is safe.
--
-- Security model
--   * RLS is enabled on every table created here.
--   * No policy grants anon/authenticated anything: in-app reads and writes are refused.
--   * All writes go through public.import_sync_upsert(), whose EXECUTE privilege is revoked
--     from anon/authenticated/public and granted only to service_role, called by the
--     import-sync Edge Function. The service role key lives in Supabase function secrets and
--     must never appear in the Android app, the repository, or a build artifact.
--   * The Android client only carries SUPABASE_URL + SUPABASE_PUBLISHABLE_KEY, both public.
--
-- The payload accepted by import_sync_upsert() is snake_case JSON; the Edge Function
-- supabase/functions/import-sync converts the camelCase client payload before calling it.
-- =============================================================================================

begin;

-- gen_random_uuid() ships with pgcrypto, which Supabase already enables.
create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------------------------
-- updated_at trigger helper
-- ---------------------------------------------------------------------------------------------
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

-- ---------------------------------------------------------------------------------------------
-- 1. providers
-- ---------------------------------------------------------------------------------------------
create table if not exists public.import_providers (
    provider_id     text primary key,
    display_name    text not null,
    language        text,
    base_url        text,
    enabled         boolean not null default true,
    -- Free-form, non secret provider metadata only.
    configuration   jsonb not null default '{}'::jsonb,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint import_providers_config_is_object
        check (configuration is null or jsonb_typeof(configuration) = 'object'),
    constraint import_providers_no_secret_keys
        check (
            configuration is null
            or not (configuration ?| array[
                'service_role', 'service_role_key', 'secret', 'token',
                'password', 'api_key', 'apikey', 'access_key'
            ])
        )
);

comment on table public.import_providers is
    'CloudStream providers that contributed at least one imported title.';

-- ---------------------------------------------------------------------------------------------
-- 2. canonical content
-- ---------------------------------------------------------------------------------------------
create table if not exists public.media_contents (
    id               uuid primary key default gen_random_uuid(),
    -- Stable identity built by the app: kind|normalized_title|year
    canonical_key    text not null unique,
    title            text not null,
    normalized_title text not null,
    original_title   text,
    content_type     text not null,
    tv_type          text not null,
    release_year     integer,
    poster_url       text,
    description      text,
    genres           jsonb not null default '[]'::jsonb,
    status           text,
    rating           numeric(4,2),
    external_ids     jsonb not null default '{}'::jsonb,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint media_contents_content_type_check
        check (content_type in (
            'movie', 'anime_movie', 'series', 'anime_series', 'ova',
            'cartoon', 'asian_drama', 'documentary', 'live', 'other'
        )),
    constraint media_contents_year_check
        check (release_year is null or (release_year >= 1870 and release_year <= 2200)),
    constraint media_contents_rating_check
        check (rating is null or (rating >= 0 and rating <= 10)),
    constraint media_contents_genres_is_array
        check (jsonb_typeof(genres) = 'array'),
    constraint media_contents_external_ids_is_object
        check (jsonb_typeof(external_ids) = 'object')
);

create index if not exists media_contents_year_idx on public.media_contents (release_year);
create index if not exists media_contents_type_idx on public.media_contents (content_type);
create index if not exists media_contents_normalized_title_idx
    on public.media_contents (normalized_title);

-- ---------------------------------------------------------------------------------------------
-- 3. provider <-> content association (one row per provider page, never a duplicate title)
-- ---------------------------------------------------------------------------------------------
create table if not exists public.provider_contents (
    id                uuid primary key default gen_random_uuid(),
    content_id        uuid not null references public.media_contents (id) on delete cascade,
    provider_id       text not null references public.import_providers (provider_id) on delete restrict,
    -- The provider's own detail page url. This is NOT a media url.
    detail_url        text not null,
    last_seen_at      timestamptz not null default now(),
    provider_metadata jsonb not null default '{}'::jsonb,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint provider_contents_metadata_is_object
        check (jsonb_typeof(provider_metadata) = 'object')
);

create unique index if not exists provider_contents_unique_idx
    on public.provider_contents (content_id, provider_id, detail_url);
create index if not exists provider_contents_provider_idx
    on public.provider_contents (provider_id, last_seen_at);

-- ---------------------------------------------------------------------------------------------
-- 4. seasons
-- ---------------------------------------------------------------------------------------------
create table if not exists public.seasons (
    id            uuid primary key default gen_random_uuid(),
    content_id    uuid not null references public.media_contents (id) on delete cascade,
    season_number integer,
    name          text,
    sort_order    integer not null default 0,
    -- Null safe identity so a provider that never states a season number still upserts once.
    season_key    text generated always as (coalesce(season_number::text, 'u')) stored,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint seasons_number_check
        check (season_number is null or season_number >= 0)
);

create unique index if not exists seasons_unique_idx
    on public.seasons (content_id, season_key);

-- ---------------------------------------------------------------------------------------------
-- 5. episodes
-- ---------------------------------------------------------------------------------------------
create table if not exists public.episodes (
    id             uuid primary key default gen_random_uuid(),
    content_id     uuid not null references public.media_contents (id) on delete cascade,
    season_id      uuid references public.seasons (id) on delete cascade,
    season_number  integer,
    episode_number integer,
    title          text,
    description    text,
    poster_url     text,
    -- Provider page url for the episode, never a playable url.
    detail_url     text,
    -- The provider payload passed back to MainAPI.loadLinks(); also not a playable url.
    link_data      text,
    attributes     jsonb not null default '{}'::jsonb,
    sort_order     integer not null default 0,
    dedup_key      text generated always as (
        case
            when episode_number is not null
                then 'n:' || coalesce(season_number::text, 'u') || ':' || episode_number::text
            else 'u:' || coalesce(detail_url, link_data, title, '')
        end
    ) stored,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint episodes_attributes_is_object
        check (jsonb_typeof(attributes) = 'object'),
    constraint episodes_number_check
        check (episode_number is null or episode_number >= 0)
);

create unique index if not exists episodes_unique_idx
    on public.episodes (content_id, dedup_key);
create index if not exists episodes_content_order_idx
    on public.episodes (content_id, sort_order);

-- ---------------------------------------------------------------------------------------------
-- 6. sources / servers / resolved media links
-- ---------------------------------------------------------------------------------------------
create table if not exists public.media_sources (
    id             uuid primary key default gen_random_uuid(),
    -- Exactly one owner: a content level link (movie/live) or an episode level link.
    content_id     uuid references public.media_contents (id) on delete cascade,
    episode_id     uuid references public.episodes (id) on delete cascade,
    provider_id    text not null references public.import_providers (provider_id) on delete restrict,
    detail_url     text,
    extractor_name text,
    server_name    text,
    -- Populated ONLY when the provider's real loadLinks()/extractor path returned it.
    media_url      text,
    quality        text,
    link_type      text,
    is_resolved    boolean not null default false,
    resolved_at    timestamptz,
    expires_at     timestamptz,
    status         text not null default 'unresolved',
    error          text,
    dedup_key      text generated always as (
        provider_id || '|'
        || coalesce(content_id::text, '') || '|'
        || coalesce(episode_id::text, '') || '|'
        || coalesce(extractor_name, '') || '|'
        || coalesce(server_name, '') || '|'
        || coalesce(media_url, detail_url, '')
    ) stored,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint media_sources_owner_check
        check (content_id is not null or episode_id is not null),
    -- Episode level links are always stored together with their content, so content level
    -- aggregation never needs a join through the episode.
    constraint media_sources_episode_needs_content
        check (episode_id is null or content_id is not null),
    constraint media_sources_status_check
        check (status in ('resolved', 'unresolved', 'no_links', 'error')),
    -- A resolved row must really carry a media url, and an unresolved row must not claim one.
    constraint media_sources_resolved_needs_url
        check (status <> 'resolved' or media_url is not null),
    constraint media_sources_unresolved_never_has_url
        check (status = 'resolved' or media_url is null),
    constraint media_sources_resolved_timestamp
        check (status <> 'resolved' or resolved_at is not null)
);

create unique index if not exists media_sources_unique_idx
    on public.media_sources (dedup_key);
create index if not exists media_sources_content_idx on public.media_sources (content_id);
create index if not exists media_sources_episode_idx on public.media_sources (episode_id);
create index if not exists media_sources_resolved_idx
    on public.media_sources (resolved_at) where status = 'resolved';

-- ---------------------------------------------------------------------------------------------
-- 7. import jobs / runs
-- ---------------------------------------------------------------------------------------------
create table if not exists public.import_jobs (
    id             uuid primary key,
    provider_ids   text[] not null default '{}',
    filters        jsonb not null default '{}'::jsonb,
    status         text not null default 'running',
    started_at     timestamptz not null default now(),
    finished_at    timestamptz,
    counters       jsonb not null default '{}'::jsonb,
    error_summary  text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint import_jobs_status_check
        check (status in ('running', 'completed', 'failed', 'cancelled')),
    constraint import_jobs_filters_is_object
        check (jsonb_typeof(filters) = 'object'),
    constraint import_jobs_counters_is_object
        check (jsonb_typeof(counters) = 'object')
);

create index if not exists import_jobs_started_idx on public.import_jobs (started_at desc);

-- ---------------------------------------------------------------------------------------------
-- 8. per item errors
-- ---------------------------------------------------------------------------------------------
create table if not exists public.import_item_errors (
    id          bigint generated always as identity primary key,
    job_id      uuid not null references public.import_jobs (id) on delete cascade,
    provider_id text,
    provider_url text,
    stage       text not null,
    message     text not null,
    -- Replaying the same import payload must not duplicate its error rows.
    dedup_key   text generated always as (
        job_id::text || '|' || coalesce(provider_url, '') || '|' || stage || '|' || left(message, 200)
    ) stored,
    created_at  timestamptz not null default now(),
    constraint import_item_errors_stage_check
        check (stage in (
            'DISCOVERY', 'LOAD', 'FILTER', 'LINK_RESOLUTION',
            'SERIALIZATION', 'UPLOAD', 'PROVIDER'
        ))
);

create unique index if not exists import_item_errors_unique_idx
    on public.import_item_errors (dedup_key);
create index if not exists import_item_errors_job_idx on public.import_item_errors (job_id);

-- ---------------------------------------------------------------------------------------------
-- 9. duplicate candidates kept for review (never merged automatically)
-- ---------------------------------------------------------------------------------------------
create table if not exists public.import_duplicate_candidates (
    id                 uuid primary key default gen_random_uuid(),
    job_id             uuid references public.import_jobs (id) on delete cascade,
    canonical_key      text not null,
    title              text not null,
    release_year       integer,
    tv_type            text,
    conflicting_titles jsonb not null default '[]'::jsonb,
    reason             text not null,
    detected_at        timestamptz not null default now(),
    resolved           boolean not null default false,
    created_at         timestamptz not null default now(),
    constraint import_duplicate_candidates_titles_is_array
        check (jsonb_typeof(conflicting_titles) = 'array')
);

create unique index if not exists import_duplicate_candidates_unique_idx
    on public.import_duplicate_candidates (job_id, canonical_key, reason)
    where job_id is not null;

-- ---------------------------------------------------------------------------------------------
-- updated_at triggers (Postgres has no CREATE TRIGGER IF NOT EXISTS, so guard with DO)
-- ---------------------------------------------------------------------------------------------
do $$
declare
    t text;
begin
    foreach t in array array[
        'import_providers', 'media_contents', 'provider_contents', 'seasons',
        'episodes', 'media_sources', 'import_jobs'
    ] loop
        if not exists (
            select 1 from pg_trigger
            where tgname = t || '_set_updated_at'
              and tgrelid = ('public.' || t)::regclass
        ) then
            execute format(
                'create trigger %I before update on public.%I '
                || 'for each row execute function public.set_updated_at()',
                t || '_set_updated_at', t
            );
        end if;
    end loop;
end;
$$;

-- ---------------------------------------------------------------------------------------------
-- Row Level Security: enabled everywhere, no read or write policy for clients
-- ---------------------------------------------------------------------------------------------
do $$
declare
    t text;
begin
    foreach t in array array[
        'import_providers', 'media_contents', 'provider_contents', 'seasons',
        'episodes', 'media_sources', 'import_jobs', 'import_item_errors',
        'import_duplicate_candidates'
    ] loop
        execute format('alter table public.%I enable row level security', t);
        -- Defence in depth: clients never receive table privileges, RLS blocks them anyway.
        execute format('revoke all on public.%I from anon, authenticated', t);

        if not exists (
            select 1 from pg_policies
            where schemaname = 'public' and tablename = t
              and policyname = 'deny_client_all'
        ) then
            execute format(
                'create policy "deny_client_all" on public.%I '
                || 'for all to anon, authenticated using (false) with check (false)',
                t
        );
        end if;

        -- The Edge Function runs as service_role, which bypasses RLS; make the privilege
        -- explicit so behaviour does not depend on project default privileges.
        execute format('grant all on public.%I to service_role', t);
    end loop;
end;
$$;

-- ---------------------------------------------------------------------------------------------
-- Transaction safe, idempotent upsert used by the import-sync Edge Function
--
-- Payload (snake_case):
-- {
--   "job": {"id": uuid, "provider_ids": [], "filters": {}, "status": "completed",
--           "started_at": iso8601, "finished_at": iso8601, "counters": {}, "error_summary": null},
--   "providers": [{"provider_id", "display_name", "language", "base_url"}],
--   "contents": [{"canonical_key", "title", ..., "provider_contents": [], "seasons": [],
--                 "sources": []}],
--   "duplicate_candidates": [],
--   "item_errors": [{"job_id", "provider_id", "provider_url", "stage", "message"}]
-- }
-- ---------------------------------------------------------------------------------------------
create or replace function public.import_sync_upsert(payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_job           jsonb := payload -> 'job';
    v_job_id        uuid;
    v_contents      jsonb := coalesce(payload -> 'contents', '[]'::jsonb);
    v_providers     jsonb := coalesce(payload -> 'providers', '[]'::jsonb);
    v_errors        jsonb := coalesce(payload -> 'item_errors', '[]'::jsonb);
    v_duplicates    jsonb := coalesce(payload -> 'duplicate_candidates', '[]'::jsonb);
    v_content       jsonb;
    v_provider      jsonb;
    v_season        jsonb;
    v_episode       jsonb;
    v_source        jsonb;
    v_error         jsonb;
    v_content_id    uuid;
    v_season_id     uuid;
    v_episode_id    uuid;
    v_row           jsonb;
    i               int;
    j               int;
    k               int;
    m               int;
    n               int;
    p               int;
    upserted_contents int := 0;
    upserted_seasons  int := 0;
    upserted_episodes int := 0;
    upserted_sources  int := 0;
    upserted_links    int := 0;
    upserted_providers int := 0;
begin
    if payload is null or jsonb_typeof(payload) <> 'object' then
        raise exception 'payload must be a JSON object';
    end if;
    if v_job is null or jsonb_typeof(v_job) <> 'object' then
        raise exception 'payload.job is required';
    end if;

    v_job_id := (v_job ->> 'id')::uuid;
    if v_job_id is null then
        raise exception 'payload.job.id must be a uuid';
    end if;

    -- job (idempotent on the client generated id)
    insert into public.import_jobs
        (id, provider_ids, filters, status, started_at, finished_at, counters, error_summary)
    values (
        v_job_id,
        coalesce(
            (select array_agg(x) from jsonb_array_elements_text(
                coalesce(v_job -> 'provider_ids', '[]'::jsonb)) as x),
            '{}'::text[]
        ),
        coalesce(v_job -> 'filters', '{}'::jsonb),
        coalesce(v_job ->> 'status', 'completed'),
        coalesce((v_job ->> 'started_at')::timestamptz, now()),
        (v_job ->> 'finished_at')::timestamptz,
        coalesce(v_job -> 'counters', '{}'::jsonb),
        v_job ->> 'error_summary'
    )
    on conflict (id) do update set
        status        = excluded.status,
        finished_at   = excluded.finished_at,
        counters      = excluded.counters,
        error_summary = excluded.error_summary,
        updated_at    = now();

    -- providers
    for i in 0 .. jsonb_array_length(v_providers) - 1 loop
        v_provider := v_providers -> i;
        insert into public.import_providers
            (provider_id, display_name, language, base_url, enabled)
        values (
            v_provider ->> 'provider_id',
            coalesce(v_provider ->> 'display_name', v_provider ->> 'provider_id'),
            v_provider ->> 'language',
            v_provider ->> 'base_url',
            true
        )
        on conflict (provider_id) do update set
            display_name = excluded.display_name,
            language     = coalesce(excluded.language, import_providers.language),
            base_url     = coalesce(excluded.base_url, import_providers.base_url),
            enabled      = true,
            updated_at   = now();
        upserted_providers := upserted_providers + 1;
    end loop;

    -- contents
    for i in 0 .. jsonb_array_length(v_contents) - 1 loop
        v_content := v_contents -> i;

        insert into public.media_contents
            (canonical_key, title, normalized_title, original_title, content_type, tv_type,
             release_year, poster_url, description, genres, status, rating, external_ids)
        values (
            v_content ->> 'canonical_key',
            v_content ->> 'title',
            coalesce(v_content ->> 'normalized_title', lower(v_content ->> 'title')),
            v_content ->> 'original_title',
            coalesce(v_content ->> 'content_type', 'other'),
            coalesce(v_content ->> 'tv_type', 'Others'),
            (v_content ->> 'release_year')::int,
            v_content ->> 'poster_url',
            v_content ->> 'description',
            coalesce(v_content -> 'genres', '[]'::jsonb),
            v_content ->> 'status',
            (v_content ->> 'rating')::numeric,
            coalesce(v_content -> 'external_ids', '{}'::jsonb)
        )
        on conflict (canonical_key) do update set
            title           = excluded.title,
            original_title  = coalesce(excluded.original_title, media_contents.original_title),
            content_type    = excluded.content_type,
            tv_type         = excluded.tv_type,
            release_year    = coalesce(excluded.release_year, media_contents.release_year),
            poster_url      = coalesce(excluded.poster_url, media_contents.poster_url),
            description     = coalesce(excluded.description, media_contents.description),
            genres          = (
                select jsonb_agg(distinct t.v)
                from jsonb_array_elements(
                    media_contents.genres || excluded.genres
                ) as t(v)
            ),
            status          = coalesce(excluded.status, media_contents.status),
            rating          = coalesce(excluded.rating, media_contents.rating),
            external_ids    = media_contents.external_ids || excluded.external_ids,
            updated_at      = now()
        returning id into v_content_id;

        upserted_contents := upserted_contents + 1;

        -- provider associations
        for j in 0 .. jsonb_array_length(coalesce(v_content -> 'provider_contents', '[]'::jsonb)) - 1 loop
            v_provider := v_content -> 'provider_contents' -> j;
            insert into public.provider_contents
                (content_id, provider_id, detail_url, last_seen_at, provider_metadata)
            values (
                v_content_id,
                v_provider ->> 'provider_id',
                v_provider ->> 'detail_url',
                coalesce((v_provider ->> 'last_seen_at')::timestamptz, now()),
                coalesce(v_provider -> 'provider_metadata', '{}'::jsonb)
            )
            on conflict (content_id, provider_id, detail_url) do update set
                last_seen_at      = greatest(provider_contents.last_seen_at, excluded.last_seen_at),
                provider_metadata = provider_contents.provider_metadata || excluded.provider_metadata,
                updated_at        = now();
        end loop;

        -- seasons and episodes
        for j in 0 .. jsonb_array_length(coalesce(v_content -> 'seasons', '[]'::jsonb)) - 1 loop
            v_season := v_content -> 'seasons' -> j;

            insert into public.seasons (content_id, season_number, name, sort_order)
            values (
                v_content_id,
                (v_season ->> 'season_number')::int,
                v_season ->> 'name',
                coalesce((v_season ->> 'sort_order')::int, 0)
            )
            on conflict (content_id, season_key) do update set
                name       = coalesce(excluded.name, seasons.name),
                sort_order = least(seasons.sort_order, excluded.sort_order),
                updated_at = now()
            returning id into v_season_id;

            upserted_seasons := upserted_seasons + 1;

            for k in 0 .. jsonb_array_length(coalesce(v_season -> 'episodes', '[]'::jsonb)) - 1 loop
                v_episode := v_season -> 'episodes' -> k;

                insert into public.episodes
                    (content_id, season_id, season_number, episode_number, title, description,
                     poster_url, detail_url, link_data, attributes, sort_order)
                values (
                    v_content_id,
                    v_season_id,
                    coalesce((v_episode ->> 'season_number')::int, (v_season ->> 'season_number')::int),
                    (v_episode ->> 'episode_number')::int,
                    v_episode ->> 'title',
                    v_episode ->> 'description',
                    v_episode ->> 'poster_url',
                    v_episode ->> 'detail_url',
                    v_episode ->> 'link_data',
                    coalesce(v_episode -> 'attributes', '{}'::jsonb),
                    coalesce((v_episode ->> 'sort_order')::int, 0)
                )
                on conflict (content_id, dedup_key) do update set
                    title        = coalesce(excluded.title, episodes.title),
                    description  = coalesce(excluded.description, episodes.description),
                    poster_url   = coalesce(excluded.poster_url, episodes.poster_url),
                    detail_url   = coalesce(excluded.detail_url, episodes.detail_url),
                    link_data    = coalesce(excluded.link_data, episodes.link_data),
                    season_id    = coalesce(excluded.season_id, episodes.season_id),
                    sort_order   = least(episodes.sort_order, excluded.sort_order),
                    updated_at   = now()
                returning id into v_episode_id;

                upserted_episodes := upserted_episodes + 1;

                for m in 0 .. jsonb_array_length(coalesce(v_episode -> 'sources', '[]'::jsonb)) - 1 loop
                    v_source := v_episode -> 'sources' -> m;
                    perform public.import_sync_source_upsert(
                        v_content_id, v_episode_id, v_source
                    );
                    upserted_sources := upserted_sources + 1;
                    if (v_source ->> 'is_resolved')::boolean is true then
                        upserted_links := upserted_links + 1;
                    end if;
                end loop;
            end loop;
        end loop;

        -- content level sources (movies, live streams)
        for n in 0 .. jsonb_array_length(coalesce(v_content -> 'sources', '[]'::jsonb)) - 1 loop
            v_source := v_content -> 'sources' -> n;
            perform public.import_sync_source_upsert(v_content_id, null, v_source);
            upserted_sources := upserted_sources + 1;
            if (v_source ->> 'is_resolved')::boolean is true then
                upserted_links := upserted_links + 1;
            end if;
        end loop;
    end loop;

    -- per item errors
    for p in 0 .. jsonb_array_length(v_errors) - 1 loop
        v_error := v_errors -> p;
        insert into public.import_item_errors (job_id, provider_id, provider_url, stage, message)
        values (
            v_job_id,
            v_error ->> 'provider',
            v_error ->> 'url',
            v_error ->> 'stage',
            left(coalesce(v_error ->> 'message', 'unknown error'), 1000)
        )
        on conflict (dedup_key) do nothing;
    end loop;

    -- duplicate candidates (insert only, never merged automatically)
    for p in 0 .. jsonb_array_length(v_duplicates) - 1 loop
        v_row := v_duplicates -> p;
        insert into public.import_duplicate_candidates
            (job_id, canonical_key, title, release_year, tv_type, conflicting_titles, reason)
        values (
            v_job_id,
            v_row ->> 'canonical_key',
            left(v_row ->> 'title', 500),
            (v_row ->> 'release_year')::int,
            v_row ->> 'tv_type',
            coalesce(v_row -> 'conflicting_titles', '[]'::jsonb),
            coalesce(v_row ->> 'reason', 'ambiguous_title')
        )
        on conflict (job_id, canonical_key, reason) where job_id is not null
        do nothing;
    end loop;

    return jsonb_build_object(
        'job', v_job_id,
        'upserted_providers', upserted_providers,
        'upserted_contents', upserted_contents,
        'upserted_seasons', upserted_seasons,
        'upserted_episodes', upserted_episodes,
        'upserted_sources', upserted_sources,
        'upserted_links', upserted_links
    );
end;
$$;

-- Helper used above; kept separate so source upserts stay readable and idempotent.
create or replace function public.import_sync_source_upsert(
    p_content_id uuid,
    p_episode_id uuid,
    p_source      jsonb
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    v_id uuid;
    v_status text := coalesce(p_source ->> 'status', 'unresolved');
    v_media_url text := p_source ->> 'media_url';
begin
    if v_status = 'resolved' and v_media_url is null then
        raise exception 'a resolved source must carry a media_url';
    end if;
    if v_status <> 'resolved' then
        v_media_url := null; -- never persist an unresolved url as playable
    end if;

    insert into public.media_sources
        (content_id, episode_id, provider_id, detail_url, extractor_name, server_name,
         media_url, quality, link_type, is_resolved, resolved_at, expires_at, status, error)
    values (
        p_content_id,
        p_episode_id,
        p_source ->> 'provider_id',
        p_source ->> 'detail_url',
        p_source ->> 'extractor_name',
        p_source ->> 'server_name',
        v_media_url,
        p_source ->> 'quality',
        p_source ->> 'link_type',
        coalesce((p_source ->> 'is_resolved')::boolean, false),
        case when v_status = 'resolved'
             then coalesce((p_source ->> 'resolved_at')::timestamptz, now())
        end,
        (p_source ->> 'expires_at')::timestamptz,
        v_status,
        p_source ->> 'error'
    )
    on conflict (dedup_key) do update set
        -- A later successful resolution upgrades an earlier failure, never the other way round.
        is_resolved   = media_sources.is_resolved or excluded.is_resolved,
        status        = case
                            when excluded.is_resolved then 'resolved'
                            when media_sources.is_resolved then media_sources.status
                            else excluded.status
                        end,
        media_url     = coalesce(excluded.media_url, media_sources.media_url),
        resolved_at   = coalesce(excluded.resolved_at, media_sources.resolved_at),
        quality       = coalesce(excluded.quality, media_sources.quality),
        link_type     = coalesce(excluded.link_type, media_sources.link_type),
        error         = case when excluded.is_resolved then null else excluded.error end,
        updated_at    = now()
    returning id into v_id;

    return v_id;
end;
$$;

-- Privileged execution only: the client can never call these directly.
revoke all on function public.import_sync_upsert(jsonb) from public, anon, authenticated;
revoke all on function public.import_sync_source_upsert(uuid, uuid, jsonb) from public, anon, authenticated;
grant execute on function public.import_sync_upsert(jsonb) to service_role;
grant execute on function public.import_sync_source_upsert(uuid, uuid, jsonb) to service_role;

commit;

-- =============================================================================================
-- Post-run validation (safe to run any time)
-- =============================================================================================
-- select table_name from information_schema.tables
--  where table_schema = 'public'
--    and table_name in ('import_providers','media_contents','provider_contents','seasons',
--                       'episodes','media_sources','import_jobs','import_item_errors',
--                       'import_duplicate_candidates')
--  order by table_name;
--
-- select tablename, rowsecurity from pg_tables
--  where schemaname = 'public' and tablename like 'import%' or tablename like 'media%';
--
-- -- RLS must block anonymous writes (expect: permission denied / new row violates row-level)
-- -- Run in the SQL Editor only after setting: set role anon;
-- -- insert into public.media_contents (canonical_key, title, normalized_title, content_type, tv_type)
-- -- values ('x','x','x','other','Others');
-- -- reset role;
-- =============================================================================================
