package dev.local.rebuy.cart

import org.json.JSONObject

internal object CartBatchSnapshotMigration {
    fun toCurrent(json: JSONObject): JSONObject {
        when (json.getInt("version")) {
            1 -> {
                val entries = json.getJSONArray("entries")
                require(entries.length() <= CartBatchValidation.MAX_TARGETS)
                for (index in 0 until entries.length()) entries.getJSONObject(index).put("maxTotalWon", JSONObject.NULL)
                json.put("version", 2)
            }
            2, 3 -> Unit
            else -> throw IllegalArgumentException("지원하지 않는 묶음 기록 버전입니다.")
        }
        val entries = json.getJSONArray("entries")
        require(entries.length() <= CartBatchValidation.MAX_TARGETS)
        for (index in 0 until entries.length()) require(entries.getJSONObject(index).has("maxTotalWon"))
        json.put("version", 3)
        return json
    }
}
