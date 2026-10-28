/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mebot.nguli.com.feed

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Narrow regression adapted from AndroidX DataStore's DataStoreRaceTest
 * (commit 1ef22b89a34bd4027d7c50684101a62daf27b07b, Apache-2.0).
 * It deliberately opens a cold data collector while updateData is paused in writeTo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreColdCollectorRace098Test {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun newColdCollectorReceivesValueCommittedByConcurrentUpdate() = runTest {
        val serializer = GatedByteSerializer()
        val dataFile = temporaryFolder.newFile()
        val store = DataStoreFactory.create(
            serializer = serializer,
            scope = this,
            produceFile = { dataFile },
        )
        val expected = listOf(1.toByte(), 2.toByte())

        store.updateData { 1.toByte() }
        assertEquals(1.toByte(), store.data.first())

        serializer.pauseNextWrite()
        val writer = launch { store.updateData { 2.toByte() } }
        try {
            serializer.awaitWriteStarted()

            val observed = mutableListOf<Byte>()
            val reader: Job = launch { store.data.take(expected.size).toList(observed) }
            serializer.resumeWrite()
            writer.join()

            // On the affected DataStore version this times out because the committed second
            // value can be dropped when the cold reader's initial read observes a new version
            // paired with the old file contents.
            withTimeout(5_000) { reader.join() }
            assertEquals(expected, observed)
        } finally {
            // Never leave the test-scope DataStore writer blocked, including on expected failure.
            serializer.resumeWrite()
            writer.cancelAndJoin()
        }
    }

    private class GatedByteSerializer : Serializer<Byte> {
        override val defaultValue: Byte = 0
        @Volatile private var writeStarted: CompletableDeferred<Unit>? = null
        @Volatile private var writeGate: CompletableDeferred<Unit>? = null

        fun pauseNextWrite() {
            writeStarted = CompletableDeferred()
            writeGate = CompletableDeferred()
        }

        suspend fun awaitWriteStarted() {
            requireNotNull(writeStarted).await()
        }

        fun resumeWrite() {
            writeGate?.complete(Unit)
        }

        override suspend fun readFrom(input: InputStream): Byte {
            val value = input.read()
            return if (value == -1) defaultValue else value.toByte()
        }

        override suspend fun writeTo(t: Byte, output: OutputStream) {
            val started = writeStarted
            val gate = writeGate
            started?.complete(Unit)
            gate?.await()
            output.write(t.toInt())
            if (gate != null && gate.isCompleted) {
                writeStarted = null
                writeGate = null
            }
        }
    }
}
