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
// OEM filename timestamps are in the phone's local time. Default to IST since
// the existing data was recorded in India. Override with PHONE_TZ_OFFSET="+05:30".
// Format: "+HH:MM" or "-HH:MM".
const PHONE_TZ_OFFSET = process.env.PHONE_TZ_OFFSET || "+05:30";

if (!KEY || !SECRET) {
  console.error("ERROR: set CLOUDINARY_KEY and CLOUDINARY_SECRET environment variables.");
  console.error("Example (PowerShell):");
  console.error('  $env:CLOUDINARY_KEY="your_key"; $env:CLOUDINARY_SECRET="your_secret"; node scripts/backfill.js');
  process.exit(1);
}

const auth = "Basic " + Buffer.from(`${KEY}:${SECRET}`).toString("base64");

// ── 1. List every Cloudinary resource via the Search API ─────────────────────
// Search API returns duration + display_name in one call (the basic /resources
// list endpoint omits both for video/audio assets uploaded via unsigned presets).
async function listAllCloudinary() {
  let cursor = null;
  const all = [];
  let page = 0;
  do {
    page++;
    const body = {
      expression:  `folder=${PREFIX}`,
      max_results: 500,
    };
    if (cursor) body.next_cursor = cursor;

    process.stdout.write(`→ Fetching Cloudinary page ${page}… `);
    const res = await fetch(
      `https://api.cloudinary.com/v1_1/${CLOUD_NAME}/resources/search`,
      {
        method: "POST",
        headers: {
          Authorization: auth,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(body),
      }
    );
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Cloudinary ${res.status}: ${text}`);
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
// Returns a Map<cloudinary_url, { source_used }> so the caller knows which
// rows were authored by a real phone (and shouldn't be overwritten).
async function fetchExistingRows() {
  const url = `${SUPABASE_URL}/rest/v1/recordings?select=cloudinary_url,source_used&limit=10000`;
  const res = await fetch(url, {
    headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}` },
  });
  if (!res.ok) throw new Error(`Supabase ${res.status}: ${await res.text()}`);
  const map = new Map();
  for (const r of await res.json()) {
    if (r.cloudinary_url) map.set(r.cloudinary_url, { source_used: r.source_used });
  }
  return map;
}

// ── 3. Parse phone + timestamp + direction from filename ─────────────────────
// Pick the most useful name source: original_filename / display_name / public_id
function bestName(r) {
  // Cloudinary "display_name" is what the user/upload preset stored as the
  // human-readable filename. For unsigned uploads, public_id is auto-generated
  // (random IDs like "k0uunka2fxycuipcp5kr"), so display_name is the only
  // meaningful name. Check it first.
  return r.display_name || r.original_filename || (r.public_id || "").split("/").pop() || "";
}

