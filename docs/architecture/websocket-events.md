# Room and WebSocket event flow

```mermaid
sequenceDiagram
  participant A as Client A
  participant API as REST
  participant IO as Socket.IO
  participant B as Client B
  A->>API: POST /rooms
  A->>IO: join_room
  IO-->>A: room_state
  B->>API: POST /rooms/:code/join
  API-->>IO: user_joined
  IO-->>A: user_joined
  B->>IO: join_room
  IO-->>B: room_state
  A->>API: POST /rooms/:id/drafts
  API-->>IO: draft_shared
  B->>API: POST /rooms/:id/leave
  API-->>IO: user_left
  Note over B,IO: reconnect_state emits room_state
```

Mandatory events: `user_joined`, `user_left`, `draft_shared`, `spin_started`, `user_eliminated`, `winner_announced`, `room_state`.

Disconnect: membership stays until REST leave. `connected` is set false after `DISCONNECT_GRACE_MS` (default 15s) if the socket does not return. A spin is not aborted because the owner disconnects; the server timer continues.
