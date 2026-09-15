# Room + spin integration (manual / demo)

Requires `DATABASE_URL` and `npm start` in `backend`.

```bash
# 1. Three users
U1=$(curl -s -X POST localhost:8080/users -H 'content-type: application/json' -d '{"displayName":"A"}' | jq -r .id)
U2=$(curl -s -X POST localhost:8080/users -H 'content-type: application/json' -d '{"displayName":"B"}' | jq -r .id)
U3=$(curl -s -X POST localhost:8080/users -H 'content-type: application/json' -d '{"displayName":"C"}' | jq -r .id)

# 2. Owner creates room
ROOM=$(curl -s -X POST localhost:8080/rooms -H "X-User-Id: $U1" | jq -r .room.id)
CODE=$(curl -s localhost:8080/rooms/$ROOM | jq -r .room.code)

curl -s -X POST localhost:8080/rooms/$CODE/join -H "X-User-Id: $U2"
curl -s -X POST localhost:8080/rooms/$CODE/join -H "X-User-Id: $U3"

# Expected failure: spin with owner-only room before joins would return INSUFFICIENT_PLAYERS

# 3. Start spin (owner)
curl -s -X POST localhost:8080/rooms/$ROOM/spins -H "X-User-Id: $U1"

# Duplicate start is idempotent (HTTP 200, duplicate=true)
curl -s -X POST localhost:8080/rooms/$ROOM/spins -H "X-User-Id: $U1"

# 4. Poll result
sleep 12
curl -s localhost:8080/rooms/$ROOM/state
```

Socket.IO events to assert: `user_joined`, `spin_started`, `user_eliminated` every 5s, `winner_announced`, `room_state` after reconnect.
