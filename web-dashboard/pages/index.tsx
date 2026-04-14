import { useEffect, useRef, useState } from 'react';
import type { Recording } from './api/recordings';

function formatDuration(ms: number): string {
  const sec = Math.floor(ms / 1000);
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

function directionBadge(direction: string) {
  const isOut = direction?.toUpperCase() === 'OUTGOING';
  return (
    <span className={`inline-block px-2 py-0.5 rounded text-xs font-semibold ${
      isOut ? 'bg-blue-100 text-blue-800' : 'bg-green-100 text-green-800'
    }`}>
      {isOut ? '↑ OUT' : '↓ IN'}
    </span>
  );
}

function sourceBadge(source: string) {
  const isDual = source === 'DUAL_CHANNEL';
  const isVoice = source.startsWith('VOICE_CALL') || source.startsWith('VOICE_DOWN') || source.startsWith('VOICE_UP');
  const cls = isDual
    ? 'bg-blue-600 text-white'
    : isVoice
    ? 'bg-purple-100 text-purple-800'
    : 'bg-gray-100 text-gray-700';
  return (
    <span className={`inline-block px-2 py-0.5 rounded text-xs font-mono ${cls}`}>
      {source || '—'}
    </span>
  );
}

export default function Home() {
  const [recordings, setRecordings] = useState<Recording[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [playingId, setPlayingId] = useState<string | null>(null);
  const [playingUrl, setPlayingUrl] = useState<string | null>(null);
  const [loadingPlay, setLoadingPlay] = useState<string | null>(null);
  const audioRef = useRef<HTMLAudioElement>(null);

  useEffect(() => {
    fetch('/api/recordings')
      .then((r) => r.json())
      .then((data) => {
        if (Array.isArray(data)) setRecordings(data);
        else setError(data.error ?? 'Unknown error');
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  }, []);

  async function handlePlay(rec: Recording) {
    if (playingId === rec.id) {
      // Toggle pause/resume
      if (audioRef.current?.paused) {
        audioRef.current.play();
      } else {
        audioRef.current?.pause();
      }
      return;
    }
    setLoadingPlay(rec.id);
    try {
      const res = await fetch(`/api/signed-url?path=${encodeURIComponent(rec.storage_path)}`);
      const json = await res.json();
      if (!res.ok) throw new Error(json.error);
      setPlayingId(rec.id);
      setPlayingUrl(json.signedUrl);
    } catch (e) {
      alert(`Could not load audio: ${e}`);
    } finally {
      setLoadingPlay(null);
    }
  }

  // Auto-play when URL changes
  useEffect(() => {
    if (playingUrl && audioRef.current) {
      audioRef.current.src = playingUrl;
      audioRef.current.play().catch(() => {});
    }
  }, [playingUrl]);

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className="bg-primary text-white px-6 py-4 shadow">
        <h1 className="text-xl font-bold tracking-wide">Call Recordings</h1>
        <p className="text-sm text-blue-200 mt-0.5">
          {recordings.length} recording{recordings.length !== 1 ? 's' : ''}
        </p>
      </header>

      {/* Sticky audio player */}
      {playingId && (
        <div className="sticky top-0 z-10 bg-white border-b border-gray-200 px-6 py-3 shadow-sm flex items-center gap-4">
          <span className="text-sm font-medium text-gray-700 flex-shrink-0">
            {recordings.find((r) => r.id === playingId)?.file_name ?? 'Playing…'}
          </span>
          <audio
            ref={audioRef}
            controls
            className="flex-1 h-8"
            onEnded={() => setPlayingId(null)}
          />
        </div>
      )}

      <main className="max-w-3xl mx-auto px-4 py-6">
        {loading && (
          <p className="text-center text-gray-500 py-16">Loading recordings…</p>
        )}
        {error && (
          <div className="bg-red-50 border border-red-200 rounded-lg p-4 text-red-700">
            {error}
          </div>
        )}

        {!loading && !error && recordings.length === 0 && (
          <div className="text-center text-gray-500 py-16">
            <p className="text-4xl mb-3">🎙️</p>
            <p className="font-medium">No recordings yet</p>
            <p className="text-sm mt-1">Make a call with the Android app to see recordings here.</p>
          </div>
        )}

        <div className="space-y-3">
          {recordings.map((rec) => (
            <div
              key={rec.id}
              className={`bg-white rounded-xl shadow-sm border p-4 transition-all ${
                playingId === rec.id ? 'border-primary ring-1 ring-primary' : 'border-gray-200'
              }`}
            >
              <div className="flex items-start justify-between gap-3">
                {/* Left: number + badges */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-semibold text-gray-900 truncate">
                      {rec.number || 'Unknown'}
                    </span>
                    {directionBadge(rec.direction)}
                    {sourceBadge(rec.source_used)}
                  </div>
                  <div className="mt-1 text-sm text-gray-500 flex items-center gap-3 flex-wrap">
                    <span>{formatDate(rec.recorded_at)}</span>
                    <span>·</span>
                    <span>{formatDuration(rec.duration_ms)}</span>
                  </div>
                  <div className="mt-0.5 text-xs text-gray-400 font-mono truncate">
                    {rec.file_name}
                  </div>
                </div>

                {/* Right: play button */}
                <button
                  onClick={() => handlePlay(rec)}
                  disabled={loadingPlay === rec.id}
                  className={`flex-shrink-0 w-10 h-10 rounded-full flex items-center justify-center transition-colors ${
                    playingId === rec.id
                      ? 'bg-primary text-white'
                      : 'bg-gray-100 hover:bg-primary hover:text-white text-gray-600'
                  } disabled:opacity-50`}
                  title="Play"
                >
                  {loadingPlay === rec.id ? (
                    <svg className="animate-spin w-4 h-4" viewBox="0 0 24 24" fill="none">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                    </svg>
                  ) : playingId === rec.id ? (
                    <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
                      <rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/>
                    </svg>
                  ) : (
                    <svg className="w-4 h-4 ml-0.5" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M8 5v14l11-7z"/>
                    </svg>
                  )}
                </button>
              </div>
            </div>
          ))}
        </div>
      </main>
    </div>
  );
}
