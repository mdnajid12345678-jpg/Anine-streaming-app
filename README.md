# Anime Streaming App — CloudStream importer

This repository is being bootstrapped from the upstream [reCloudStream/CloudStream](https://github.com/recloudstream/cloudstream) Android source.

## Build

Use **Actions → Bootstrap CloudStream and build Release APK → Run workflow**. The workflow syncs upstream source into `main`, then runs the Android Release build and uploads the APK as a workflow artifact.

## Planned native importer

The importer will be integrated into the Android app (not a separate demo): provider selection and scan, media/season/episode metadata, multiple provider sources per canonical title, duplicate-safe upserts, and Supabase persistence.

## Security

Never put Supabase `service_role` / secret keys in the Android app or repository. The Android client may use a publishable key only with suitable Row Level Security; privileged imports must go through a server-side function using GitHub/Supabase secrets.
