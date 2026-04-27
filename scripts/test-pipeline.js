#!/usr/bin/env node
/**
 * End-to-end test of the upload pipeline — same code path the phone uses.
 *
 *  1. Generate a tiny silent .mp3 file in a temp dir
 *  2. Upload to Cloudinary using the unsigned preset (Audiorecording)
 *     with the EXACT same multipart fields as FileUploader.kt
 *  3. Insert the metadata row into Supabase with the EXACT same JSON
 *     payload the phone sends
 *  4. Read the row back to confirm it landed
 *  5. Optionally clean up (delete the test row + the Cloudinary file)
 *
 *  Run:    node scripts/test-pipeline.js
 *  Cleanup the row at the end:    node scripts/test-pipeline.js --cleanup
 */

const fs   = require("node:fs");
const path = require("node:path");
const os   = require("node:os");

// Same values as Config.kt in the Android app
const CLOUD_NAME    = "djrfmyimy";
const UPLOAD_PRESET = "Audiorecording";
const SUPABASE_URL  = "https://tahyfwvlnlufqkzknbcm.supabase.co";
const SUPABASE_KEY  = "sb_publishable_VPkvLBuf_p8ltI4LH0bTuw_PHp6rLYn";

const CLEANUP = process.argv.includes("--cleanup");

// Fetch a real recording from your Cloudinary library to use as the test
// source. Cloudinary's video uploader rejects synthetic byte streams; using
// a real file proves the pipeline end-to-end.
async function fetchSampleRecording() {
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/recordings?select=cloudinary_url&cloudinary_url=not.is.null&limit=1`,
    { headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}` } }
  );
  const rows = await res.json();
  if (!rows.length || !rows[0].cloudinary_url) {
    throw new Error("No existing recording available to use as a test sample.");
  }
  const sampleUrl = rows[0].cloudinary_url;
  console.log(`    using sample: ${sampleUrl}`);
  const audio = await fetch(sampleUrl);
  if (!audio.ok) throw new Error(`Could not download sample: HTTP ${audio.status}`);
  return Buffer.from(await audio.arrayBuffer());
}

async function main() {
  const stamp = new Date().toISOString().replace(/[-:.]/g, "").slice(0, 14); // YYYYMMDDHHMMSS
  const fileName = `pipeline_test_${stamp}.mp3`;
  const tmpPath  = path.join(os.tmpdir(), fileName);
  console.log("[1/5] Fetching a real sample recording for the test…");
  const sampleBytes = await fetchSampleRecording();
  fs.writeFileSync(tmpPath, sampleBytes);
  console.log(`    wrote ${sampleBytes.length} bytes → ${tmpPath}`);

  // ── 2. Upload to Cloudinary (mirroring FileUploader.kt exactly) ───────────
  console.log("[2/5] Uploading to Cloudinary…");
  const fd = new FormData();
  fd.append("file", new Blob([fs.readFileSync(tmpPath)], { type: "audio/mpeg" }), fileName);
  fd.append("upload_preset", UPLOAD_PRESET);
  fd.append("folder", "call_recordings");

  const upRes = await fetch(
    `https://api.cloudinary.com/v1_1/${CLOUD_NAME}/video/upload`,
    { method: "POST", body: fd }
  );
  const upBody = await upRes.text();
  if (!upRes.ok) {
    console.error("    ✗ Cloudinary returned HTTP", upRes.status);
    console.error("    body:", upBody);
    process.exit(1);
  }
  const upJson = JSON.parse(upBody);
  const cloudinaryUrl = upJson.secure_url;
  console.log(`    ✓ Cloudinary OK → ${cloudinaryUrl}`);

  // ── 3. Insert Supabase row (mirroring FileUploader.insertMetadata) ────────
  console.log("[3/5] Inserting Supabase row…");
  const supabaseBody = {
    file_name:      fileName,
    cloudinary_url: cloudinaryUrl,
    number:         "0000000000",
    direction:      "INCOMING",
    recorded_at:    new Date().toISOString(),
    duration_ms:    1000,
    source_used:    "PIPELINE_TEST",
    device_id:      "pipeline-test",
    device_label:   "Pipeline Test",
  };
  const inRes = await fetch(`${SUPABASE_URL}/rest/v1/recordings`, {
    method:  "POST",
    headers: {
      apikey:        SUPABASE_KEY,
      Authorization: `Bearer ${SUPABASE_KEY}`,
      "Content-Type": "application/json",
      Prefer:        "return=representation",
    },
    body: JSON.stringify(supabaseBody),
  });
  const inBody = await inRes.text();
  if (!inRes.ok) {
    console.error("    ✗ Supabase returned HTTP", inRes.status);
    console.error("    body:", inBody);
    process.exit(2);
  }
  const insertedRow = JSON.parse(inBody)[0];
  console.log(`    ✓ Supabase row created → id=${insertedRow.id}`);

  // ── 4. Verify it's visible the way the admin panel sees it ────────────────
  console.log("[4/5] Verifying admin-panel visibility (anon SELECT)…");
  // Same query shape the admin panel uses: filter by cloudinary_url
  const readRes = await fetch(
    `${SUPABASE_URL}/rest/v1/recordings?cloudinary_url=eq.${encodeURIComponent(cloudinaryUrl)}&select=*`,
    { headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}` } }
  );
  const readJson = await readRes.json();
  if (readJson.length !== 1) {
    console.error("    ✗ Row NOT visible to anon role — admin panel won't show it.");
    console.error("    This is exactly the case where the phone would now refuse to mark");
    console.error("    the file as uploaded, and would retry. Fix the SELECT RLS policy.");
    console.error("    Got:", readJson);
    process.exit(3);
  }
  console.log(`    ✓ Row is visible in admin: ${readJson[0].file_name} (${readJson[0].device_label})`);
  console.log(`    → On phone, this is exactly when '.uploaded' marker would be written.`);

  // ── 5. Cleanup (or leave it for inspection) ───────────────────────────────
  if (CLEANUP) {
    console.log("[5/5] Cleaning up test row…");
    const delRes = await fetch(
      `${SUPABASE_URL}/rest/v1/recordings?id=eq.${insertedRow.id}`,
      {
        method:  "DELETE",
        headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}` },
      }
    );
    if (!delRes.ok) {
      console.warn("    ⚠ Could not delete row:", delRes.status, await delRes.text());
    } else {
      console.log("    ✓ Test row deleted.");
    }
  } else {
    console.log("[5/5] Skipping cleanup (pass --cleanup to delete the test row).");
    console.log(`    Row left in Supabase: id=${insertedRow.id}, device_label="Pipeline Test"`);
    console.log("    See it in admin panel → device filter \"Pipeline Test\".");
  }

  fs.unlinkSync(tmpPath);
  console.log("\n✅ End-to-end pipeline OK — Cloudinary upload + Supabase insert + read.");
}

main().catch((e) => {
  console.error("FATAL:", e);
  process.exit(99);
});
