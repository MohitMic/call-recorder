# Call Recorder Development

This project implements a foreground-service based call recording flow with telemetry and background upload.

## Implemented Milestones

- Foundation and permissions:
  - Main screen with controls for permission request, accessibility settings, start/stop service.
  - Foreground service with permanent notification.
- Call detection orchestration:
  - Telephony callback and legacy call state listener.
  - Outgoing call receiver and accessibility backup trigger integration.
- Recording engine:
  - MediaRecorder source fallback: `VOICE_COMMUNICATION`, `VOICE_RECOGNITION`, `MIC`.
  - Session-safe start/stop with file naming in `CallRecordings`.
- Upload pipeline:
  - WorkManager worker with network constraints and exponential backoff.
  - Multipart upload payload including metadata.
- Lifecycle resilience:
  - Boot receiver auto-start support.
  - Stop action from notification.
- Hardening:
  - Lifecycle metric logging counters in service transitions and failures.

## Device Compatibility Test Matrix

| Device | ROM | Outgoing | Incoming | Recommended source | Notes |
|---|---|---|---|---|---|
| Vivo | Funtouch OS | Verify both sides | Verify both sides | `VOICE_COMMUNICATION` | Usually best quality |
| OnePlus | Oxygen OS 16 | Verify both sides | Verify both sides | `VOICE_RECOGNITION` fallback | Accessibility service should stay enabled |

## Privacy and Compliance Checklist

- Ask explicit user consent before recording.
- Show clear disclosure that call recording laws vary by region.
- Provide in-app option to disable auto-upload.
- Allow users to keep local recordings or delete after upload.
- Exclude logs from containing full phone numbers.

## Next Setup

- Replace `https://your-api.example.com/call-recordings` in `UploadWorker`.
- Add app signing, Play policy checks, and OEM battery optimization guidance in release docs.
