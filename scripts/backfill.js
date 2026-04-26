#!/usr/bin/env node
/**
 * One-shot backfill: Cloudinary → Supabase.
 *
 * Lists every audio file under call_recordings/ in Cloudinary, fetches the
 * existing rows from Supabase, and inserts a row for each missing file.
 *
 * Usage:
 *   set CLOUDINARY_KEY=...
 *   set CLOUDINARY_SECRET=...
 *   node scripts/backfill.js
 *
 * Or as a one-liner in PowerShell:
 *   $env:CLOUDINARY_KEY="..."; $env:CLOUDINARY_SECRET="..."; node scripts/backfill.js
 *
 * Or in bash:
 *   CLOUDINARY_KEY=... CLOUDINARY_SECRET=... node scripts/backfill.js
 *
 * No npm install required — uses only Node's built-in fetch (Node 18+).
 */

const CLOUD_NAME    = process.env.CLOUDINARY_CLOUD || "djrfmyimy";
const PREFIX        = process.env.CLOUDINARY_PREFIX || "call_recordings";
const KEY           = process.env.CLOUDINARY_KEY;
const SECRET        = process.env.CLOUDINARY_SECRET;
const SUPABASE_URL  = process.env.SUPABASE_URL || "https://tahyfwvlnlufqkzknbcm.supabase.co";
const SUPABASE_KEY  = process.env.SUPABASE_KEY  || "sb_publishable_VPkvLBuf_p8ltI4LH0bTuw_PHp6rLYn";

if (!KEY || !SECRET) {
  console.error("ERROR: set CLOUDINARY_KEY and CLOUDINARY_SECRET environment variables.");
  console.error("Example (PowerShell):");
  console.error('  $env:CLOUDINARY_KEY="your_key"; $env:CLOUDINARY_SECRET="your_secret"; node scripts/backfill.js');
  process.exit(1);
}

const auth = "Basic " + Buffer.from(`${KEY}:${SECRET}`).toString("base64");

// ── 1. List every Cloudinary resource (paginated) ────────────────────────────
async function listAllCloudinary() {
  let cursor = null;
  const all = [];
  let page = 0;
  do {
    page++;
    const url = new URL(`https://api.cloudinary.com/v1_1/${CLOUD_NAME}/resources/video`);
    url.searchParams.set("prefix", PREFIX);
    url.searchParams.set("max_results", "500");
    if (cursor) url.searchParams.set("next_cursor", cursor);

    process.stdout.write(`→ Fetching Cloudinary page ${page}… `);
    const res = await fetch(url, { headers: { Authorization: auth } });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`Cloudinary ${res.status}: ${body}`);
    }
    const data = await res.json();
    const got = (data.resources || []).length;
    all.push(...(data.resources || []));
    cursor = data.next_cursor || null;
    console.log(`${got} file(s)${cursor ? " — more pages…" : ""}`);
  } while (cursor);
  return all;
}

// ── 2. Existing rows in Supabase ─────────────────────────────────────────────
async function fetchExistingUrls() {
  const url = `${SUPABASE_URL}/rest/v1/recordings?select=cloudinary_url&limit=10000`;
  const res = await fetch(url, {
    headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}` },
  });
  if (!res.ok) throw new Error(`Supabase ${res.status}: ${await res.text()}`);
  return new Set((await res.json()).map((r) => r.cloudinary_url).filter(Boolean));
}

// ── 3. Parse phone + timestamp + direction from filename ─────────────────────
function parseMeta(publicId, createdAt) {
  const base = publicId.split("/").pop() || publicId;
  const numMatch = base.match(/(\+?\d{7,15})/);
  const number   = numMatch ? numMatch[1] : "Unknown";

  const upper = base.toUpperCase();
  let direction = "UNKNOWN";
  if (upper.startsWith("INCOMING") || upper.includes("INCOMING_") || upper.includes("_IN_"))
    direction = "INCOMING";
  else if (upper.startsWith("OUTGOING") || upper.includes("OUTGOING_") || upper.includes("_OUT_"))
    direction = "OUTGOING";

  const tsMatch = base.match(/(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})/);
  let recordedAt = createdAt;
  if (tsMatch) {
    const [, y, m, d, h, mi, s] = tsMatch;
    recordedAt = `${y}-${m}-${d}T${h}:${mi}:${s}Z`;
  }
  return { number, direction, recordedAt };
}

// ── 4. Insert one row ────────────────────────────────────────────────────────
async function insertRow(r) {
  const meta = parseMeta(r.public_id, r.created_at);
  const fileName =
    (r.public_id.split("/").pop() || r.public_id) + (r.format ? `.${r.format}` : "");
  const body = {
    file_name:      fileName,
    cloudinary_url: r.secure_url,
    number:         meta.number,
    direction:      meta.direction,
    recorded_at:    meta.recordedAt,
    duration_ms:    Math.round((r.duration || 0) * 1000),
    source_used:    "BACKFILL",
    device_id:      "backfill",
    device_label:   "Backfill (pre-import)",
  };
  const res = await fetch(`${SUPABASE_URL}/rest/v1/recordings`, {
    method:  "POST",
    headers: {
      apikey:        SUPABASE_KEY,
      Authorization: `Bearer ${SUPABASE_KEY}`,
      "Content-Type": "application/json",
      Prefer:        "return=minimal",
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
}

// ── Main ─────────────────────────────────────────────────────────────────────
(async () => {
  try {
    const resources = await listAllCloudinary();
    console.log(`✓ Total in Cloudinary: ${resources.length}`);

    process.stdout.write("→ Reading existing Supabase rows… ");
    const existing = await fetchExistingUrls();
    console.log(`${existing.size} already imported`);

    const missing = resources.filter((r) => !existing.has(r.secure_url));
    console.log(`→ Missing: ${missing.length}\n`);

    if (missing.length === 0) {
      console.log("Nothing to do. All Cloudinary files are already in Supabase.");
      return;
    }

    let ok = 0, fail = 0;
    for (let i = 0; i < missing.length; i++) {
      const r = missing[i];
      try {
        await insertRow(r);
        ok++;
        console.log(`✓ [${i + 1}/${missing.length}] ${r.public_id}`);
      } catch (e) {
        fail++;
        console.log(`✗ [${i + 1}/${missing.length}] ${r.public_id} — ${e.message}`);
      }
    }
    console.log(`\nDone — ${ok} inserted, ${fail} failed.`);
    console.log("Open the admin panel to see them: https://mohitmic.github.io/call-recorder/");
  } catch (e) {
    console.error("FATAL:", e.message);
    process.exit(1);
  }
})();
