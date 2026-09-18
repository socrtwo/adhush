package io.adhush.android

import android.content.Context
import io.adhush.core.FileClockStore
import io.adhush.core.FileFingerprintStore
import io.adhush.core.FileJingleStore
import io.adhush.core.FileScriptStore
import io.adhush.core.Jingle
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The phone's memory as one file (ADR 0018): the remembered breaks, the
 * scripts and the break clock, zipped for sharing, and merged back on another
 * phone without doubling what it already knows. Numbers and words only; no
 * audio is ever in it.
 */
object Memory {
    const val ZIP = "adhush-memory.zip"
    private const val ADS = "ads.tsv"
    private val FILES = listOf(ADS, AdHushService.SCRIPTS_FILE, AdHushService.CLOCK_FILE, AdHushService.JINGLES_FILE)

    fun export(context: Context): File {
        val dir = File(context.filesDir, "share"); dir.mkdirs()
        val out = File(dir, ZIP)
        ZipOutputStream(FileOutputStream(out).buffered()).use { z ->
            for (name in FILES) {
                val f = File(context.filesDir, name)
                if (f.isFile) { z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
            z.putNextEntry(ZipEntry("meta.txt"))
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
            z.write("adhush memory\nversion $version\nfiles ${FILES.joinToString(" ")}\n".toByteArray())
            z.closeEntry()
        }
        return out
    }

    /** Merge a memory file into this phone's. Call with the service stopped: it reads the files only at start. */
    fun import(context: Context, input: InputStream): String {
        val tmp = File(context.cacheDir, "import"); tmp.deleteRecursively(); tmp.mkdirs()
        ZipInputStream(input.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val name = File(e.name).name
                if (name in FILES) File(tmp, name).outputStream().use { z.copyTo(it) }
                e = z.nextEntry
            }
        }
        var ads = 0; var scripts = 0; var clock = false; var jingles = 0
        File(tmp, ADS).takeIf { it.isFile }?.let { f ->
            val theirs = FileFingerprintStore(f)
            val mine = FileFingerprintStore(File(context.filesDir, ADS))
            val known = HashSet(mine.ads().map { mine.audioBlocks(it.adId) })
            for (a in theirs.ads()) {
                val blocks = theirs.audioBlocks(a.adId)
                if (blocks.isEmpty() || !known.add(blocks)) continue
                mine.addAd(a.durationS, blocks, System.currentTimeMillis() / 1000.0, a.kind); ads++
            }
        }
        File(tmp, AdHushService.SCRIPTS_FILE).takeIf { it.isFile }?.let { f ->
            val theirs = FileScriptStore(f)
            val mine = FileScriptStore(File(context.filesDir, AdHushService.SCRIPTS_FILE))
            val known = HashSet(mine.all().map { it.words })
            for (s in theirs.all()) if (known.add(s.words)) { mine.add(s.words, s.durationS); scripts++ }
        }
        File(tmp, AdHushService.CLOCK_FILE).takeIf { it.isFile }?.let { f ->
            val theirs = FileClockStore(f).load()
            if (theirs != null) {
                val store = FileClockStore(File(context.filesDir, AdHushService.CLOCK_FILE))
                val mine = store.load() ?: io.adhush.core.ClockCounts()
                store.save(io.adhush.core.ClockCounts(
                    IntArray(60) { mine.breaks[it] + theirs.breaks[it] },
                    IntArray(60) { mine.seen[it] + theirs.seen[it] },
                    IntArray(mine.lengths.size) { mine.lengths[it] + theirs.lengths[it] },
                ))
                clock = true
            }
        }
        File(tmp, AdHushService.JINGLES_FILE).takeIf { it.isFile }?.let { f ->
            val theirs = FileJingleStore(f).load()
            val store = FileJingleStore(File(context.filesDir, AdHushService.JINGLES_FILE))
            val mine = ArrayList(store.load())
            val known = HashSet(mine.map { it.blocks })
            var nextId = (mine.maxOfOrNull { it.id } ?: 0) + 1
            for (j in theirs) if (known.add(j.blocks)) { mine.add(Jingle(nextId++, j.kind, j.blocks, j.hits, j.falseHits, j.createdTs, HashSet(j.hours))); jingles++ }
            if (jingles > 0) store.save(mine)
        }
        tmp.deleteRecursively()
        return "imported $ads new breaks, $scripts new scripts and $jingles jingles" + (if (clock) ", and merged the break clock" else "")
    }
}
