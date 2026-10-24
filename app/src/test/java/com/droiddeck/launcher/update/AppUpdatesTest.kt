package com.droiddeck.launcher.update

import com.droiddeck.launcher.update.AppUpdates.Installed
import com.droiddeck.launcher.update.AppUpdates.Offer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AppUpdatesTest {
    private val sha = "ab".repeat(32)

    private fun asset(
        name: String = "DroidDeck-Patched-0.6.0-patched.2.apk",
        url: String = "https://github.com/${AppUpdates.REPO}/releases/download/v0.6.0-patched.2/$name",
        digest: String? = "sha256:$sha",
    ) = JSONObject().put("name", name).put("browser_download_url", url).put("size", 1234L).put("state", "uploaded")
        .apply { if (digest != null) put("digest", digest) }

    private fun published(tag: String = "v0.6.0-patched.2", vararg assets: JSONObject = arrayOf(asset())) = JSONObject()
        .put("tag_name", tag)
        .put("name", "DroidDeck Patched $tag")
        .put("body", "notes")
        .put("published_at", "2026-10-24T10:00:00Z")
        .put("html_url", "https://github.com/${AppUpdates.REPO}/releases/tag/$tag")
        .put("assets", JSONArray().apply { assets.forEach { put(it) } })

    private fun me(version: String) = Installed(version, 1501, updatable = true)

    private fun apkOf(r: AppUpdates.Release): AppUpdates.Apk {
        assertNotNull(r.apk)
        return r.apk!!
    }

    @Test
    fun readsTheReleaseAndItsApk() {
        val r = AppUpdates.readPublishedRelease(published())
        assertEquals("0.6.0-patched.2", r.version)
        assertEquals(1792836000000L, r.publishedAt)
        val apk = apkOf(r)
        assertEquals(sha, apk.sha256)
        assertEquals(1234L, apk.size)
        assertTrue(apk.url.startsWith("https://github.com/${AppUpdates.REPO}/releases/download/"))
    }

    @Test
    fun skipsAssetsThatAreNotApks() {
        val r = AppUpdates.readPublishedRelease(published("v0.6.0-patched.2", asset("notes.txt"), asset()))
        assertEquals("DroidDeck-Patched-0.6.0-patched.2.apk", apkOf(r).name)
    }

    @Test
    fun anApkWithoutAChecksumIsNotOffered() {
        assertNull(AppUpdates.readPublishedRelease(published("v0.6.0-patched.2", asset(digest = null))).apk)
        assertNull(AppUpdates.readPublishedRelease(published("v0.6.0-patched.2", asset(digest = "sha1:abc"))).apk)
    }

    @Test
    fun rejectsDownloadsFromAnotherRepository() {
        try {
            AppUpdates.readPublishedRelease(
                published("v0.6.0-patched.2", asset(url = "https://github.com/Droid-Deck/DroidDeck/releases/download/0.6.0/DroidDeck-0.6.0.apk")),
            )
            fail("a foreign download URL was accepted")
        } catch (_: IOException) {
        }
    }

    @Test
    fun rejectsATagThatIsNotAVersion() {
        try {
            AppUpdates.readPublishedRelease(published("latest"))
            fail("a non-version tag was accepted")
        } catch (_: IOException) {
        }
    }

    @Test
    fun offersOnlyNewerReleases() {
        val catalog = AppUpdates.Catalog(AppUpdates.readPublishedRelease(published()), 0L)
        assertEquals(Offer.UPDATE, AppUpdates.offer(catalog, me("0.6.0-patched.1")))
        assertEquals(Offer.CURRENT, AppUpdates.offer(catalog, me("0.6.0-patched.2")))
        assertEquals(Offer.AHEAD, AppUpdates.offer(catalog, me("0.6.0-patched.3")))
        assertEquals(Offer.NONE, AppUpdates.offer(AppUpdates.Catalog(null, 0L), me("0.6.0-patched.1")))
    }

    @Test
    fun patchedVersionsOrderAfterTheirUpstreamRelease() {
        assertTrue(AppUpdates.compareVersions("0.6.0-patched.1", "0.6.0") > 0)
        assertTrue(AppUpdates.compareVersions("0.6.0-patched.10", "0.6.0-patched.9") > 0)
        assertTrue(AppUpdates.compareVersions("0.7.0-patched.1", "0.6.0-patched.12") > 0)
        assertEquals(0, AppUpdates.compareVersions("v0.6.0-patched.1", "0.6.0-patched.1"))
    }

    @Test
    fun theCacheRoundTrips() {
        val catalog = AppUpdates.Catalog(AppUpdates.readPublishedRelease(published()), 42L)
        val back = AppUpdates.readCachedCatalog(JSONObject(AppUpdates.writeCatalog(catalog).toString()))
        assertEquals(42L, back.checkedAt)
        assertEquals("0.6.0-patched.2", back.stable?.version)
        assertEquals(sha, back.stable?.apk?.sha256)
        assertNull(AppUpdates.readCachedCatalog(JSONObject(AppUpdates.writeCatalog(AppUpdates.Catalog(null, 1L)).toString())).stable)
    }
}
