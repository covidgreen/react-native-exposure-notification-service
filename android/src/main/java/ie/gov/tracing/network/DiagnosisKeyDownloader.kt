package ie.gov.tracing.network

import android.content.Context
import androidx.annotation.Keep
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.Gson
import ie.gov.tracing.common.Events
import ie.gov.tracing.nearby.ProvideDiagnosisKeysWorker
import ie.gov.tracing.storage.SharedPrefs
import java.io.File

@Keep
data class ServerFile(val id: Long, val path: String)

internal class DiagnosisKeyDownloader(private val context: Context) {
    fun download(): ListenableFuture<List<File>> {
        ProvideDiagnosisKeysWorker.nextSince = 0 // this will be greater than 0 on success

        var since = SharedPrefs.getLong("since", context)
        var fileLimit = SharedPrefs.getLong("fileLimit", context)

        Events.raiseEvent(Events.INFO, "download - get exports to process since: $since")

        // process:
        // 1. list batches from server from since index
        // 2. download the files to process
        // 3. increment sync to largest processed index
        // 4. return the list of files to pass to the submitter
        val data = Fetcher.fetch("/exposures/?since=$since&limit=$fileLimit", false, context)

        val files = mutableListOf<File>()
        if(data != null) {
            val serverFiles = Gson().fromJson(data, Array<ServerFile>::class.java)
            Events.raiseEvent(Events.INFO, "download - success, processing files: ${serverFiles.size}")

            serverFiles.forEach { serverFile ->
                try {
                    Events.raiseEvent(Events.INFO, "download - downloading file: ${serverFile.path}")
                    val file = Fetcher.downloadFile(serverFile.path, context)
                    if (file != null) {
                        files.add(file)
                        // update max since
                        since = since.coerceAtLeast(serverFile.id)
                    }
                } catch (ex: Exception) {
                    Events.raiseError("download - Error downloading file: ${serverFile.path}", ex)
                }
            }

            if (files.size > 0) {
                Events.raiseEvent(Events.INFO, "success downloading incrementing since to: $since")
                ProvideDiagnosisKeysWorker.nextSince = since
            }
        }

        return Futures.immediateFuture(files)
    }
}