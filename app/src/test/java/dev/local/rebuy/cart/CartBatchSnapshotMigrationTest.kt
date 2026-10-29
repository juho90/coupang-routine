package dev.local.rebuy.cart

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CartBatchSnapshotMigrationTest {
    @Test fun versionOneGetsExplicitNullLimitWithoutChangingPreviousResults() {
        val source = JSONObject("""{"version":1,"entries":[{"status":"ADDED","url":"retained"}]}""")
        val result = CartBatchSnapshotMigration.toCurrent(source)
        assertEquals(3, result.getInt("version"))
        val entry = result.getJSONArray("entries").getJSONObject(0)
        assertTrue(entry.has("maxTotalWon"))
        assertTrue(entry.isNull("maxTotalWon"))
        assertEquals("ADDED", entry.getString("status"))
        assertEquals("retained", entry.getString("url"))
    }

    @Test fun versionTwoRetainsLimitAndRejectsMissingFieldsAndUnknownVersion() {
        val source = JSONObject("""{"version":2,"entries":[{"maxTotalWon":3880}]}""")
        assertEquals(3880, CartBatchSnapshotMigration.toCurrent(source).getJSONArray("entries").getJSONObject(0).getInt("maxTotalWon"))
        assertEquals(3, source.getInt("version"))
        assertTrue(runCatching { CartBatchSnapshotMigration.toCurrent(JSONObject("""{"version":2,"entries":[{}]}""")) }.isFailure)
        assertTrue(runCatching { CartBatchSnapshotMigration.toCurrent(JSONObject("""{"version":4,"entries":[]}""")) }.isFailure)
    }
    @Test fun versionThreeKeepsSkippedAndReviewStatesWithoutInventingAdditions() {
        val source = JSONObject("""{"version":3,"entries":[{"status":"SKIPPED","maxTotalWon":null},{"status":"REVIEW_REQUIRED","maxTotalWon":null}]}""")
        val result = CartBatchSnapshotMigration.toCurrent(source)
        assertEquals("SKIPPED", result.getJSONArray("entries").getJSONObject(0).getString("status"))
        assertEquals("REVIEW_REQUIRED", result.getJSONArray("entries").getJSONObject(1).getString("status"))
    }

}
