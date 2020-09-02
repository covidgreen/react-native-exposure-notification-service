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
import kotlin.math.max
import kotlin.math.min

@Keep
data class ServerFile(val id: Long, val path: String)

internal class DiagnosisKeyDownloader(private val context: Context) {
    private fun processGoogleList(fileList: List<String>): Array<ServerFile> {
        var since = SharedPrefs.getLong("since", context)
        var fileLimit = SharedPrefs.getLong("fileLimit", context)
        val files = mutableListOf<ServerFile>()

        fileList.forEach { serverFile ->
            val f = File(serverFile)
            val name = f.nameWithoutExtension
            val nameParts = name.split("-")
            Events.raiseEvent(Events.INFO, "checking google file - ${serverFile} - ${name}, ${nameParts[0]}, ${nameParts[1]}, ${since}")
            if (nameParts[0].toLong() >= since) {
              val sf = ServerFile(nameParts[1].toLong(), serverFile)
              files.add(sf)
            }
        }
        if (files.size == 0) {
            return files.toTypedArray()
        }        
        if (since <= 0) {
            val startIndex = max(0, files.size - fileLimit.toInt())
            val endIndex = min(files.size, startIndex + fileLimit.toInt())
            return files.subList(startIndex, endIndex).toTypedArray()
        } else {
            val endIndex = min(files.size, fileLimit.toInt())
            return files.subList(0, endIndex).toTypedArray()
        }
    }

    fun download(): ListenableFuture<List<File>> {
        ProvideDiagnosisKeysWorker.nextSince = 0 // this will be greater than 0 on success

        var since = SharedPrefs.getLong("since", context)
        var fileLimit = SharedPrefs.getLong("fileLimit", context)
        val keyServerType = SharedPrefs.getString("keyServerType", context)

        Events.raiseEvent(Events.INFO, "download - get exports to process since: $since")

        // process:
        // 1. list batches from server from since index
        // 2. download the files to process
        // 3. increment sync to largest processed index
        // 4. return the list of files to pass to the submitter
        var url = "/exposures/?since=$since&limit=$fileLimit"
        if (keyServerType == "google") {
            url = "/v1/index.txt"
        } 
        val data = Fetcher.fetch(url, false, true, context)

        val files = mutableListOf<File>()
        if(data != null) {
            var serverFiles: Array<ServerFile>
            if (keyServerType == "google") {
                val fileList = data.split("\n")
                serverFiles = processGoogleList(fileList)
            } else {
                serverFiles = Gson().fromJson(data, Array<ServerFile>::class.java)
                Events.raiseEvent(Events.INFO, "download - success, processing files: ${serverFiles.size}")

            }
            serverFiles.forEach { serverFile ->
                try {
                    Events.raiseEvent(Events.INFO, "download - downloading file: ${serverFile}")
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