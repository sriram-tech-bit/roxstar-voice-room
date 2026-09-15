CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  display_name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE rooms (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code TEXT NOT NULL UNIQUE,
  owner_id UUID NOT NULL REFERENCES users(id),
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rooms_code ON rooms(code);
CREATE INDEX idx_rooms_owner ON rooms(owner_id);

CREATE TABLE room_members (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id),
  role TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'member')),
  connected BOOLEAN NOT NULL DEFAULT TRUE,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  left_at TIMESTAMPTZ,
  UNIQUE (room_id, user_id)
);

CREATE INDEX idx_room_members_room ON room_members(room_id);
CREATE INDEX idx_room_members_user ON room_members(user_id);
CREATE INDEX idx_room_members_active ON room_members(room_id) WHERE left_at IS NULL;

CREATE TABLE drafts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id),
  room_id UUID REFERENCES rooms(id) ON DELETE SET NULL,
  name TEXT NOT NULL,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  storage_url TEXT,
  effect TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_drafts_user ON drafts(user_id);
CREATE INDEX idx_drafts_room ON drafts(room_id);

CREATE TABLE spins (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK (status IN ('waiting', 'running', 'completed', 'aborted')),
  started_by UUID NOT NULL REFERENCES users(id),
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  winner_id UUID REFERENCES users(id),
  virtual_points INTEGER NOT NULL DEFAULT 0,
  next_elimination_at TIMESTAMPTZ,
  abort_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_spins_room_status ON spins(room_id, status);
CREATE UNIQUE INDEX idx_spins_one_running ON spins(room_id) WHERE status = 'running';

CREATE TABLE spin_participants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  spin_id UUID NOT NULL REFERENCES spins(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id),
  status TEXT NOT NULL CHECK (status IN ('active', 'eliminated', 'left', 'winner')),
  elimination_order INTEGER,
  eliminated_at TIMESTAMPTZ,
  UNIQUE (spin_id, user_id)
);

CREATE INDEX idx_spin_participants_spin ON spin_participants(spin_id);

CREATE TABLE spin_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  spin_id UUID NOT NULL REFERENCES spins(id) ON DELETE CASCADE,
  type TEXT NOT NULL,
  payload JSONB NOT NULL DEFAULT '{}',
  seq INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (spin_id, seq)
);

CREATE INDEX idx_spin_events_spin_seq ON spin_events(spin_id, seq);
