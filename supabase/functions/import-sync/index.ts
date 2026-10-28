// =============================================================================================
// import-sync — the only privileged write path for the Android Import system.
//
// Deploy (from the Supabase CLI, see supabase/IMPORT_SYSTEM_SETUP.sql for details):
//   supabase functions deploy import-sync
//   supabase secrets set IMPORT_CALLER_KEY=<a long random value you generate>
//
// Required function secrets (server side only, never in the app or in GitHub artifacts):
//   SUPABASE_SERVICE_ROLE_KEY  — injected automatically by Supabase, used by this function only
//   IMPORT_CALLER_KEY          — device key sent by the app in x-import-caller-key
//
// The Android client sends only SUPABASE_URL + SUPABASE_PUBLISHABLE_KEY (both public).
// This function authenticates the caller, validates and normalises the payload, then performs
// one transactional, idempotent upsert through public.import_sync_upsert().
// =============================================================================================

import { createClient } from "npm:@supabase/supabase-js@2";

const MAX_BODY_BYTES = 8 * 1024 * 1024;
const MAX_CONTENTS = 500;
const MAX_PROVIDERS = 100;
const MAX_ITEM_ERRORS = 500;
const MAX_SOURCES_PER_EPISODE = 50;
const CONTENT_TYPES = new Set([
  "movie",
  "anime_movie",
  "series",
  "anime_series",
  "ova",
  "cartoon",
  "asian_drama",
  "documentary",
  "live",
  "other",
]);
const STATUSES = new Set(["resolved", "unresolved", "no_links", "error"]);
const JOB_STATUSES = new Set(["running", "completed", "failed", "cancelled"]);
const ERROR_STAGES = new Set([
  "DISCOVERY",
  "LOAD",
  "FILTER",
  "LINK_RESOLUTION",
  "SERIALIZATION",
  "UPLOAD",
  "PROVIDER",
]);

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json",
      "access-control-allow-origin": "*",
      "access-control-allow-headers":
        "content-type,apikey,authorization,x-import-caller-key,x-client",
      "access-control-allow-methods": "POST,OPTIONS",
    },
  });
}

/** Constant time string comparison so the caller key cannot be probed by timing. */
async function safeEqual(a: string, b: string): Promise<boolean> {
  const enc = new TextEncoder();
  const digests = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  const x = new Uint8Array(digests[0]);
  const y = new Uint8Array(digests[1]);
  if (x.length !== y.length) return false;
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

const str = (v: unknown): string | undefined =>
  typeof v === "string" && v.length > 0 ? v : undefined;

/** App sends epoch milliseconds; Postgres wants ISO-8601. */
const iso = (v: unknown): string | undefined => {
  if (typeof v === "number" && Number.isFinite(v) && v > 0) {
    return new Date(v).toISOString();
  }
  if (typeof v === "string" && v.length > 0) return v;
  return undefined;
};

const num = (v: unknown): number | undefined =>
  typeof v === "number" && Number.isFinite(v) ? v : undefined;

const stripUndefined = (o: Record<string, unknown>): Record<string, unknown> =>
  Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined));

function mapSource(s: Record<string, unknown>): Record<string, unknown> {
  const status = typeof s.status === "string" && STATUSES.has(s.status) ? s.status : "unresolved";
  return stripUndefined({
    provider_id: str(s.providerId),
    detail_url: str(s.detailUrl),
    extractor_name: str(s.extractorName),
    server_name: str(s.serverName),
    // Only carried through when the real loadLinks() path produced it.
    media_url: status === "resolved" ? str(s.mediaUrl) : undefined,
    quality: str(s.quality),
    link_type: str(s.linkType),
    is_resolved: s.isResolved === true,
    resolved_at: iso(s.resolvedAt),
    expires_at: iso(s.expiresAt),
    status,
    error: str(s.error),
  });
}

