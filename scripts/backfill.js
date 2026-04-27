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
    const url = new URL(`https://api.cloudinary.com/v1_1/${CLOUD_NAME}/resources/video/upload`);
    url.searchParams.set("prefix", PREFIX);
    url.searchParams.set("max_results", "500");
    url.searchParams.set("media_metadata", "true"); // returns duration
    url.searchParams.set("context", "true");        // returns custom context
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
// Pick the most useful name source: original_filename / display_name / public_id
function bestName(r) {
  return r.original_filename || r.display_name || (r.public_id || "").split("/").pop() || "";
}

function parseMeta(r) {
  const base = bestName(r);

  // Try to find a phone number anywhere in the filename
  const phoneMatch = base.match(/(\+?\d{7,15})/);

  // Extract the "caller label" — everything before the trailing date stamp.
  // Common OEM filename shapes we've seen:
  //   "Arjun Ji (Sanju & Paravati) 2026-03-30 03-51-42"
  //   "9571091011(9571091011)_20260425171748"
  //   "+918107565674 2026-03-27 03-52-51"
  //   "Bared Solar Narendra Pal Ji 2026-04-08 00-06-19"
  let label = base;
  const dateStrips = [
    /[\s_]*\d{4}-\d{2}-\d{2}[\s_-]+\d{2}-\d{2}-\d{2}.*$/,  // "2026-03-30 03-51-42"
    /[\s_]+\d{8}_\d{6}.*$/,                                  // "_20260425_171617"
    /[\s_]*\d{14}.*$/,                                       // "_20260425171617" or "20260425171617"
  ];
  for (const re of dateStrips) {
    const stripped = label.replace(re, "").trim();
    if (stripped && stripped !== label) { label = stripped; break; }
  }
  // Drop trailing "_" or "-" leftover
  label = label.replace(/[_\s-]+$/, "").trim();

  // Decide what to display in the "caller" column:
  //   • If we have a contact-name-shaped label → use the name as-is
  //   • Else if we found a phone number → use it
  //   • Else "Unknown"
  const looksLikePureNumber = label && /^[+\d\s()_-]+$/.test(label);
  let caller;
  if (label && !looksLikePureNumber) {
    caller = label; // e.g. "Arjun Ji (Sanju & Paravati)"
  } else if (phoneMatch) {
    caller = phoneMatch[1];
  } else if (label) {
    caller = label;
  } else {
    caller = "Unknown";
  }

  const upper = base.toUpperCase();
  let direction = "UNKNOWN";
  if (upper.startsWith("INCOMING") || upper.includes("INCOMING_") || upper.includes("_IN_"))
    direction = "INCOMING";
  else if (upper.startsWith("OUTGOING") || upper.includes("OUTGOING_") || upper.includes("_OUT_"))
    direction = "OUTGOING";

  const tsMatch = base.match(/(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})/) ||
                  base.match(/(\d{4})-(\d{2})-(\d{2})[\s_-]+(\d{2})-(\d{2})-(\d{2})/);
  let recordedAt = r.created_at;
  if (tsMatch) {
    const [, y, m, d, h, mi, s] = tsMatch;
    recordedAt = `${y}-${m}-${d}T${h}:${mi}:${s}Z`;
  }

  // Cloudinary returns duration on most resources; some need media_metadata.duration
  let duration = r.duration;
  if ((!duration || duration === 0) && r.media_metadata) {
    const md = Array.isArray(r.media_metadata)
      ? r.media_metadata.find((x) => x.key === "duration")
      : null;
    if (md) duration = parseFloat(md.value);
  }
  if ((!duration || duration === 0) && r.video && r.video.duration) duration = r.video.duration;
  if ((!duration || duration === 0) && r.audio && r.audio.duration) duration = r.audio.duration;
  return { number: caller, direction, recordedAt, durationMs: Math.round((duration || 0) * 1000) };
}

// ── 4. Build row body, then insert / update ──────────────────────────────────
function buildBody(r) {
  const meta = parseMeta(r);
  const fileName = bestName(r) + (r.format ? `.${r.format}` : "");
  return {
    file_name:      fileName,
    cloudinary_url: r.secure_url,
    number:         meta.number,
    direction:      meta.direction,
    recorded_at:    meta.recordedAt,
    duration_ms:    meta.durationMs,
    source_used:    "BACKFILL",
    device_id:      "backfill",
    device_label:   "Backfill (pre-import)",
  };
}

async function insertRow(r) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/recordings`, {
    method:  "POST",
    headers: {
      apikey:        SUPABASE_KEY,
      Authorization: `Bearer ${SUPABASE_KEY}`,
      "Content-Type": "application/json",
      Prefer:        "return=minimal",
    },
    body: JSON.stringify(buildBody(r)),
  });
  if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
}

async function updateRow(r) {
  const url = `${SUPABASE_URL}/rest/v1/recordings?cloudinary_url=eq.${encodeURIComponent(r.secure_url)}`;
  // Only patch the parsed/derived fields. Don't overwrite device_id /
  // device_label / source_used on rows that came from real phones.
  const body = buildBody(r);
  delete body.device_id;
  delete body.device_label;
  delete body.source_used;
  delete body.cloudinary_url; // it's the lookup key, no need to re-set
  const res = await fetch(url, {
    method:  "PATCH",
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
    console.log(`${existing.size} already in Supabase`);

    const toInsert = resources.filter((r) => !existing.has(r.secure_url));
    const toUpdate = resources.filter((r) => existing.has(r.secure_url));
    console.log(`→ ${toInsert.length} new to insert, ${toUpdate.length} existing to refresh\n`);

    let ok = 0, fail = 0;

    for (let i = 0; i < toInsert.length; i++) {
      const r = toInsert[i];
      try {
        await insertRow(r);
        ok++;
        console.log(`✓ INSERT [${i + 1}/${toInsert.length}] ${bestName(r)}`);
      } catch (e) {
        fail++;
        console.log(`✗ INSERT [${i + 1}/${toInsert.length}] ${bestName(r)} — ${e.message}`);
      }
    }

    for (let i = 0; i < toUpdate.length; i++) {
      const r = toUpdate[i];
      try {
        await updateRow(r);
        ok++;
        console.log(`↻ UPDATE [${i + 1}/${toUpdate.length}] ${bestName(r)}`);
      } catch (e) {
        fail++;
        console.log(`✗ UPDATE [${i + 1}/${toUpdate.length}] ${bestName(r)} — ${e.message}`);
      }
    }

    console.log(`\nDone — ${ok} processed, ${fail} failed.`);
    console.log("Open the admin panel to see them: https://mohitmic.github.io/call-recorder/");
  } catch (e) {
    console.error("FATAL:", e.message);
    process.exit(1);
  }
})();
