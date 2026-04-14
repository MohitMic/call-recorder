import type { NextApiRequest, NextApiResponse } from 'next';
import { createClient } from '@supabase/supabase-js';

type Response = { signedUrl: string } | { error: string };

export default async function handler(req: NextApiRequest, res: NextApiResponse<Response>) {
  const { path } = req.query;
  if (!path || typeof path !== 'string') {
    return res.status(400).json({ error: 'Missing path query param' });
  }

  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_SERVICE_KEY;
  if (!url || !key) {
    return res.status(500).json({ error: 'Supabase env vars not set' });
  }

  const supabase = createClient(url, key);
  const { data, error } = await supabase.storage
    .from('call-recordings')
    .createSignedUrl(path, 3600);  // 1-hour expiry

  if (error || !data?.signedUrl) {
    return res.status(500).json({ error: error?.message ?? 'Could not create signed URL' });
  }

  return res.status(200).json({ signedUrl: data.signedUrl });
}