function mapEpisode(e: Record<string, unknown>, index: number): Record<string, unknown> {
  const sources = Array.isArray(e.sources)
    ? (e.sources as Record<string, unknown>[]).slice(0, MAX_SOURCES_PER_EPISODE).map(mapSource)
    : [];
  return stripUndefined({
    season_number: num(e.seasonNumber),
    episode_number: num(e.episodeNumber),
    title: str(e.title),
    description: str(e.description),
    poster_url: str(e.posterUrl),
    detail_url: str(e.detailUrl),
    link_data: str(e.linkData),
    attributes: e.attributes && typeof e.attributes === "object"
      ? e.attributes as Record<string, string>
      : {},
    sort_order: num(e.sortOrder) ?? index,
    sources,
  });
}

function mapSeason(s: Record<string, unknown>, index: number): Record<string, unknown> {
  const episodes = Array.isArray(s.episodes)
    ? (s.episodes as Record<string, unknown>[]).map(mapEpisode)
    : [];
  return stripUndefined({
    season_number: num(s.seasonNumber),
    name: str(s.name),
    sort_order: num(s.sortOrder) ?? index,
    episodes,
  });
}

function mapContent(c: Record<string, unknown>): Record<string, unknown> | null {
  const canonicalKey = str(c.canonicalKey);
  const title = str(c.title);
  if (!canonicalKey || !title) return null;

  const contentType = str(c.contentType) ?? "other";
  if (!CONTENT_TYPES.has(contentType)) return null;

  const providerContents = Array.isArray(c.providerContents)
    ? (c.providerContents as Record<string, unknown>[]).map((pc) => stripUndefined({
      provider_id: str(pc.providerId),
      detail_url: str(pc.detailUrl),
      last_seen_at: iso(pc.lastSeenAt),
      provider_metadata: pc.providerMetadata && typeof pc.providerMetadata === "object"
        ? pc.providerMetadata
        : {},
    })).filter((pc) => pc.provider_id && pc.detail_url)
    : [];

  const seasons = Array.isArray(c.seasons)
    ? (c.seasons as Record<string, unknown>[]).map(mapSeason)
    : [];

  const sources = Array.isArray(c.sources)
    ? (c.sources as Record<string, unknown>[]).map(mapSource)
    : [];

  if (providerContents.length === 0 && seasons.length === 0 && sources.length === 0) {
    // Nothing usable to store: skip rather than insert an empty record.
    return null;
  }

  return stripUndefined({
    canonical_key: canonicalKey,
    title,
    normalized_title: str(c.normalizedTitle) ?? title.toLowerCase(),
    original_title: str(c.originalTitle),
    content_type: contentType,
    tv_type: str(c.tvType) ?? "Others",
    release_year: num(c.releaseYear),
    poster_url: str(c.posterUrl),
    description: str(c.description),
    genres: Array.isArray(c.genres)
      ? (c.genres as unknown[]).filter((g): g is string => typeof g === "string").slice(0, 50)
      : [],
    status: str(c.status),
    rating: num(c.rating),
    external_ids: c.externalIds && typeof c.externalIds === "object"
      ? c.externalIds
      : {},
    updated_at: iso(c.updatedAt),
    provider_contents: providerContents,
    seasons,
    sources,
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return json(204, {});
  if (req.method !== "POST") {
    return json(405, { error: "method not allowed, use POST" });
  }

  const expectedKey = Deno.env.get("IMPORT_CALLER_KEY") ?? "";
  if (!expectedKey) {
    // Honest failure: never fall back to "allow everything" or pretend the write succeeded.
    return json(503, {
      error: "IMPORT_CALLER_KEY is not configured for this project",
      detail: "set it with: supabase secrets set IMPORT_CALLER_KEY=<value>",
    });
  }

  const callerKey = req.headers.get("x-import-caller-key") ?? "";
  if (!callerKey || !(await safeEqual(callerKey, expectedKey))) {
    return json(401, { error: "invalid or missing x-import-caller-key" });
  }

  const contentLength = Number(req.headers.get("content-length") ?? "0");
  if (contentLength > MAX_BODY_BYTES) {
    return json(413, { error: `payload too large, limit is ${MAX_BODY_BYTES} bytes` });
  }

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch (_e) {
    return json(400, { error: "body must be valid JSON" });
  }

  const job = body.job as Record<string, unknown> | undefined;
  const jobId = str(job?.id ?? job?.jobId);
  if (!job || !jobId || !/^[0-9a-fA-F-]{36}$/.test(jobId)) {
    return json(400, { error: "job.id must be a uuid" });
  }

  const rawProviders = Array.isArray(body.providers) ? body.providers : [];
  const rawContents = Array.isArray(body.contents) ? body.contents : [];
  const rawErrors = Array.isArray(body.itemErrors) ? body.itemErrors : [];
  const rawDuplicates = Array.isArray(body.duplicateCandidates)
    ? body.duplicateCandidates
    : [];

  if (rawProviders.length > MAX_PROVIDERS) {
    return json(400, { error: `too many providers, limit is ${MAX_PROVIDERS}` });
  }
  if (rawContents.length > MAX_CONTENTS) {
    return json(400, { error: `too many contents, limit is ${MAX_CONTENTS}` });
  }
  if (rawErrors.length > MAX_ITEM_ERRORS) {
    return json(400, { error: `too many item errors, limit is ${MAX_ITEM_ERRORS}` });
  }

  const providers = (rawProviders as Record<string, unknown>[])
    .map((p) => stripUndefined({
      provider_id: str(p.providerId),
      display_name: str(p.displayName) ?? str(p.providerId),
      language: str(p.language),
      base_url: str(p.mainUrl),
    }))
    .filter((p) => !!p.provider_id);

  const contents = (rawContents as Record<string, unknown>[])
    .map(mapContent)
    .filter((c): c is Record<string, unknown> => c !== null);

  const jobStatus = str(job.status);
  const itemErrors = (rawErrors as Record<string, unknown>[])
    .map((e) => stripUndefined({
      provider: str(e.provider),
      url: str(e.url),
      stage: str(e.stage) ?? "PROVIDER",
      message: (str(e.message) ?? "unknown error").slice(0, 1000),
    }))
    .filter((e) => ERROR_STAGES.has(e.stage as string));

  const duplicateCandidates = (rawDuplicates as Record<string, unknown>[])
    .map((d) => stripUndefined({
      canonical_key: str(d.canonicalKey) ?? "review",
      title: (str(d.title) ?? "").slice(0, 500),
      release_year: num(d.releaseYear),
      tv_type: str(d.tvType),
      conflicting_titles: Array.isArray(d.conflictingTitles)
        ? (d.conflictingTitles as unknown[]).filter((t): t is string => typeof t === "string")
        : [],
      reason: str(d.reason) ?? "ambiguous_title",
      detected_at: iso(d.detectedAt),
    }))
    .filter((d) => d.title.length > 0);

  const payload = {
    job: stripUndefined({
      id: jobId,
      provider_ids: Array.isArray(job.providerIds)
        ? (job.providerIds as unknown[]).filter((v): v is string => typeof v === "string")
        : [],
      filters: job.filters && typeof job.filters === "object" ? job.filters : {},
      status: jobStatus && JOB_STATUSES.has(jobStatus) ? jobStatus : "completed",
      started_at: iso(job.startedAt),
      finished_at: iso(job.finishedAt),
      counters: job.counters && typeof job.counters === "object" ? job.counters : {},
      error_summary: str(job.errorSummary)?.slice(0, 1000),
    }),
    providers,
    contents,
    item_errors: itemErrors,
    duplicate_candidates: duplicateCandidates,
  };

  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  if (!serviceRoleKey || !supabaseUrl) {
    return json(503, {
      error: "server side Supabase secrets are not configured for this project",
    });
  }

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { data, error } = await admin.rpc("import_sync_upsert", { payload });

  if (error) {
    console.error("import_sync_upsert failed:", error.code ?? "unknown", error.message ?? "");
    return json(422, {
      error: "database rejected the import payload",
      detail: (error.message ?? "").slice(0, 500),
    });
  }

  const counts = (data ?? {}) as Record<string, number | string>;

  return json(200, {
    // Field names match the Kotlin ImportUploadResult DTO exactly.
    job: String(counts.job ?? jobId),
    upsertedContents: Number(counts.upserted_contents ?? contents.length),
    upsertedEpisodes: Number(counts.upserted_episodes ?? 0),
    upsertedSources: Number(counts.upserted_sources ?? 0),
    duplicateCandidates: duplicateCandidates.length,
    errors: [] as string[],
  });
});
