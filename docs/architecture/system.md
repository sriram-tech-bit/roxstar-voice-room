# System architecture

```mermaid
flowchart LR
  subgraph android [Android app]
    UI[Kotlin UI]
    Drafts[Draft store]
    JNI[JNI]
    Oboe[Oboe native path]
    UI --> Drafts
    UI --> JNI --> Oboe
    UI --> HTTP[REST]
    UI --> WS[Socket.IO]
  end
  subgraph cloud [Node.js backend]
    API[Express REST]
    IO[Socket.IO]
    Spin[Spin scheduler]
    DB[(PostgreSQL)]
    API --> DB
    IO --> DB
    Spin --> DB
  end
  HTTP --> API
  WS --> IO
```

The backend is authoritative for room membership, spin lifecycle and winners. WebSocket does not carry live audio. Draft audio files stay on-device; only metadata is shared with the room.

Identity: `X-User-Id` header after `POST /users`. No OAuth in this assessment.
