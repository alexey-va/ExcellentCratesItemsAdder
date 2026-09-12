package ru.ruscrafting.ecia.runtime

import ru.arc.persistence.DurableRecordJournal
import java.nio.file.Path
import java.util.function.Consumer
import java.util.function.Function

/** Java-friendly adapter that keeps record storage on arc-core's durable owner. */
object EciaDurableJournalFactory {
    @JvmStatic
    fun <T : Any> create(
        root: Path,
        relativeDirectory: Path,
        maxRecordBytes: Long,
        encode: Function<T, ByteArray>,
        decode: Function<ByteArray, T>,
        validate: Consumer<T>?,
    ): DurableRecordJournal<T> = DurableRecordJournal(
        root,
        relativeDirectory,
        maxRecordBytes,
        { value -> encode.apply(value) },
        { bytes -> decode.apply(bytes) },
        { value -> validate?.accept(value) },
    )
}
