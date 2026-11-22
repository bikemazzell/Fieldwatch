package app.fieldwatch.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

internal fun writeAtomic(file: File, write: (OutputStream) -> Unit) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    try {
        FileOutputStream(tmp).use { raw ->
            val out = BufferedOutputStream(raw)
            write(out)
            out.flush()
            raw.fd.sync()
        }
    } catch (err: Throwable) {
        tmp.delete()
        throw err
    }
    if (!tmp.renameTo(file)) {
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }
}
