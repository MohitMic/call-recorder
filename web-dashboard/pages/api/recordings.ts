import type { NextApiRequest, NextApiResponse } from 'next';
import { createClient } from '@supabase/supabase-js';

export type Recording = {
  id: string;
  file_name: string;
  storage_path: string;
  number: string | null;
  direction: string;
  recorded_at: string;
  duration_ms: number;
  source_used: string;
  created_at: string;
};

export default async function handler(
  req: NextApiRequest,
  res: NextApiResponse<Recording[] | { error: string }>
) {
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_SERVICE_KEY;

  if (!url || !key) {
    return res.status(500).json({ error: 'SUPABASE_URL and SUPABASE_SERVICE_KEY must be set' });
  }

  const supabase = createClient(url, key);

  const { data, error } = await supabase
    .from('recordings')
    .select('*')
    .order('recorded_at', { ascending: false })
    .limit(200);

  if (error) {
    return res.status(500).json({ error: error.message });
  }

  return res.status(200).json(data ?? []);
}
