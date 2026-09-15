# Database

PostgreSQL schema for users, rooms, membership, drafts, spins, participants and auditable spin events.

Apply migrations:

```bash
cd backend
npx --yes dotenv -e ../.env -- node src/migrate.js
```

Or start the API; it migrates on boot.

See `migrations/001_init.sql` and `docs/architecture/system.md` for keys and indexes.
