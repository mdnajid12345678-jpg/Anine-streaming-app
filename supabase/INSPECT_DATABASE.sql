-- =============================================================================================
-- CloudStream import database — READ-ONLY inspection
--
-- Purpose: hand this file to anyone who should see what is (or is not) in the database.
-- Every statement is a SELECT. Nothing here inserts, updates, deletes or changes the schema,
-- so it is safe to run in the Supabase SQL Editor on the production project.
--
-- Project: ref mrhwfvpxetegxmwkdngw (https://mrhwfvpxetegxmwkdngw.supabase.co)
--
-- How to run: Supabase Dashboard -> SQL Editor -> paste this file -> Run (or run each
-- numbered query on its own). The "-- expected" comments say what a healthy setup returns.
-- =============================================================================================


-- 1. Which tables exist in the public schema, and how many rows each holds
-- ------------------------------------------------------------------------------------------
-- expected: the 9 import tables below (plus the two legacy tables kept for history).
--           Right after setup every count is 0; they grow after the first import.
select t.table_name,
       (xpath('/row/cnt/text(),
               query_to_xml(format(
                   'select count(*) as cnt from public.%I', t.table_name),
                   false, true, '')))[1]::text::bigint as row_count
  from information_schema.tables t
 where t.table_schema = 'public'
   and t.table_type = 'BASE TABLE'
 order by t.table_name;


-- 2. Row Level Security must be enabled on every import table
-- expected: rowsecurity = true for all of them
select tablename, rowsecurity
  from pg_tables
 where schemaname = 'public'
 order by tablename;


-- 3. No client role may read or write these tables directly
-- expected: 9 rows of the "deny_client_all" policy, roles = {anon,authenticated}
select tablename, policyname, roles, cmd
  from pg_policies
 where schemaname = 'public'
   and policyname = 'deny_client_all'
 order by tablename;


-- 4. The privileged upsert functions are callable by service_role only
-- expected: anon_can_run = false, authenticated_can_run = false, service_role_can_run = true
select p.proname,
       has_function_privilege('anon',          p.oid, 'execute') as anon_can_run,
       has_function_privilege('authenticated', p.oid, 'execute') as authenticated_can_run,
       has_function_privilege('service_role',  p.oid, 'execute') as service_role_can_run
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and p.proname in ('import_sync_upsert', 'import_sync_source_upsert');


-- 5. Import job history with the counters each run actually reported
-- expected: one row per scan/upload; empty until the first import has been saved
select j.id,
       j.status,
       j.started_at,
       j.finished_at,
       j.provider_ids,
       j.counters,
       left(coalesce(j.error_summary, ''), 200) as error_summary
  from public.import_jobs j
 order by j.started_at desc
 limit 20;


-- 6. Which providers contributed data, and how much
-- expected: one row per provider that has ever uploaded something
select p.provider_id,
       p.display_name,
       p.language,
       p.base_url,
       p.enabled,
       count(pc.id) as linked_titles
  from public.import_providers p
  left join public.provider_contents pc on pc.provider_id = p.provider_id
 group by p.provider_id, p.display_name, p.language, p.base_url, p.enabled
 order by linked_titles desc;


-- 7. Canonical titles stored, with their episode counts
-- expected: one row per canonical work (multi-provider titles appear ONCE, with several
--           provider rows in provider_contents - that is the de-duplication working)
select m.title,
       m.release_year,
       m.content_type,
       m.tv_type,
       (select count(*) from public.provider_contents pc where pc.content_id = m.id) as providers,
       (select count(*) from public.episodes e        where e.content_id  = m.id) as episodes,
       (select count(*) from public.media_sources s   where s.content_id  = m.id) as sources,
       m.created_at
  from public.media_contents m
 order by m.created_at desc
 limit 50;


-- 8. Season and episode ordering is preserved per title
-- expected: episodes ordered by season then episode number; no movie rows here
select m.title,
       e.season_number,
       e.episode_number,
       e.title as episode_title,
       e.sort_order
  from public.episodes e
  join public.media_contents m on m.id = e.content_id
 order by m.title, coalesce(e.season_number, 0), coalesce(e.episode_number, 0), e.sort_order
 limit 100;


-- 9. Sources: resolved playable links vs metadata-only records
-- expected: media_url is NOT NULL only when status = 'resolved'
select s.status,
       count(*)                                        as rows_count,
       count(*) filter (where s.media_url is not null) as with_media_url,
       min(s.resolved_at)                              as oldest_resolution,
       max(s.resolved_at)                              as newest_resolution
  from public.media_sources s
 group by s.status
 order by s.status;


-- 10. Integrity rule: an unresolved row must never pretend to hold a playable url
-- expected: 0
select count(*) as bad_rows
  from public.media_sources
 where status <> 'resolved' and media_url is not null;


-- 11. Per-item errors recorded during imports
-- expected: empty on a clean run; every row is a real failure with its stage
select e.created_at,
       e.provider_id,
       e.stage,
       e.provider_url,
       left(e.message, 300) as message
  from public.import_item_errors e
 order by e.created_at desc
 limit 50;


-- 12. Ambiguous titles kept for human review (never merged automatically)
-- expected: usually empty; anything here needs a decision
select d.detected_at, d.title, d.release_year, d.reason, d.resolved
  from public.import_duplicate_candidates d
 order by d.detected_at desc
 limit 50;


-- 13. Migration history recorded by the Supabase Management API
-- expected: includes cloudstream_import_20261028_repo_file (the schema in this repository)
select name, version
  from supabase_migrations.schema_migrations
 order by version desc;


-- =============================================================================================
-- What the legacy tables are
-- =============================================================================================
--   public.media_items                        older prototype table (kept, untouched)
--   public.media_sources_legacy_backup_20261025
--                                             the old media_sources, renamed aside by the
--                                             migration so the new schema could be created.
--                                             Nothing was ever dropped or truncated.
-- =============================================================================================
