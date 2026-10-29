-- =============================================================================================
-- CloudStream native Import system — request headers for resolved sources
--
-- File:      supabase/migrations/20261029_source_headers.sql
-- How to run: paste into the Supabase SQL Editor of your project and press Run, or apply it
--             through the Supabase Management API. Safe to re-run: the column is added with
--            IF NOT EXISTS semantics (guarded) and the function is CREATE OR REPLACE.
--
-- Why:       Media CDNs behind the imported links check Referer/User-Agent (hotlink
--            protection). The Android app now copies the exact headers its extractor used
--            for each resolved link, so a downstream player can repeat that request instead
--            of guessing and being blocked. Only non-secret request headers are stored.
-- =============================================================================================

begin;

-- -------------------------------------------------------------------------------------------
-- 1. New column on media_sources
-- -------------------------------------------------------------------------------------------
alter table public.media_sources
    add column if not exists headers jsonb not null default '{}'::jsonb;

do $$
begin
    if not exists (
        select 1
          from pg_constraint
         where conname = 'media_sources_headers_is_object'
           and conrelid = 'public.media_sources'::regclass
    ) then
        alter table public.media_sources
            add constraint media_sources_headers_is_object
            check (jsonb_typeof(headers) = 'object');
    end if;
end;
$$;

-- -------------------------------------------------------------------------------------------
-- 2. Source upsert now persists headers (created by 20261028_cloudstream_import.sql)
--
--    Merge rule: a new non-empty header map wins over the old one, an empty payload keeps
--    what was stored before, so a metadata-only re-import never erases known headers.
-- -------------------------------------------------------------------------------------------
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
    v_headers jsonb := coalesce(p_source -> 'headers', '{}'::jsonb);
begin
    if v_status = 'resolved' and v_media_url is null then
        raise exception 'a resolved source must carry a media_url';
    end if;
    if v_status <> 'resolved' then
        v_media_url := null; -- never persist an unresolved url as playable
    end if;
    if jsonb_typeof(v_headers) <> 'object' then
        v_headers := '{}'::jsonb;
    end if;

    insert into public.media_sources
        (content_id, episode_id, provider_id, detail_url, extractor_name, server_name,
         media_url, quality, link_type, is_resolved, resolved_at, expires_at, status, error,
         headers)
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
        p_source ->> 'error',
        v_headers
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
        headers       = case
                            when excluded.headers <> '{}'::jsonb
                                then coalesce(media_sources.headers, '{}'::jsonb) || excluded.headers
                            else coalesce(media_sources.headers, '{}'::jsonb)
                        end,
        updated_at    = now()
    returning id into v_id;

    return v_id;
end;
$$;

commit;

-- =============================================================================================
-- Validation (safe, read only)
-- =============================================================================================
-- -- 1. the column exists with a default
-- select column_name, data_type, column_default
--   from information_schema.columns
--  where table_schema = 'public' and table_name = 'media_sources' and column_name = 'headers';
--
-- -- 2. how many stored sources already carry request headers
-- select count(*) filter (where headers <> '{}'::jsonb) as with_headers,
--        count(*) as total
--   from public.media_sources;
--
-- -- 3. the constraint is present
-- select conname from pg_constraint
--  where conname = 'media_sources_headers_is_object';
-- =============================================================================================
