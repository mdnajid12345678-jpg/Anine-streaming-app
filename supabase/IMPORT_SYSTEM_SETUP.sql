-- =============================================================================================
-- CloudStream native Import system — setup guide (human readable)
-- =============================================================================================
--
-- WHAT THIS FILE IS
--   Documentation plus the validation queries you can paste into the Supabase SQL Editor.
--   The schema itself lives in the canonical migration:
--
--       supabase/migrations/20261028_cloudstream_import.sql
--
--   Run THAT file first. It is idempotent: running it twice changes nothing. It creates only
--   new objects (no existing table is dropped, renamed or altered).
--
-- ---------------------------------------------------------------------------------------
-- STEP 1 — run the migration (you, manually)
-- ---------------------------------------------------------------------------------------
--   1. Open your Supabase project -> SQL Editor.
--   2. Create a new query, paste the full contents of
--      supabase/migrations/20261028_cloudstream_import.sql
--   3. Press Run. Expected result: "Success. No rows returned".
--
--   The migration is not executed by this repository, by CI, or by the Android app. It only
--   ever runs when you paste it yourself.
--
-- ---------------------------------------------------------------------------------------
-- STEP 2 — what the migration creates
-- ---------------------------------------------------------------------------------------
--   Tables (public schema, all with Row Level Security enabled and no client policy):
--
--     import_providers          one row per CloudStream provider that contributed data.
--                               configuration jsonb must be an object and may never contain
--                               keys such as service_role, token, password, api_key.
--     media_contents            one row per canonical work, unique on canonical_key
--                               (kind|normalized_title|year).
--     provider_contents         provider page association, unique on
--                               (content_id, provider_id, detail_url). This is what makes a
--                               second provider add a source instead of a duplicate title.
--     seasons                   per content, identified by (content_id, season_key) where
--                               season_key is the season number or 'u' for unknown.
--     episodes                  per content, identified by (content_id, dedup_key): numbered
--                               episodes by season+episode, unnumbered ones by provider url /
--                               payload / title.
--     media_sources             provider/extractor/server records. media_url is only stored
--                               when status = 'resolved', which can only come from the real
--                               loadLinks() path. resolved rows must carry a media url,
--                               unresolved rows must not.
--     import_jobs               one row per import run (id is the client generated uuid).
--     import_item_errors        per-item failures, de-duplicated per job.
--     import_duplicate_candidates  ambiguous titles kept for manual review, never auto merged.
--
--   Functions:
--     set_updated_at()                             trigger helper.
--     import_sync_upsert(payload jsonb)            transactional, idempotent upsert used by
--                                                  the Edge Function.
--     import_sync_source_upsert(content, episode,  single source upsert with upgrade rules.
--                               source)
--     Both are SECURITY DEFINER, EXECUTE is revoked from anon/authenticated/public and
--     granted only to service_role.
--
--   Indexes: unique constraints listed above plus lookup indexes on year, content type,
--   normalized title, job start time, episode order, and resolved links.
--
-- ---------------------------------------------------------------------------------------
-- STEP 3 — deploy the Edge Function (you, manually)
-- ---------------------------------------------------------------------------------------
--   Source: supabase/functions/import-sync/index.ts
--
--     supabase functions deploy import-sync
--     supabase secrets set IMPORT_CALLER_KEY="<a long random value you generate>"
--
--   Function secrets (server side only):
--     SUPABASE_SERVICE_ROLE_KEY  provided by Supabase automatically
--     IMPORT_CALLER_KEY          the device key; the Android app asks you for this value on
--                                 the device (Settings -> Import -> Edge function caller key)
--                                 and stores it locally. It is never part of a build.
--
--   If IMPORT_CALLER_KEY is missing the function answers HTTP 503 with that exact message and
--   never falls open: no unauthenticated write can succeed.
--
-- ---------------------------------------------------------------------------------------
-- STEP 4 — GitHub secrets used by the Android release build (names only)
-- ---------------------------------------------------------------------------------------
--   SUPABASE_URL              public project url, injected into BuildConfig at build time
--   SUPABASE_PUBLISHABLE_KEY  public publishable/anon key, injected into BuildConfig
--
--   SUPABASE_SERVICE_ROLE_KEY must NOT be a GitHub secret used by the app build and must never
--   reach BuildConfig: the Gradle build fails if it is present in the build environment.
--
-- ---------------------------------------------------------------------------------------
-- STEP 5 — validation queries (safe, read only)
-- --------------------------------------------------------------------------------=======
--   Run these after STEP 1. Each returns the expected rows when everything is in place.

