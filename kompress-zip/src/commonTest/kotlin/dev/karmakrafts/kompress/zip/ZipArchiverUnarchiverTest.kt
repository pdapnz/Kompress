/*
 * Copyright 2026 Karma Krafts
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.karmakrafts.kompress.zip

import dev.karmakrafts.kompress.AbstractCompressor
import dev.karmakrafts.kompress.ExperimentalCompressionApi
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCompressionApi::class)
class ZipArchiverUnarchiverTest {
    /** Passthrough STORED "compressor" — a real consumer (e.g. avoiding a broken Deflater) needs one; ZIP itself has no built-in one. */
    private class NoOpCompressor : AbstractCompressor() {
        private var isFinishing = false
        private var isFinished = false

        override val needsInput: Boolean get() = remaining <= 0 && !isFinishing
        override val finished: Boolean get() = isFinished

        override fun compress(output: ByteArray, offset: Int, size: Int, flush: Boolean): Int {
            if (remaining > 0) {
                val count = min(remaining, size)
                input.copyInto(output, offset, inputOffset, inputOffset + count)
                inputOffset += count
                remaining -= count
                bytesWritten += count
                return count
            }
            if (isFinishing) {
                isFinishing = false
                isFinished = true
            }
            return 0
        }

        override fun setInput(data: ByteArray, offset: Int, size: Int) {
            super.setInput(data, offset, size)
            bytesRead += size
        }

        override fun finish() {
            isFinishing = true
        }

        override fun reset() {
            input = ByteArray(0)
            inputOffset = 0
            inputSize = 0
            remaining = 0
            bytesRead = 0
            bytesWritten = 0
            isFinishing = false
            isFinished = false
        }

        override fun close() = Unit
    }

    private companion object {
        fun readEntryBytes(source: Source, fetchMore: () -> Boolean): ByteArray {
            val buffer = Buffer()
            var read = source.readAtMostTo(buffer, 64)
            while (read != -1L || fetchMore()) {
                read = source.readAtMostTo(buffer, 64)
            }
            return buffer.readByteArray()
        }
    }

    @Test
    fun `archive and unarchive text entry preserves metadata and content`() {
        val payload = "HELLO, WORLD!".encodeToByteArray()
        val expectedEntry = ZipEntry(
            modificationTime = Instant.fromEpochSeconds(1_704_067_242),
            name = "test.txt",
            gpbf = ZipGPBF(omitChecksumAndSizes = true, languageEncoding = false)
        )
        val archiveBuffer = Buffer()

        archiveBuffer.zip().use { archiver ->
            archiver.appendEntry(expectedEntry) { sink ->
                sink.write(payload)
                false
            }
        }

        val entries = ArrayList<ZipEntry>()
        val contents = ArrayList<ByteArray>()
        archiveBuffer.unzip().use { unarchiver ->
            unarchiver.forEachEntry { entry, source, fetchMore ->
                entries += entry
                contents += readEntryBytes(source, fetchMore)
            }
        }

        val actualEntry = assertNotNull(entries.singleOrNull())
        assertEquals(expectedEntry.name, actualEntry.name)
        assertEquals(expectedEntry.compressionMethod, actualEntry.compressionMethod)
        assertEquals(expectedEntry.gpbf, actualEntry.gpbf)
        assertContentEquals(payload, contents.single())
    }

    @Test
    fun `archive and unarchive multiple text entries preserves order and data`() {
        val inputBuffer1 = Buffer()
        inputBuffer1.writeString("HELLO, WORLD!")
        val inputBuffer2 = Buffer()
        inputBuffer2.writeString("The fox goes yap!")
        val archiveBuffer = Buffer()

        archiveBuffer.zip().use { archiver ->
            archiver.appendEntry("test1.txt", source = inputBuffer1)
            archiver.appendEntry("test2.txt", source = inputBuffer2)
        }

        val names = ArrayList<String>()
        val contents = ArrayList<String>()
        archiveBuffer.unzip().use { unarchiver ->
            unarchiver.forEachEntry { entry, source, fetchMore ->
                names += entry.name
                contents += readEntryBytes(source, fetchMore).decodeToString()
            }
        }

        assertEquals(listOf("test1.txt", "test2.txt"), names)
        assertEquals(listOf("HELLO, WORLD!", "The fox goes yap!"), contents)
    }

    @Test
    fun `archive and unarchive supports utf-8 names when language encoding is enabled`() {
        val payload = "UTF-8 test".encodeToByteArray()
        val archiveBuffer = Buffer()
        val utf8Name = "täst-lâtìn1-ÿ-☃.txt"
        val entry = ZipEntry(
            modificationTime = Instant.fromEpochSeconds(1_704_067_242),
            name = utf8Name,
            gpbf = ZipGPBF(omitChecksumAndSizes = false, languageEncoding = true)
        )

        archiveBuffer.zip().use { archiver ->
            archiver.appendEntry(entry) { sink ->
                sink.write(payload)
                false
            }
        }

        archiveBuffer.unzip().use { unarchiver ->
            var entryFound = false
            unarchiver.forEachEntry { entry, source, fetchMore ->
                if (entry.name == utf8Name) {
                    entryFound = true
                }
                readEntryBytes(source, fetchMore)
            }
            assertTrue(entryFound, "Entry with name $utf8Name should be present")
        }
    }

    @Test
    fun `archive and unarchive empty file keeps zero-length payload`() {
        val inputBuffer = Buffer()
        val archiveBuffer = Buffer()

        archiveBuffer.zip().use { archiver ->
            archiver.appendEntry("empty.txt", source = inputBuffer)
        }

        archiveBuffer.unzip().use { unarchiver ->
            var entryFound = false
            unarchiver.forEachEntry { entry, source, fetchMore ->
                if (entry.name == "empty.txt") {
                    entryFound = true
                    assertEquals(0, readEntryBytes(source, fetchMore).size)
                }
            }
            assertTrue(entryFound, "Entry 'empty.txt' should be present")
        }
    }

    @Test
    fun `archive and unarchive STORED entry with default GPBF does not require a data descriptor`() {
        // Регрессия: appendEntry всегда форсировал OMIT_CHECKSUM_AND_SIZES (дата-дескриптор), а
        // ZipUnarchiver.extractStoredData явно отказывался читать STORED-записи с дескриптором —
        // архив, написанный этой же библиотекой со STORED-компрессией, не читался ею же обратно.
        val payload = ByteArray(5000) { (it % 256).toByte() }
        val entry = ZipEntry(
            modificationTime = Instant.fromEpochSeconds(1_704_067_242),
            name = "photo.jpg",
            compressionMethod = ZipCompressionMethod.NONE,
            gpbf = ZipGPBF(), // дефолты (omitChecksumAndSizes = true) — то, что реально используют потребители
        )
        val archiveBuffer = Buffer()

        archiveBuffer.zip(compressors = mapOf(ZipCompressionMethod.NONE to NoOpCompressor())).use { archiver ->
            archiver.appendEntry(entry) { sink ->
                sink.write(payload)
                false
            }
        }

        val entries = ArrayList<ZipEntry>()
        val contents = ArrayList<ByteArray>()
        archiveBuffer.unzip().use { unarchiver ->
            unarchiver.forEachEntry { entry, source, fetchMore ->
                entries += entry
                contents += readEntryBytes(source, fetchMore)
            }
        }

        val actualEntry = assertNotNull(entries.singleOrNull())
        assertEquals(entry.name, actualEntry.name)
        assertContentEquals(payload, contents.single())
    }
}
