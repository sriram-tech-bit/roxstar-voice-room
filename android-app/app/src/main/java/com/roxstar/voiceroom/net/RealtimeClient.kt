package com.roxstar.voiceroom.net

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

class RealtimeClient {
    private var socket: Socket? = null

    fun connect(baseUrl: String, roomId: String, userId: String, onEvent: (String, JSONObject) -> Unit) {
        disconnect()
        val opts = IO.Options.builder().setForceNew(true).setReconnection(true).build()
        val s = IO.socket(baseUrl, opts)
        socket = s
        listOf(
            "room_state",
            "user_joined",
            "user_left",
            "draft_shared",
            "spin_started",
            "user_eliminated",
            "winner_announced"
        ).forEach { event ->
            s.on(event) { args ->
                val payload = args.firstOrNull() as? JSONObject ?: JSONObject()
                onEvent(event, payload)
            }
        }
        s.on(Socket.EVENT_CONNECT) {
            val join = JSONObject().put("roomId", roomId).put("userId", userId)
            s.emit("join_room", join)
        }
        s.connect()
    }

    fun reconnectState(roomId: String, userId: String) {
        val payload = JSONObject().put("roomId", roomId).put("userId", userId)
        socket?.emit("reconnect_state", payload)
    }

    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
    }
}