-- 5.1 all nine tables exist
select table_name
  from information_schema.tables
 where table_schema = 'public'
   and table_name in ('import_providers','media_contents','provider_contents','seasons',
                      'episodes','media_sources','import_jobs','import_item_errors',
                      'import_duplicate_candidates')
 order by table_name;
-- expected: 9 rows

-- 5.2 row level security is on for every one of them
select tablename, rowsecurity
  from pg_tables
 where schemaname = 'public'
   and tablename in ('import_providers','media_contents','provider_contents','seasons',
                     'episodes','media_sources','import_jobs','import_item_errors',
                     'import_duplicate_candidates')
 order by tablename;
-- expected: rowsecurity = true for all 9

-- 5.3 no read or write policy exists for clients
select tablename, policyname, roles
  from pg_policies
 where schemaname = 'public'
   and policyname = 'deny_client_all';
-- expected: 9 rows, roles = {anon,authenticated}

-- 5.4 the privileged function exists and is executable by service_role only
select p.proname,
       has_function_privilege('anon', p.oid, 'execute')          as anon_can_run,
       has_function_privilege('authenticated', p.oid, 'execute') as authenticated_can_run,
       has_function_privilege('service_role', p.oid, 'execute')  as service_role_can_run
  from pg_proc p
 join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and p.proname in ('import_sync_upsert','import_sync_source_upsert');
-- expected: anon_can_run = false, authenticated_can_run = false, service_role_can_run = true

-- 5.5 a real import landed (after your first upload)
select j.id, j.status, j.started_at, j.counters,
       (select count(*) from media_contents)            as contents,
       (select count(*) from episodes)                  as episodes,
       (select count(*) from media_sources)             as sources,
       (select count(*) from media_sources
         where status = 'resolved' and media_url is not null) as resolved_links
  from import_jobs j
 order by j.started_at desc
 limit 5;

-- 5.6 no unresolved row ever pretends to hold a playable url
select count(*) as bad_rows
  from media_sources
 where status <> 'resolved' and media_url is not null;
-- expected: 0

-- ---------------------------------------------------------------------------------------
-- STEP 6 — verifying that anonymous writes really are rejected
-- ---------------------------------------------------------------------------------------
--   RLS + revoked privileges make this true by construction (5.2/5.3/5.4 above). You can
--   additionally prove it in the SQL Editor with:
--
--     set role anon;
--     insert into public.media_contents (canonical_key, title, normalized_title, content_type, tv_type)
--     values ('x','x','x','other','Others');
--     reset role;
--
--   Expected: ERROR ... permission denied for table media_contents (or row-level security).
--   Do not leave the session in role anon; the reset role line above restores it.
--
-- ---------------------------------------------------------------------------------------
-- AUTOMATED VERSION OF THE SAME CHECKS
-- ---------------------------------------------------------------------------------------
--   supabase/verify_migration.py runs the migration twice, asserts the RLS rejections above
--   and replays a realistic import payload through import_sync_upsert() (idempotency,
--   episode ordering, unresolved-url rules). It needs a throwaway PostgreSQL 16 and
--   `pip install pg8000`:
--
--     python supabase/verify_migration.py
--
--   It is a local tool; it is not required by the app or by CI.
--
-- =============================================================================================
