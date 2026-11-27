package com.komic.reader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val repository = "Accelerating/Komic"
private val client = OkHttpClient()
private val stableTag = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

private fun parts(tag: String): List<Int>? = stableTag.matchEntire(tag)?.groupValues?.drop(1)?.map {
    it.toIntOrNull() ?: return null
}

private data class Update(val tag: String, val asset: String)

private fun latest(installed: String): Update? {
    val current = parts(installed) ?: return null
    val request = Request.Builder().url("https://api.github.com/repos/$repository/releases/latest").build()
    return client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
        val release = JSONObject(requireNotNull(response.body).string())
        val tag = release.getString("tag_name")
        val next = parts(tag) ?: return null
        val difference = next.indices.firstOrNull { next[it] != current[it] } ?: return null
        if (next[difference] < current[difference]) return null
        val assets = release.getJSONArray("assets")
        val names = (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }
        val asset = listOf("Komic-$tag.apk", "Komic.apk").firstOrNull { it in names } ?: throw java.io.IOException("Missing APK")
        Update(tag, asset)
    }
}

@Composable
internal fun UpdatePrompt(activity: MainActivity, request: Int) {
    var available by remember { mutableStateOf<Update?>(null) }
    LaunchedEffect(request) {
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: return@LaunchedEffect
        fun notify(message: Int) {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        if (request > 0) notify(R.string.checking_updates)
        try {
            available = withContext(Dispatchers.IO) { latest(installed) }
            if (request > 0 && available == null) notify(R.string.up_to_date)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (request > 0) notify(R.string.update_check_failed) }
    }
    val update = available ?: return
    AlertDialog(onDismissRequest = { available = null },
        title = { Text(activity.getString(R.string.update_title, update.tag)) },
        confirmButton = { TextButton(onClick = {
            val uri = Uri.parse("https://github.com/$repository/releases/download/${update.tag}/${update.asset}")
            val request = DownloadManager.Request(uri)
                .setMimeType("application/vnd.android.package-archive")
                .setTitle("Komic-${update.tag}.apk")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Komic-${update.tag}.apk")
            }
            try {
                (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            } catch (_: RuntimeException) {
                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
            }
            available = null
        }) { Text(activity.getString(R.string.update_download)) } },
        dismissButton = { TextButton(onClick = { available = null }) { Text(activity.getString(R.string.update_later)) } })
}
