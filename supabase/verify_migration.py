#!/usr/bin/env python3
"""Local verification of the import-system Supabase migrations.

Runs every migration twice (idempotency), checks client roles are refused, and exercises both
the import upsert and selected-provider playable-link replacement paths.
"""
import json
import sys
from pathlib import Path

import pg8000.native

MIGRATIONS = [
    Path(__file__).with_name("migrations") / "20261028_cloudstream_import.sql",
    Path(__file__).with_name("migrations") / "20261029_source_headers.sql",
    Path(__file__).with_name("migrations") / "20261031_provider_link_refresh.sql",
]
CONN = dict(host="127.0.0.1", port=5433, user="postgres", database="postgres")

failures = []


def check(name, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {name}" + (f" — {detail}" if detail else ""))
    if not condition:
        failures.append(name)


def scalar(con, sql, **params):
    rows = con.run(sql, **params)
    return rows[0][0] if rows and rows[0] else None


def upsert(con, payload):
    """Call the privileged upsert with a jsonb payload (pg8000 takes keyword params)."""
    rows = con.run(
        "select public.import_sync_upsert(:payload::jsonb)",
        payload=json.dumps(payload),
    )
    value = rows[0][0]
    return value if isinstance(value, dict) else json.loads(str(value))


def replace_links(con, provider_id, contents):
    rows = con.run(
        "select public.import_sync_replace_provider_links(:provider_id, :contents::jsonb)",
        provider_id=provider_id,
        contents=json.dumps(contents),
    )
    value = rows[0][0]
    return value if isinstance(value, dict) else json.loads(str(value))


def expect_error(con, name, sql, *args):
    try:
        con.run(sql)
        check(name, False, "statement unexpectedly succeeded")
    except Exception as exc:  # noqa: BLE001 - we assert on the failure itself
        check(name, True, str(exc).splitlines()[0][:90])


def main():
    migrations = [(path.name, path.read_text()) for path in MIGRATIONS]

    con = pg8000.native.Connection(**CONN)
    con.run("set client_min_messages to warning;")

    # Supabase-like roles
    con.run(
        """
        do $$
        begin
          if not exists (select 1 from pg_roles where rolname='anon') then
            create role anon nologin;
          end if;
          if not exists (select 1 from pg_roles where rolname='authenticated') then
            create role authenticated nologin;
          end if;
          if not exists (select 1 from pg_roles where rolname='service_role') then
            create role service_role nologin;
          end if;
        end $$;
        """
    )
    con.run("grant usage on schema public to anon, authenticated, service_role;")

    for name, sql in migrations:
        print(f"--- running {name} (1st time) ---")
        con.run(sql)
        check(f"{name} runs without error", True)

    for name, sql in migrations:
        print(f"--- running {name} again (idempotency) ---")
        con.run(sql)
        check(f"{name} re-runs without error", True)

    tables = [
        r[0]
        for r in con.run(
            "select table_name from information_schema.tables where table_schema='public' "
            "and table_name in ('import_providers','media_contents','provider_contents',"
            "'seasons','episodes','media_sources','import_jobs','import_item_errors',"
            "'import_duplicate_candidates') order by table_name"
        )
    ]
    check(
        "all nine tables exist",
        len(tables) == 9,
        f"found {len(tables)}: {', '.join(tables)}",
    )

    secured = [
        r[0]
        for r in con.run(
            "select tablename from pg_tables where schemaname='public' and rowsecurity "
            "and tablename in ('import_providers','media_contents','provider_contents',"
            "'seasons','episodes','media_sources','import_jobs','import_item_errors',"
            "'import_duplicate_candidates')"
        )
    ]
    check("RLS enabled on all nine tables", len(secured) == 9, f"{len(secured)} secured")

    # ---------------------------------------------------------------- anon / authenticated
    con.run("set role anon;")
    expect_error(
        con,
        "anon insert into media_contents is rejected",
        "insert into public.media_contents (canonical_key,title,normalized_title,content_type,tv_type)"
        " values ('x','x','x','other','Others')",
    )
    expect_error(
        con,
        "anon select from media_contents is rejected",
        "select * from public.media_contents limit 1",
    )
    expect_error(
        con,
        "anon call of import_sync_upsert is rejected",
        "select public.import_sync_upsert('{}'::jsonb)",
    )
    expect_error(
        con,
        "anon call of provider link replacement is rejected",
        "select public.import_sync_replace_provider_links('ProviderA', '[]'::jsonb)",
    )
    con.run("reset role;")

    con.run("set role authenticated;")
    expect_error(
        con,
        "authenticated insert into import_jobs is rejected",
        "insert into public.import_jobs (id) values (gen_random_uuid())",
    )
    con.run("reset role;")

    # Keep the verifier repeatable when run against a persistent local database.
    con.run(
        "truncate table public.import_duplicate_candidates, public.import_item_errors, "
        "public.import_jobs, public.media_sources, public.episodes, public.seasons, "
        "public.provider_contents, public.media_contents, public.import_providers cascade"
    )

    # ---------------------------------------------------------------- privileged upsert
    payload = {
        "job": {
            "id": "11111111-2222-3333-4444-555555555555",
            "provider_ids": ["ProviderA"],
            "filters": {"categories": "ANIME"},
            "status": "completed",
            "started_at": "2026-10-28T10:00:00Z",
            "finished_at": "2026-10-28T10:05:00Z",
            "counters": {"loaded": 2},
            "error_summary": None,
        },
        "providers": [
            {
                "provider_id": "ProviderA",
                "display_name": "Provider A",
                "language": "hi",
                "base_url": "https://a.example",
            }
        ],
        "contents": [
            {
                "canonical_key": "series|naruto|2002",
                "title": "Naruto",
                "normalized_title": "naruto",
                "content_type": "series",
                "tv_type": "Anime",
                "release_year": 2002,
                "genres": ["Action"],
                "external_ids": {},
                "updated_at": "2026-10-28T10:05:00Z",
                "provider_contents": [
                    {
                        "provider_id": "ProviderA",
                        "detail_url": "https://a.example/naruto",
                        "last_seen_at": "2026-10-28T10:05:00Z",
                        "provider_metadata": {"api_name": "ProviderA"},
                    }
                ],
                "seasons": [
                    {
                        "season_number": 1,
                        "name": None,
                        "sort_order": 0,
                        "episodes": [
                            {
                                "season_number": 1,
                                "episode_number": 1,
                                "title": "Enter Naruto",
                                "detail_url": None,
                                "link_data": "payload-1",
                                "attributes": {"variant": "sub"},
                                "sort_order": 0,
                                "sources": [
                                    {
                                        "provider_id": "ProviderA",
                                        "extractor_name": "FakeExtractor",
                                        "server_name": "Server 1",
                                        "media_url": "https://cdn.example/1.m3u8",
                                        "quality": "1080",
                                        "link_type": "M3U8",
                                        "is_resolved": True,
                                        "resolved_at": "2026-10-28T10:04:00Z",
                                        "status": "resolved",
                                    },
                                    {
                                        "provider_id": "ProviderA",
                                        "extractor_name": "BrokenExtractor",
                                        "is_resolved": False,
                                        "status": "error",
                                        "error": "extractor timeout",
                                    },
                                ],
                            },
                            {
                                "season_number": 1,
                                "episode_number": 2,
                                "title": "My Name is Konohamaru",
                                "link_data": "payload-2",
                                "attributes": {},
                                "sort_order": 1,
                                "sources": [],
                            },
                        ],
                    }
                ],
                "sources": [],
            }
        ],
        "item_errors": [
            {
                "provider": "ProviderA",
                "url": "https://a.example/broken",
                "stage": "LOAD",
                "message": "load() returned null",
            }
        ],
        "duplicate_candidates": [
            {
                "canonical_key": "series|naruto|2002",
                "title": "Ambiguous match example",
                "release_year": 2002,
                "tv_type": "anime_series",
                "conflicting_titles": ["Naruto"],
                "reason": "ambiguous_title",
            }
        ],
    }

    svc = pg8000.native.Connection(**CONN)
    svc.run("set role service_role;")
    first = upsert(svc, payload)
    svc.run("reset role;")

    check("upsert returns job id", first.get("job") == payload["job"]["id"], str(first))
    check("upsert reports 1 content", first.get("upserted_contents") == 1, str(first))
    check("upsert reports 2 episodes", first.get("upserted_episodes") == 2, str(first))
    check("upsert reports 2 sources", first.get("upserted_sources") == 2, str(first))
    check("upsert reports 1 resolved link", first.get("upserted_links") == 1, str(first))

    counts_after_first = {
        "media_contents": scalar(con, "select count(*) from public.media_contents"),
        "episodes": scalar(con, "select count(*) from public.episodes"),
        "media_sources": scalar(con, "select count(*) from public.media_sources"),
        "provider_contents": scalar(con, "select count(*) from public.provider_contents"),
        "import_item_errors": scalar(con, "select count(*) from public.import_item_errors"),
        "jobs": scalar(con, "select count(*) from public.import_jobs"),
    }

    # ---------------------------------------------------------------- idempotency
    svc.run("set role service_role;")
    upsert(svc, payload)
    svc.run("reset role;")

    counts_after_second = {
        "media_contents": scalar(con, "select count(*) from public.media_contents"),
        "episodes": scalar(con, "select count(*) from public.episodes"),
        "media_sources": scalar(con, "select count(*) from public.media_sources"),
        "provider_contents": scalar(con, "select count(*) from public.provider_contents"),
        "import_item_errors": scalar(con, "select count(*) from public.import_item_errors"),
        "jobs": scalar(con, "select count(*) from public.import_jobs"),
    }
    check(
        "re-running the same payload creates no duplicates",
        counts_after_first == counts_after_second,
        json.dumps(counts_after_second),
    )

    # ---------------------------------------------------------------- data integrity
    resolved_urls = scalar(
        con, "select count(*) from public.media_sources where status='resolved' and media_url is not null"
    )
    unresolved_with_url = scalar(
        con,
        "select count(*) from public.media_sources where status<>'resolved' and media_url is not null",
    )
    check("exactly one resolved link with a media url", resolved_urls == 1, str(resolved_urls))
    check("no unresolved row carries a media url", unresolved_with_url == 0, str(unresolved_with_url))

    # ------------------------------------------------ provider-specific link replacement
    content_id = scalar(
        con,
        "select id::text from public.media_contents where canonical_key='series|naruto|2002'",
    )
    episode_id = scalar(
        con,
        "select id::text from public.episodes where content_id=(select id from public.media_contents "
        "where canonical_key='series|naruto|2002') and episode_number=1",
    )
    svc.run(
        "insert into public.import_providers (provider_id, display_name) values ('ProviderB', 'Provider B') "
        "on conflict (provider_id) do nothing"
    )
    svc.run(
        "insert into public.provider_contents (content_id, provider_id, detail_url) "
        "values (:content_id::uuid, 'ProviderB', 'https://b.example/naruto') "
        "on conflict (content_id, provider_id, detail_url) do nothing",
        content_id=content_id,
    )
    svc.run("set role service_role;")
    svc.run(
        "select public.import_sync_source_upsert(:content_id::uuid, :episode_id::uuid, :source::jsonb)",
        content_id=content_id,
        episode_id=episode_id,
        source=json.dumps({
            "provider_id": "ProviderB",
            "extractor_name": "OtherExtractor",
            "server_name": "Other server",
            "media_url": "https://other.example/keep.m3u8",
            "is_resolved": True,
            "status": "resolved",
            "resolved_at": "2026-10-28T10:06:00Z",
        }),
    )
    refresh = replace_links(
        svc,
        "ProviderA",
        [{
            "content_id": content_id,
            "sources": [],
            "episodes": [{
                "season_number": 1,
                "episode_number": 1,
                "title": "Enter Naruto",
                "sources": [{
                    "provider_id": "ProviderA",
                    "extractor_name": "FreshExtractor",
                    "server_name": "Fresh server",
                    "media_url": "https://cdn.example/fresh.m3u8",
                    "is_resolved": True,
                    "status": "resolved",
                    "resolved_at": "2026-10-31T10:00:00Z",
                    "headers": {"Referer": "https://a.example/watch"},
                }],
            }],
        }],
    )
    svc.run("reset role;")
    check("provider link replacement reports one episode/source", refresh.get("replaced_episodes") == 1 and refresh.get("replaced_sources") == 1, str(refresh))
    provider_a_urls = [
        row[0]
        for row in con.run(
            "select media_url from public.media_sources where provider_id='ProviderA' and episode_id=:episode_id::uuid",
            episode_id=episode_id,
        )
    ]
    provider_b_urls = [
        row[0]
        for row in con.run(
            "select media_url from public.media_sources where provider_id='ProviderB' and episode_id=:episode_id::uuid",
            episode_id=episode_id,
        )
    ]
    headers = scalar(
        con,
        "select headers from public.media_sources where provider_id='ProviderA' and episode_id=:episode_id::uuid",
        episode_id=episode_id,
    )
    check("fresh provider link replaced old resolved and failed rows", provider_a_urls == ["https://cdn.example/fresh.m3u8"], str(provider_a_urls))
    check("another provider's playable link is untouched", provider_b_urls == ["https://other.example/keep.m3u8"], str(provider_b_urls))
    check("fresh extractor headers are persisted", headers == {"Referer": "https://a.example/watch"}, str(headers))

    svc.run("set role service_role;")
    empty_refresh = replace_links(
        svc,
        "ProviderA",
        [{"content_id": content_id, "sources": [], "episodes": []}],
    )
    svc.run("reset role;")
    check("empty refresh leaves saved provider link intact", empty_refresh.get("replaced_sources") == 0 and scalar(
        con,
        "select count(*) from public.media_sources where provider_id='ProviderA' and media_url='https://cdn.example/fresh.m3u8'",
    ) == 1, str(empty_refresh))

    order_ok = con.run(
        "select episode_number from public.episodes where dedup_key like 'n:%' order by sort_order"
    )
    check(
        "episodes stored in provider order",
        [r[0] for r in order_ok] == [1, 2],
        str([r[0] for r in order_ok]),
    )

    expect_error(
        con,
        "resolved source without media_url is rejected by check constraint",
        "select public.import_sync_source_upsert("
        "(select id from public.media_contents limit 1), null::uuid,"
        " '{\"provider_id\":\"X\",\"status\":\"resolved\"}'::jsonb)",
    )

    expect_error(
        con,
        "source with no owner at all is rejected",
        "insert into public.media_sources (provider_id) values ('ProviderA')",
    )

    expect_error(
        con,
        "episode source without its content is rejected",
        "insert into public.media_sources (episode_id, provider_id)"
        " select e.id, 'ProviderA' from public.episodes e limit 1",
    )

    # provider config must never hold secret-looking keys
    expect_error(
        con,
        "provider configuration with secret keys is rejected",
        "insert into public.import_providers (provider_id, display_name, configuration)"
        " values ('Sneaky','Sneaky','{\"service_role\":\"x\"}'::jsonb)",
    )

    print()
    if failures:
        print(f"{len(failures)} FAILED: {', '.join(failures)}")
        sys.exit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
