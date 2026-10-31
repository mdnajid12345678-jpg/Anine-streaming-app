-- =============================================================================================
-- Refresh playable links for already-imported content from one provider.
--
-- Apply after 20261028_cloudstream_import.sql and 20261029_source_headers.sql. The Edge Function
-- invokes this SECURITY DEFINER function only after the device has resolved new links through
-- the real provider loadLinks()/extractor path. A failed/empty resolution never deletes old links.
-- =============================================================================================

begin;

create or replace function public.import_sync_replace_provider_links(
    p_provider_id text,
    p_contents jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_content jsonb;
    v_episode jsonb;
    v_source jsonb;
    v_content_id uuid;
    v_episode_ids uuid[];
    v_episode_id uuid;
    v_season_number integer;
    v_episode_number integer;
    v_link_data text;
    v_title text;
    v_sources jsonb;
    v_replaced_contents integer := 0;
    v_replaced_episodes integer := 0;
    v_replaced_sources integer := 0;
    v_unmatched_episodes integer := 0;
    v_content_replaced boolean;
begin
    if p_provider_id is null or length(trim(p_provider_id)) = 0 or length(p_provider_id) > 200 then
        raise exception 'provider id is invalid';
    end if;
    if p_contents is null or jsonb_typeof(p_contents) <> 'array'
       or jsonb_array_length(p_contents) < 1 or jsonb_array_length(p_contents) > 50 then
        raise exception 'contents must be an array with 1 to 50 entries';
    end if;

    for v_content in select value from jsonb_array_elements(p_contents) as item(value) loop
        v_content_id := (v_content ->> 'content_id')::uuid;
        if not exists (
            select 1 from public.provider_contents pc
             where pc.content_id = v_content_id and pc.provider_id = p_provider_id
        ) then
            raise exception 'content % is not associated with provider %', v_content_id, p_provider_id;
        end if;
        v_content_replaced := false;

        -- Replace content-level sources (movies/live) only when this payload contains at least
        -- one genuine resolved URL. Empty/error results preserve the previously working source.
        v_sources := coalesce(v_content -> 'sources', '[]'::jsonb);
        if jsonb_typeof(v_sources) = 'array' and exists (
            select 1
              from jsonb_array_elements(v_sources) as source(value)
             where source.value ->> 'status' = 'resolved'
               and source.value ->> 'is_resolved' = 'true'
               and nullif(source.value ->> 'media_url', '') is not null
        ) then
            delete from public.media_sources
             where provider_id = p_provider_id
               and content_id = v_content_id
               and episode_id is null;

            for v_source in select value from jsonb_array_elements(v_sources) as source(value) loop
                if v_source ->> 'status' = 'resolved'
                   and v_source ->> 'is_resolved' = 'true'
                   and nullif(v_source ->> 'media_url', '') is not null then
                    perform public.import_sync_source_upsert(
                        v_content_id,
                        null,
                        v_source || jsonb_build_object('provider_id', p_provider_id)
                    );
                    v_replaced_sources := v_replaced_sources + 1;
                end if;
            end loop;
            v_content_replaced := true;
        end if;

        -- Replace episode sources individually. Episodes that are missing, ambiguous, or have no
        -- newly resolved URLs are counted/reported but their old sources are deliberately kept.
        for v_episode in
            select value
              from jsonb_array_elements(coalesce(v_content -> 'episodes', '[]'::jsonb)) as item(value)
        loop
            v_sources := coalesce(v_episode -> 'sources', '[]'::jsonb);
            if jsonb_typeof(v_sources) <> 'array' or not exists (
                select 1
                  from jsonb_array_elements(v_sources) as source(value)
                 where source.value ->> 'status' = 'resolved'
                   and source.value ->> 'is_resolved' = 'true'
                   and nullif(source.value ->> 'media_url', '') is not null
            ) then
                continue;
            end if;

            v_season_number := nullif(v_episode ->> 'season_number', '')::integer;
            v_episode_number := nullif(v_episode ->> 'episode_number', '')::integer;
            v_link_data := nullif(v_episode ->> 'link_data', '');
            v_title := nullif(trim(v_episode ->> 'title'), '');
            v_episode_ids := '{}'::uuid[];

            if v_episode_number is not null then
                select coalesce(array_agg(ep.id), '{}'::uuid[])
                  into v_episode_ids
                  from public.episodes ep
                 where ep.content_id = v_content_id
                   and ep.season_number is not distinct from v_season_number
                   and ep.episode_number = v_episode_number;
            elsif v_link_data is not null then
                select coalesce(array_agg(ep.id), '{}'::uuid[])
                  into v_episode_ids
                  from public.episodes ep
                 where ep.content_id = v_content_id
                   and ep.season_number is not distinct from v_season_number
                   and ep.link_data = v_link_data;
            elsif v_title is not null then
                select coalesce(array_agg(ep.id), '{}'::uuid[])
                  into v_episode_ids
                  from public.episodes ep
                 where ep.content_id = v_content_id
                   and ep.season_number is not distinct from v_season_number
                   and lower(trim(ep.title)) = lower(v_title);
            end if;

            if cardinality(v_episode_ids) <> 1 then
                v_unmatched_episodes := v_unmatched_episodes + 1;
                continue;
            end if;
            v_episode_id := v_episode_ids[1];

            delete from public.media_sources
             where provider_id = p_provider_id
               and content_id = v_content_id
               and episode_id = v_episode_id;

            for v_source in select value from jsonb_array_elements(v_sources) as source(value) loop
                if v_source ->> 'status' = 'resolved'
                   and v_source ->> 'is_resolved' = 'true'
                   and nullif(v_source ->> 'media_url', '') is not null then
                    perform public.import_sync_source_upsert(
                        v_content_id,
                        v_episode_id,
                        v_source || jsonb_build_object('provider_id', p_provider_id)
                    );
                    v_replaced_sources := v_replaced_sources + 1;
                end if;
            end loop;
            v_replaced_episodes := v_replaced_episodes + 1;
            v_content_replaced := true;
        end loop;

        if v_content_replaced then
            v_replaced_contents := v_replaced_contents + 1;
        end if;
    end loop;

    return jsonb_build_object(
        'replaced_contents', v_replaced_contents,
        'replaced_episodes', v_replaced_episodes,
        'replaced_sources', v_replaced_sources,
        'unmatched_episodes', v_unmatched_episodes
    );
end;
$$;

revoke all on function public.import_sync_replace_provider_links(text, jsonb)
    from public, anon, authenticated;
grant execute on function public.import_sync_replace_provider_links(text, jsonb)
    to service_role;

comment on function public.import_sync_replace_provider_links(text, jsonb) is
    'Atomically replaces playable sources for a provider only where fresh resolved media URLs were supplied.';

commit;

-- Validation: service_role should be the only role able to execute this function.
-- select has_function_privilege('anon', 'public.import_sync_replace_provider_links(text,jsonb)', 'execute') as anon_can_run,
--        has_function_privilege('authenticated', 'public.import_sync_replace_provider_links(text,jsonb)', 'execute') as authenticated_can_run,
--        has_function_privilege('service_role', 'public.import_sync_replace_provider_links(text,jsonb)', 'execute') as service_role_can_run;
