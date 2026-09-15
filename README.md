# Roxstar Voice Draft, Room and Spin Wheel

Take-home implementation of the Roxstar candidate assessment (200 points). Live audio streaming, WebRTC, wallets, Kubernetes and multi-tenant design are **not** included.

## Repository

```
/android-app      Kotlin client
/native-audio     Oboe C++ (record, Echo, WAV, playback)
/backend          Node.js REST + Socket.IO
/database         PostgreSQL migrations
/infrastructure   Docker, Compose, CI, cloud notes
/docs/architecture
/docs/api         OpenAPI
/tests
README.md
```

## Quick start (backend)

```bash
cp .env.example .env
cd infrastructure
docker compose up --build
```

API: http://localhost:8080/health  
Ready: http://localhost:8080/ready

Without Compose: run PostgreSQL 16, set `DATABASE_URL`, then:

```bash
cd backend
npm install
npm test
npm start
```

Identity header for mutating routes: `X-User-Id: <uuid from POST /users>`.

## Android

1. Open `android-app` in Android Studio (Giraffe+ / AGP 8.7).
2. Let Gradle fetch Oboe via CMake `FetchContent` (network required on first native build).
3. Physical device: set `API_BASE_URL` to your machine LAN IP, e.g. `-PAPI_BASE_URL=http://192.168.1.10:8080`.
4. Emulator default: `http://10.0.2.2:8080`.
5. Grant microphone permission. Record uses **Oboe + Echo**, then save / list / play / delete drafts.
6. Create or join a room, share a draft, start a spin as the owner with 3–20 members.

## Socket.IO events

| Event | When |
|---|---|
| `user_joined` | Member joined |
| `user_left` | Member left |
| `draft_shared` | Draft metadata shared |
| `spin_started` | Owner started a valid spin |
| `user_eliminated` | One participant removed (timer or leave) |
| `winner_announced` | Exactly one winner |
| `room_state` | Snapshot on connect / reconnect |

Client emits: `join_room`, `leave_room`, `reconnect_state`.

## Spin rules

- 3–20 eligible members, owner starts, one running spin per room.
- Eliminate one active participant every 5 seconds on the **server**.
- Last remaining participant wins 100 virtual points.
- `WAITING` is the idle room; spin rows are `running` → `completed` or `aborted`.

Edge cases are listed in `docs/architecture/assumptions.md`.

## Cloud / DevOps

- Image: `docker build -f infrastructure/Dockerfile -t roxstar-backend .`
- CI: `.github/workflows/ci.yml` installs, tests, builds the image.
- Deploy the image to **Cloud Run, ECS, App Runner or Azure Container Apps** with secret `DATABASE_URL` and a managed Postgres.
- Health: `GET /health`. Ready: `GET /ready`.
- Rollback: redeploy the previous image digest.
- Local-only backend is not a valid final submission; host the container and paste the URL in your README.

Example Cloud Run:

```bash
gcloud run deploy roxstar-backend \
  --source . \
  --set-secrets=DATABASE_URL=DATABASE_URL:latest \
  --allow-unauthenticated \
  --region=us-central1
```

(Use `--image` after pushing the Dockerfile build if `--source` is not used.)

## Docs

- Architecture: `docs/architecture/system.md`
- Audio: `docs/architecture/audio-flow.md`
- Events: `docs/architecture/websocket-events.md`
- Spin: `docs/architecture/spin-state-machine.md`
- API: `docs/api/openapi.yaml`

## Demonstration checklist

- [ ] Record via Oboe, Echo on, save / list / play / delete draft
- [ ] Two clients, `user_joined` / `user_left` / `draft_shared`
- [ ] Spin with ≥3 users, elimination every 5s, one winner
- [ ] Reconnect `room_state`
- [ ] Three edge cases (duplicate start, leave during spin, insufficient players)
- [ ] `npm test`, hosted `/health`, CI green
- [ ] One expected failure (`INSUFFICIENT_PLAYERS`) explained

## Submission fields

| Field | Value |
|---|---|
| Candidate name | |
| Role | Audio / Android, Backend, or DevOps |
| Repository | private Git URL |
| Demo recording | 5–10 min link |
| Cloud provider and endpoint | |
| Android device | |
| Implemented effect | Echo |
| Handled edge cases | see `docs/architecture/assumptions.md` |
| Self-assessed score / 200 | |
