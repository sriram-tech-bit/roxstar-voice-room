# Assumptions, edge cases, trade-offs, limitations

## Assumptions

- Virtual points only (winner receives 100 points). No wallet or payments.
- Live streaming, WebRTC, LiveKit, AI, Kubernetes and multi-tenant SaaS are out of scope.
- `X-User-Id` is a take-home identity header, not production auth.
- Shared drafts send metadata (name, duration, effect, storage URL). The WAV stays on the phone unless you add object storage later.
- Room capacity matches spin cap (20 active members).

## Implemented edge cases

1. Duplicate start → existing running spin, HTTP 200, timer not reset.
2. Join during a spin → member of the room, not eligible on the current wheel.
3. Leave during a spin → participant marked `left`, elimination event, possible winner if one remains.
4. Last remaining player leaves → spin `aborted` (`last_players_left`).
5. Reconnect → `reconnect_state` / `room_state` snapshot including spin history (`spin_events.seq` ignores duplicates).
6. Owner disconnect → spin continues on the server.
7. Insufficient players → `INSUFFICIENT_PLAYERS`; too many → `TOO_MANY_PLAYERS`.
8. Delayed timer → one catch-up elimination, next fire 5s from now (no burst).
9. Server restart → `scheduler.recover()` reschedules running spins.

## Trade-offs

- Echo is the single required effect so the native callback stays stable.
- PostgreSQL unique partial index enforces one running spin per room.
- Socket.IO for presence; REST for commands that must be idempotent and persisted.

## Known limitations

- Draft audio is not uploaded to cloud storage.
- Android UI is functional, not visually polished (PRD: reasoning over polish).
- Deployed to Render, not AWS/GCP/Azure, due to free-tier access constraints during this assessment window. The Dockerfile (`infrastructure/Dockerfile`) is cloud-agnostic and would deploy identically to Cloud Run, App Runner, or App Service given billing access. CI/CD (`.github/workflows/ci.yml`) runs on every push and is fully green; production database is Neon Postgres via `DATABASE_URL`.
- Disconnect grace does not auto-leave the room (only `connected=false`) so accidental network drops do not eject players from a spin.