// ── Generic, robust filename parser ──────────────────────────────────────────
// Strategy: find every plausible candidate (any digit run, any
// date-with-separator pattern), validate each by constructing a Date and
// checking it falls in a sane range. Pick whichever has the most fields
// and lands in [2000, 2100). Falls back gracefully when nothing matches.
//
// Designed to handle arbitrary OEM naming — Xiaomi, Vivo, OnePlus, Samsung,
// random apps — without needing to add a new pattern per device.
function findRecordedAt(name, fallbackIso) {
  const candidates = [];

  // 1. Date-with-separators: "YYYY?MM?DD?HH?MM?SS"  with any non-digit between fields
  //    Catches: "2026-04-25 17-58-40", "2026/04/25_17:58:40", "2026.04.25 17.58.40"
  const sepRe = /(\d{4})\D(\d{1,2})\D(\d{1,2})\D(\d{1,2})\D(\d{1,2})\D(\d{1,2})/g;
  let m;
  while ((m = sepRe.exec(name)) !== null) {
    candidates.push({ y: m[1], mo: m[2], d: m[3], h: m[4], mi: m[5], s: m[6], score: 6 });
  }

  // 2. Contiguous 14-digit YYYYMMDDHHMMSS — only if not part of a longer run
  //    of digits (otherwise we'd latch onto phone numbers like 00919588259935)
  const c14 = /(?<!\d)(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?!\d)/g;
  while ((m = c14.exec(name)) !== null) {
    candidates.push({ y: m[1], mo: m[2], d: m[3], h: m[4], mi: m[5], s: m[6], score: 6 });
  }

  // 3. Contiguous 12-digit YYYYMMDDHHMM (no seconds)
  const c12 = /(?<!\d)(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(?!\d)/g;
  while ((m = c12.exec(name)) !== null) {
    candidates.push({ y: m[1], mo: m[2], d: m[3], h: m[4], mi: m[5], s: "00", score: 5 });
  }

  // 4. Contiguous 8-digit YYYYMMDD
  const c8 = /(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)/g;
  while ((m = c8.exec(name)) !== null) {
    candidates.push({ y: m[1], mo: m[2], d: m[3], h: "00", mi: "00", s: "00", score: 3 });
  }

  // 5. Date-only with separators: "YYYY-MM-DD"
  const d8 = /(\d{4})\D(\d{1,2})\D(\d{1,2})/g;
  while ((m = d8.exec(name)) !== null) {
    candidates.push({ y: m[1], mo: m[2], d: m[3], h: "00", mi: "00", s: "00", score: 3 });
  }

  // Validate each candidate. A valid timestamp has a real Date and a year in
  // a believable window (2000–2100). Prefer the highest-detail candidate;
  // tie-break by appearing latest in the filename (timestamps usually come
  // at the end, after caller name / phone).
  let best = null;
  for (const c of candidates) {
    const y = +c.y, mo = +c.mo, d = +c.d, h = +c.h, mi = +c.mi, s = +c.s;
    if (y < 2000 || y > 2099) continue;
    if (mo < 1 || mo > 12) continue;
    if (d < 1 || d > 31) continue;
    if (h > 23 || mi > 59 || s > 59) continue;
    const dt = new Date(Date.UTC(y, mo - 1, d, h, mi, s));
    if (isNaN(dt.getTime())) continue;
    // Sanity: must round-trip (catches "Feb 30")
    if (dt.getUTCMonth() !== mo - 1 || dt.getUTCDate() !== d) continue;
    // The values came from a phone-local timestamp in PHONE_TZ_OFFSET — emit
    // an ISO string with that offset, so PostgreSQL stores the correct UTC
    // moment and browsers display the original filename time when they're in
    // the same timezone.
    const pad = (n) => String(n).padStart(2, "0");
    const iso = `${pad(y)}-${pad(mo)}-${pad(d)}T${pad(h)}:${pad(mi)}:${pad(s)}${PHONE_TZ_OFFSET}`;
    if (!best || c.score > best.score) {
      best = { ...c, iso, score: c.score };
    }
  }
  return best ? best.iso : (fallbackIso || null);
}

// ── Phone / caller extraction ────────────────────────────────────────────────
// Pull every digit run that looks like a phone number (7–15 digits, optional +).
// Whatever's left over (after stripping numbers + dates + separator junk) is
// treated as a contact name. Returns whichever is more useful for display.
function findPhoneAndCaller(name) {
  // Strip all date-like chunks first so they don't pollute the "caller name"
  let stripped = name;
  // Date with separators
  stripped = stripped.replace(/\d{4}\D\d{1,2}\D\d{1,2}\D\d{1,2}\D\d{1,2}\D\d{1,2}/g, " ");
  stripped = stripped.replace(/\d{4}\D\d{1,2}\D\d{1,2}/g, " ");
  // Contiguous date stamps
  stripped = stripped.replace(/(?<!\d)\d{14}(?!\d)/g, " ");
  stripped = stripped.replace(/(?<!\d)\d{12}(?!\d)/g, " ");
  stripped = stripped.replace(/(?<!\d)\d{8}(?!\d)/g, " ");

  // Phone number: any +?digit run of 7-15 digits in the ORIGINAL filename
  const phoneMatches = [...name.matchAll(/(?<!\d)(\+?\d{7,15})(?!\d)/g)];
  // De-prioritise sequences that overlap with a date-stamp position (we removed
  // them in `stripped`, so any survivor is real).
  let phoneNumber = null;
  for (const pm of phoneMatches) {
    if (stripped.includes(pm[1])) { phoneNumber = pm[1]; break; }
  }

  // Caller name: stripped filename minus the phone number, cleaned up
  let caller = stripped;
  if (phoneNumber) caller = caller.split(phoneNumber).join(" ");
  caller = caller
    .replace(/[()_]/g, " ")        // turn parens/underscores into spaces
    .replace(/\s+/g, " ")           // collapse whitespace
    .replace(/^[\s\-_,]+|[\s\-_,]+$/g, "")
    .trim();

  // If caller is empty or pure punctuation/digits, fall back to phone, then "Unknown"
  if (!caller || /^[\s\-_,+\d]*$/.test(caller)) {
    caller = phoneNumber || "Unknown";
  }
  return { caller, phoneNumber };
}

function parseMeta(r) {
  const base = bestName(r);

  const { caller } = findPhoneAndCaller(base);

  // Two times, two truths:
  //   recorded_at = when the call actually happened (parsed from filename;
  //                 falls back to Cloudinary upload time only when filename
  //                 has no parseable date at all)
  //   uploaded_at = when the file reached Cloudinary (always r.created_at)
  const recordedAt = findRecordedAt(base, r.created_at);
  const uploadedAt = r.created_at;

  let direction = "UNKNOWN";
  const upper = base.toUpperCase();
  if (upper.startsWith("INCOMING") || upper.includes("INCOMING_") || upper.includes("_IN_"))
    direction = "INCOMING";
  else if (upper.startsWith("OUTGOING") || upper.includes("OUTGOING_") || upper.includes("_OUT_"))
    direction = "OUTGOING";

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
  return { number: caller, direction, recordedAt, uploadedAt, durationMs: Math.round((duration || 0) * 1000) };
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
    uploaded_at:    meta.uploadedAt,
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
    const existing = await fetchExistingRows();
    console.log(`${existing.size} already in Supabase`);

    const toInsert = resources.filter((r) => !existing.has(r.secure_url));
    // Only refresh BACKFILL rows. Rows that came from a real phone upload
    // (source_used !== "BACKFILL") have authoritative metadata — never
    // overwrite them, regardless of what we can guess from filename.
    const toUpdate = resources.filter((r) => {
      const ex = existing.get(r.secure_url);
      return ex && ex.source_used === "BACKFILL";
    });
    const skipped = resources.length - toInsert.length - toUpdate.length;
    console.log(`→ ${toInsert.length} new to insert, ${toUpdate.length} existing BACKFILL rows to refresh, ${skipped} live-phone rows preserved\n`);

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
