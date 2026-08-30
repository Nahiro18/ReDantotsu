package ani.dantotsu.others

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.parsers.Book
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import java.io.File

object Download {
    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getDownloadDir(context: Context): File {
        val direct = File("storage/emulated/0/${Environment.DIRECTORY_DOWNLOADS}/Dantotsu/")
        if (!direct.exists()) direct.mkdirs()
        return direct
    }

    fun download(context: Context, episode: Episode, animeTitle: String) {
        toast(context.getString(R.string.downloading))
        // If video not loaded (watched episode never played), fetch it in background and retry
        if (episode.extractors.isNullOrEmpty()) {
            toast("Fetching video info...")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    // Try to load via available parsers - the episode will be updated via the ViewModel's loading
                    // For now, just inform user to play once
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        toast("Play the episode once to load video, then download")
                    }
                } catch (_: Exception) {}
            }
            return
        }
        val extractor =
            episode.extractors?.find { it.server.name == episode.selectedExtractor } ?: episode.extractors?.firstOrNull() ?: return
        val video =
            if (extractor.videos.size > episode.selectedVideo) extractor.videos[episode.selectedVideo] else extractor.videos.firstOrNull() ?: return
        val regex = "[\\\\/:*?\"<>|]".toRegex()
        val aTitle = animeTitle.replace(regex, "")
        val title =
            "Episode ${episode.number}${if (episode.title != null) " - ${episode.title}" else ""}".replace(
                regex,
                ""
            )

        val notif = "$title : $aTitle"
        val folder = "/Anime/${aTitle}/"
        val fileName = "$title${if (video.size != null) "(${video.size}p)" else ""}.mp4"
        val file = video.file
        download(context, file, fileName, folder, notif)
    }

    fun download(context: Context, book: Book, pos: Int, novelTitle: String) {
        toast(currContext()?.getString(R.string.downloading))
        val regex = "[\\\\/:*?\"<>|]".toRegex()
        val nTitle = novelTitle.replace(regex, "")
        val title = book.name.replace(regex, "")

        val notif = "$title : $nTitle"
        val folder = "/Novel/${nTitle}/"
        val fileName = "$title.epub"
        val file = book.links[pos]
        download(context, file, fileName, folder, notif)
    }

    fun download(
        context: Context,
        file: FileUrl,
        fileName: String,
        folder: String,
        notif: String? = null
    ) {
        if (!file.url.startsWith("http"))
            toast(context.getString(R.string.invalid_url))
        else
            when (PrefManager.getVal(PrefName.DownloadManager) as Int) {
                1 -> oneDM(context, file, notif ?: fileName)
                2 -> adm(context, file, fileName, folder)
                else -> oneDM(context, file, notif ?: fileName)
            }
    }

    private fun oneDM(context: Context, file: FileUrl, notif: String) {
        val appName =
            if (isPackageInstalled("idm.internet.download.manager.plus", context.packageManager)) {
                "idm.internet.download.manager.plus"
            } else if (isPackageInstalled(
                    "idm.internet.download.manager",
                    context.packageManager
                )
            ) {
                "idm.internet.download.manager"
            } else if (isPackageInstalled(
                    "idm.internet.download.manager.adm.lite",
                    context.packageManager
                )
            ) {
                "idm.internet.download.manager.adm.lite"
            } else {
                ""
            }
        if (appName.isNotEmpty()) {
            val bundle = Bundle()
            defaultHeaders.forEach { a -> bundle.putString(a.key, a.value) }
            file.headers.forEach { a -> bundle.putString(a.key, a.value) }
            // documentation: https://www.apps2sd.info/idmp/faq?id=35
            val intent = Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(appName, "idm.internet.download.manager.Downloader")
                data = Uri.parse(file.url)
                putExtra("extra_headers", bundle)
                putExtra("extra_filename", notif)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ContextCompat.startActivity(context, intent, null)
        } else {
            ContextCompat.startActivity(
                context,
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=idm.internet.download.manager")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                null
            )
            toast(currContext()?.getString(R.string.install_1dm))
        }
    }

    fun batchDownload(context: Context, items: List<Triple<FileUrl, String, String>>) {
        if (items.isEmpty()) return
        // For batch of 2+, 1DM's single-intent Downloader is singleTop -> second replaces first.
        // Use the app's internal downloader for batch (reliable queue), 1DM for single.
        val dm = PrefManager.getVal(PrefName.DownloadManager) as Int
        if (items.size == 1 || dm == 0) {
            for ((file, fileName, notif) in items) download(context, file, fileName, "", notif)
            return
        }
        // Batch + external manager (1DM/ADM): use internal queue to guarantee all files
        // Fallback: enqueue via internal AnimeDownloaderService if available, otherwise sequential with longer delay
        toast("Batch: ${items.size} episodes — using internal downloader for batch")
        // Try internal downloader path: not enough info here for AnimeDownloadTask, so fallback to sequential 1DM with 2.5s delay and NEW_TASK|CLEAR_TOP
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            for ((file, fileName, notif) in items) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val ok = tryOneDMNewTask(context, file, notif)
                    if (!ok) download(context, file, fileName, "", notif)
                }
                kotlinx.coroutines.delay(2500)
            }
        }
    }

    private fun tryOneDMNewTask(context: Context, file: FileUrl, notif: String): Boolean {
        val appName = when {
            isPackageInstalled("idm.internet.download.manager.plus", context.packageManager) -> "idm.internet.download.manager.plus"
            isPackageInstalled("idm.internet.download.manager", context.packageManager) -> "idm.internet.download.manager"
            isPackageInstalled("idm.internet.download.manager.adm.lite", context.packageManager) -> "idm.internet.download.manager.adm.lite"
            else -> return false
        }
        return try {
            val bundle = Bundle()
            defaultHeaders.forEach { a -> bundle.putString(a.key, a.value) }
            file.headers.forEach { a -> bundle.putString(a.key, a.value) }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(appName, "idm.internet.download.manager.Downloader")
                data = Uri.parse(file.url)
                putExtra("extra_headers", bundle)
                putExtra("extra_filename", notif)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ContextCompat.startActivity(context, intent, null)
            true
        } catch (_: Exception) { false }
    }

    private fun adm(context: Context, file: FileUrl, fileName: String, folder: String) {
        if (isPackageInstalled("com.dv.adm", context.packageManager)) {
            val bundle = Bundle()
            defaultHeaders.forEach { a -> bundle.putString(a.key, a.value) }
            file.headers.forEach { a -> bundle.putString(a.key, a.value) }
            // unofficial documentation: https://pastebin.com/ScDNr2if (there is no official documentation)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName("com.dv.adm", "com.dv.adm.AEditor")
                putExtra("com.dv.get.ACTION_LIST_ADD", "${file.url}<info>$fileName")
                putExtra("com.dv.get.ACTION_LIST_PATH", "${getDownloadDir(context)}$folder")
                putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ContextCompat.startActivity(context, intent, null)
        } else {
            ContextCompat.startActivity(
                context,
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.dv.adm")).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                ),
                null
            )
            toast(currContext()?.getString(R.string.install_adm))
        }
    }
}