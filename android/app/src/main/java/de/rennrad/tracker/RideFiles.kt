package de.rennrad.tracker

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

internal object RideFiles {
    private var active: File? = null
    private var nextSequence = 0
    private var lastTime = 0L

    internal data class Upload(val folder: File, val body: JSONObject, val through: Long, val final: Boolean)

    private fun root(context: Context) = File(context.filesDir, "rides")
    private fun metadata(folder: File) = AtomicFile(File(folder, "state.json"))
    private fun log(folder: File) = File(folder, "points.ndjson")

    @Synchronized
    fun start(context: Context) {
        check(active == null)
        val started = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val folder = File(root(context), "$started-$id")
        check(folder.mkdirs())
        save(folder, JSONObject().put("id", id).put("started", started).put("sent", 0L))
        nextSequence = 0
        lastTime = started
        active = folder
    }

    @Synchronized
    fun point(point: JSONObject) {
        val folder = active ?: return
        val time = maxOf(lastTime, point.getLong("time"))
        point.put("seq", nextSequence).put("time", time)
        RandomAccessFile(log(folder), "rw").use { file ->
            val offset = file.length()
            file.seek(offset)
            try {
                file.write((point.toString() + "\n").toByteArray(Charsets.UTF_8))
                file.fd.sync()
            } catch (error: Exception) {
                file.setLength(offset)
                throw error
            }
        }
        lastTime = time
        nextSequence++
    }

    @Synchronized
    fun finish() {
        val folder = active ?: return
        active = null
        save(folder, read(folder).put("ended", maxOf(System.currentTimeMillis(), lastTime)))
    }

    @Synchronized
    fun hasPending(context: Context): Boolean = folders(context).isNotEmpty()

    @Synchronized
    fun nextUpload(context: Context): Upload? {
        for (folder in folders(context)) {
            val state = read(folder)
            val file = log(folder)
            val points = JSONArray()
            var through = state.getLong("sent")
            var endOfFile = true
            if (file.exists()) RandomAccessFile(file, "r").use { input ->
                input.seek(through)
                while (points.length() < 100) {
                    val line = input.readLine() ?: break
                    val after = input.filePointer
                    input.seek(after - 1)
                    if (input.read() != '\n'.code) break
                    points.put(JSONObject(line))
                    through = after
                }
                endOfFile = points.length() < 100 || through == input.length()
            }

            // After a process death the last durable sample bounds the interrupted ride.
            val final = folder != active && endOfFile
            if (points.length() == 0 && state.has("announced") && !final) continue
            val started = state.getLong("started")
            val body = JSONObject().put("started", started).put("points", points)
            if (final) {
                val last = points.optJSONObject(points.length() - 1)?.getLong("time")
                    ?: state.optLong("lastSentTime", started)
                body.put("ended", state.optLong("ended", last))
            }
            return Upload(folder, body, through, final)
        }
        return null
    }

    @Synchronized
    fun confirm(upload: Upload) {
        val state = read(upload.folder).put("sent", upload.through).put("announced", true)
        val points = upload.body.getJSONArray("points")
        points.optJSONObject(points.length() - 1)?.let { state.put("lastSentTime", it.getLong("time")) }
        if (upload.final) state.put("uploaded", true)
        save(upload.folder, state)
        if (upload.final) upload.folder.deleteRecursively()
    }

    private fun folders(context: Context): List<File> = root(context).listFiles().orEmpty()
        .filter { it.isDirectory && (File(it, "state.json").isFile || File(it, "state.json.bak").isFile) && !read(it).optBoolean("uploaded") }
        .sortedWith(compareBy<File> { it != active }.thenBy { it.name })

    private fun read(folder: File): JSONObject = JSONObject(String(metadata(folder).readFully(), Charsets.UTF_8))

    private fun save(folder: File, state: JSONObject) {
        val file = metadata(folder)
        val output = file.startWrite()
        try {
            output.write(state.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}
