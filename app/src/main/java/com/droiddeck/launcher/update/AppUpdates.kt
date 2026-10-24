package com.droiddeck.launcher.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

/** Stable releases of DroidDeck Patched: the latest GitHub release of [REPO] that carries an APK. */
object AppUpdates {
    const val REPO = "Novicek000/DroidDeck-Patched"
    private const val RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOADS = "https://github.com/$REPO/releases/download/"
    private const val PREFS = "app_updates"
    private const val KEY_CATALOG = "patched_catalog"

    class Apk(
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    class Release(
        val tag: String,
        val title: String,
        val summary: String,
        val version: String,
        val publishedAt: Long,
        val url: String,
        val apk: Apk?,
    )

    class Catalog(
        val stable: Release?,
        val checkedAt: Long,
    )

    class Installed(
        val version: String,
        val versionCode: Int,
        val updatable: Boolean,
    )

    enum class Offer {
        CURRENT,
        UPDATE,
        AHEAD,
        NONE,
    }

    @Volatile private var releaseSigned = BuildConfig.CI_BUILD

    fun init(context: Context) {
        releaseSigned = runCatching {
            signers(context).any { it.equals(BuildConfig.RELEASE_SIGNER, ignoreCase = true) }
        }.getOrDefault(BuildConfig.CI_BUILD)
    }

    private fun signers(context: Context): List<String> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ).signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptyList()
        return certs.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    fun installed(): Installed = Installed(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, releaseSigned)

    fun offer(catalog: Catalog, me: Installed = installed()): Offer {
        val r = catalog.stable ?: return Offer.NONE
        val c = compareVersions(r.version, me.version)
        return when {
            c > 0 -> Offer.UPDATE
            c == 0 -> Offer.CURRENT
            else -> Offer.AHEAD
        }
    }

    fun hasUpdate(catalog: Catalog?): Boolean {
        if (catalog == null) return false
        val me = installed()
        return me.updatable && offer(catalog, me) == Offer.UPDATE && catalog.stable?.apk != null
    }

    fun cached(context: Context): Catalog? {
        val raw = prefs(context).getString(KEY_CATALOG, null) ?: return null
        return runCatching { readCachedCatalog(JSONObject(raw)) }.getOrNull()
    }

    fun refresh(context: Context): Catalog {
        val checkedAt = System.currentTimeMillis()
        val body = get(context)
        val catalog = try {
            Catalog(body?.let { readPublishedRelease(JSONObject(it)) }, checkedAt)
        } catch (e: Exception) {
            throw IOException(context.getString(R.string.appupd_catalog_rejected, e.message), e)
        }
        prefs(context).edit().putString(KEY_CATALOG, writeCatalog(catalog).toString()).apply()
        return catalog
    }

    /** A release from GitHub's REST API; its APK asset must be served from [REPO]'s own releases. */
    internal fun readPublishedRelease(o: JSONObject): Release {
        val tag = o.getString("tag_name")
        val version = tag.removePrefix("v")
        if (!Regex("^[0-9]+(\\.[0-9]+)*(-[0-9A-Za-z.]+)?$").matches(version)) {
            throw IOException("The release tag $tag is not a version")
        }
        val assets = o.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets!!.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) && it.optString("state", "uploaded") == "uploaded" }
            ?.let(::readPublishedApk)
        return Release(
            tag,
            o.optString("name").ifBlank { tag },
            o.optString("body"),
            version,
            runCatching { Instant.parse(o.optString("published_at")).toEpochMilli() }.getOrDefault(0L),
            o.optString("html_url"),
            apk,
        )
    }

    private fun readPublishedApk(o: JSONObject): Apk? {
        val name = o.getString("name")
        val url = o.getString("browser_download_url")
        if (!url.startsWith(DOWNLOADS) || name.contains('/') || name.contains('\\')) {
            throw IOException("The release has an unexpected download URL")
        }
        val sha = o.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
            ?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) } ?: return null
        return Apk(name, url, o.optLong("size"), sha)
    }

    fun compareVersions(a: String, b: String): Int {
        val x = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** The latest release's JSON, or null when the repository has published none yet. */
    private fun get(context: Context): String? {
        val c = URL(RELEASE_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "DroidDeck-Patched-app")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            val code = c.responseCode
            if (code == 404) return null
            if (code != 200) throw IOException(context.getString(R.string.appupd_http, code))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun writeRelease(r: Release): JSONObject = JSONObject()
        .put("tag", r.tag)
        .put("title", r.title)
        .put("summary", r.summary)
        .put("version", r.version)
        .put("publishedAt", r.publishedAt)
        .put("url", r.url)
        .put("apk", r.apk?.let {
            JSONObject().put("name", it.name).put("url", it.url).put("size", it.size).put("sha256", it.sha256)
        })

    private fun readCachedRelease(o: JSONObject): Release = Release(
        o.getString("tag"),
        o.optString("title"),
        o.optString("summary"),
        o.getString("version"),
        o.optLong("publishedAt"),
        o.optString("url"),
        o.optJSONObject("apk")?.let {
            val url = it.getString("url")
            if (!url.startsWith(DOWNLOADS)) throw IOException("cached download URL is not from $REPO")
            Apk(it.getString("name"), url, it.optLong("size"), it.getString("sha256"))
        },
    )

    internal fun writeCatalog(c: Catalog): JSONObject = JSONObject()
        .put("stable", c.stable?.let(::writeRelease))
        .put("checkedAt", c.checkedAt)

    internal fun readCachedCatalog(o: JSONObject): Catalog =
        Catalog(o.optJSONObject("stable")?.let(::readCachedRelease), o.optLong("checkedAt"))

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
