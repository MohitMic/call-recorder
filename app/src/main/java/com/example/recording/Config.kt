package com.example.recording

/**
 * Hardcoded app-wide configuration. No user-editable settings for these values.
 *
 * CLOUDINARY SETUP
 * ────────────────
 * 1. Log into Cloudinary dashboard → Settings → Upload → Upload presets
 * 2. Create an **unsigned** upload preset (or use an existing one).
 *    Set "Resource type" = Auto (or Video/Raw works for audio too).
 * 3. Fill in CLOUDINARY_CLOUD_NAME and CLOUDINARY_UPLOAD_PRESET below.
 *
 * SUPABASE SETUP (metadata DB only — no Storage needed)
 * ─────────────────────────────────────────────────────
 * Run once in Supabase SQL editor:
 *   create table if not exists recordings (
 *     id            uuid primary key default gen_random_uuid(),
 *     file_name     text not null,
 *     cloudinary_url text,
 *     number        text,
 *     direction     text,
 *     recorded_at   timestamptz,
 *     duration_ms   bigint,
 *     source_used   text,
 *     created_at    timestamptz default now()
 *   );
 *   alter table recordings enable row level security;
 *   create policy "insert_anon" on recordings for insert to anon with check (true);
 */
object Config {
    // ── Cloudinary (audio file storage) ──────────────────────────────────────
    const val CLOUDINARY_CLOUD_NAME    = "djrfmyimy"
    const val CLOUDINARY_UPLOAD_PRESET = "Audiorecording"

    // ── Supabase (metadata DB only) ───────────────────────────────────────────
    const val SUPABASE_URL = "https://tahyfwvlnlufqkzknbcm.supabase.co"
    const val SUPABASE_KEY = "sb_publishable_VPkvLBuf_p8ltI4LH0bTuw_PHp6rLYn"
}
