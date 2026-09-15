package com.roxstar.voiceroom.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DraftStore(private val context: Context) {
    private val file = File(context.filesDir, "drafts.json")

    fun list(): List<Draft> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Draft(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        createdAt = o.getLong("createdAt"),
                        durationMs = o.getLong("durationMs"),
                        path = o.getString("path"),
                        effect = o.optString("effect", "echo")
                    )
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    fun save(draft: Draft) {
        val all = list().toMutableList()
        all.removeAll { it.id == draft.id }
        all.add(draft)
        persist(all)
    }

    fun delete(id: String) {
        val remaining = list().filterNot { it.id == id }
        persist(remaining)
    }

    fun newFile(): File {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        return File(dir, "${UUID.randomUUID()}.wav")
    }

    private fun persist(items: List<Draft>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("createdAt", it.createdAt)
                    .put("durationMs", it.durationMs)
                    .put("path", it.path)
                    .put("effect", it.effect)
            )
        }
        file.writeText(arr.toString())
    }
}
