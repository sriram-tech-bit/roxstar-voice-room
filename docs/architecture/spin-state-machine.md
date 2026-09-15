# Spin state machine

```mermaid
stateDiagram-v2
  [*] --> waiting: room idle
  waiting --> running: owner POST /spins, 3-20 members
  running --> completed: one participant remains
  running --> aborted: last remaining player leaves
  completed --> [*]
  aborted --> [*]
```

```mermaid
sequenceDiagram
  participant Owner
  participant API
  participant Timer
  participant Members
  Owner->>API: start spin
  API->>Members: spin_started
  loop every 5 seconds while 2+ active
    Timer->>API: eliminate one random active user
    API->>Members: user_eliminated
  end
  API->>Members: winner_announced
```

Transitions are persisted (`spins.status`, `spin_participants`, append-only `spin_events.seq`). Duplicate `POST /spins` while `running` returns the same spin (`duplicate: true`). Joins after start are room members but not spin participants. Server restart reloads `running` spins and reschedules from `next_elimination_at`. Late ticks eliminate **once**, then wait 5s from **now**.
