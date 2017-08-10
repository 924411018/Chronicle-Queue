/*
 * Copyright 2016 higherfrequencytrading.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.openhft.chronicle.queue;

import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.ReadBytesMarshallable;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.core.time.SetTimeProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class RollingCycleTest {

    private final boolean lazyIndexing;

    public RollingCycleTest(boolean lazyIndexing) {
        this.lazyIndexing = lazyIndexing;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {false},
                {true}
        });
    }

    @Test
    public void testRollCycle() throws InterruptedException {
        SetTimeProvider stp = new SetTimeProvider();
        long start = System.currentTimeMillis() - 3 * 86_400_000;
        stp.currentTimeMillis(start);

        String basePath = OS.TARGET + "/testRollCycle" + System.nanoTime();
        try (final ChronicleQueue queue = ChronicleQueueBuilder.single(basePath)
                .testBlockSize()
                .timeoutMS(5)
                .rollCycle(RollCycles.TEST_DAILY)
                .timeProvider(stp)
                .build()) {

            final ExcerptAppender appender = queue.acquireAppender().lazyIndexing(lazyIndexing);
            int numWritten = 0;
            for (int h = 0; h < 3; h++) {
                stp.currentTimeMillis(start + TimeUnit.DAYS.toMillis(h));
                for (int i = 0; i < 3; i++) {
                    appender.writeBytes(new TestBytesMarshallable(i));
                    numWritten++;
                }
            }
            String expectedEager = "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY_LIGHT,\n" +
                    "  writePosition: 666,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: !int 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 377,\n" +
                    "    lastIndex: 3\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: -1,\n" +
                    "  recovery: !TimedStoreRecovery {\n" +
                    "    timeStamp: 0\n" +
                    "  },\n" +
                    "  deltaCheckpointInterval: 0\n" +
                    "}\n" +
                    "# position: 377, header: -1\n" +
                    "--- !!meta-data #binary\n" +
                    "index2index: [\n" +
                    "  # length: 8, used: 1\n" +
                    "  480,\n" +
                    "  0, 0, 0, 0, 0, 0, 0\n" +
                    "]\n" +
                    "# position: 480, header: -1\n" +
                    "--- !!meta-data #binary\n" +
                    "index: [\n" +
                    "  # length: 8, used: 3\n" +
                    "  576,\n" +
                    "  621,\n" +
                    "  666,\n" +
                    "  0, 0, 0, 0, 0\n" +
                    "]\n" +
                    "# position: 576, header: 0\n" +
                    "--- !!data #binary\n" +
                    "00000240             10 6E 61 6D  65 5F 2D 31 31 35 35 34     ·nam e_-11554\n" +
                    "00000250 38 34 35 37 36 7A CB 93  3D 38 51 D9 D4 F6 C9 2D 84576z·· =8Q····-\n" +
                    "00000260 A3 BD 70 39 9B B7 70 E9  8C 39 F0 1D 4F          ··p9··p· ·9··O   \n" +
                    "# position: 621, header: 1\n" +
                    "--- !!data #binary\n" +
                    "00000270    10 6E 61 6D 65 5F 2D  31 31 35 35 38 36 39 33  ·name_- 11558693\n" +
                    "00000280 32 35 6F 0E FB 68 D8 9C  B8 19 FC CC 2C 35 92 F9 25o··h·· ····,5··\n" +
                    "00000290 4D 68 E5 F1 2C 55 F0 B8  46 09                   Mh··,U·· F·      \n" +
                    "# position: 666, header: 2\n" +
                    "--- !!data #binary\n" +
                    "00000290                                            10 6E                ·n\n" +
                    "000002a0 61 6D 65 5F 2D 31 31 35  34 37 31 35 30 37 39 90 ame_-115 4715079·\n" +
                    "000002b0 45 C5 E6 F7 B9 1A 4B EA  C3 2F 7F 17 5F 10 01 5C E·····K· ·/··_··\\\n" +
                    "000002c0 6E 62 FC CC 5E CC DA                             nb··^··          \n" +
                    "# position: 711, header: 2 EOF\n" +
                    "--- !!not-ready-meta-data! #binary\n" +
                    "...\n" +
                    "# 130357 bytes remaining\n" +
                    "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY_LIGHT,\n" +
                    "  writePosition: 666,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: !int 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 377,\n" +
                    "    lastIndex: 3\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: -1,\n" +
                    "  recovery: !TimedStoreRecovery {\n" +
                    "    timeStamp: 0\n" +
                    "  },\n" +
                    "  deltaCheckpointInterval: 0\n" +
                    "}\n" +
                    "# position: 377, header: -1\n" +
                    "--- !!meta-data #binary\n" +
                    "index2index: [\n" +
                    "  # length: 8, used: 1\n" +
                    "  480,\n" +
                    "  0, 0, 0, 0, 0, 0, 0\n" +
                    "]\n" +
                    "# position: 480, header: -1\n" +
                    "--- !!meta-data #binary\n" +
                    "index: [\n" +
                    "  # length: 8, used: 3\n" +
                    "  576,\n" +
                    "  621,\n" +
                    "  666,\n" +
                    "  0, 0, 0, 0, 0\n" +
                    "]\n" +
                    "# position: 576, header: 0\n" +
                    "--- !!data #binary\n" +
                    "00000240             10 6E 61 6D  65 5F 2D 31 31 35 35 34     ·nam e_-11554\n" +
                    "00000250 38 34 35 37 36 7A CB 93  3D 38 51 D9 D4 F6 C9 2D 84576z·· =8Q····-\n" +
                    "00000260 A3 BD 70 39 9B B7 70 E9  8C 39 F0 1D 4F          ··p9··p· ·9··O   \n" +
                    "# position: 621, header: 1\n" +
                    "--- !!data #binary\n" +
                    "00000270    10 6E 61 6D 65 5F 2D  31 31 35 35 38 36 39 33  ·name_- 11558693\n" +
                    "00000280 32 35 6F 0E FB 68 D8 9C  B8 19 FC CC 2C 35 92 F9 25o··h·· ····,5··\n" +
                    "00000290 4D 68 E5 F1 2C 55 F0 B8  46 09                   Mh··,U·· F·      \n" +
                    "# position: 666, header: 2\n" +
                    "--- !!data #binary\n" +
                    "00000290                                            10 6E                ·n\n" +
                    "000002a0 61 6D 65 5F 2D 31 31 35  34 37 31 35 30 37 39 90 ame_-115 4715079·\n" +
                    "000002b0 45 C5 E6 F7 B9 1A 4B EA  C3 2F 7F 17 5F 10 01 5C E·····K· ·/··_··\\\n" +
                    "000002c0 6E 62 FC CC 5E CC DA                             nb··^··          \n" +
                    "# position: 711, header: 2 EOF\n" +
                    "--- !!not-ready-meta-data! #binary\n" +
                    "...\n" +
                    "# 130357 bytes remaining\n" +
                    "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY_LIGHT,\n" +
                    "  writePosition: 666,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: !int 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 377,\n" +
                    "    lastIndex: 3\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: -1,\n" +
                    "  recovery: !TimedStoreRecovery {\n" +
                    "    timeStamp: 0\n" +
                    "  },\n" +
                    "  deltaCheckpointInterval: 0\n" +
                    "}\n" +
                    "# position: 377, header: -1\n" +
                    "--- !!meta-data #binary\n" +
                    "index2index: [\n" +
                    "  # length: 8, used: 1\n" +
                    "  480,\n" +
                    "  0, 0, 0, 0, 0, 0, 0\n" +
                    "]\n" +
                    "# position: 480, header: -1\n" +
                    "--- !!meta-data #binary\n" +
                    "index: [\n" +
                    "  # length: 8, used: 3\n" +
                    "  576,\n" +
                    "  621,\n" +
                    "  666,\n" +
                    "  0, 0, 0, 0, 0\n" +
                    "]\n" +
                    "# position: 576, header: 0\n" +
                    "--- !!data #binary\n" +
                    "00000240             10 6E 61 6D  65 5F 2D 31 31 35 35 34     ·nam e_-11554\n" +
                    "00000250 38 34 35 37 36 7A CB 93  3D 38 51 D9 D4 F6 C9 2D 84576z·· =8Q····-\n" +
                    "00000260 A3 BD 70 39 9B B7 70 E9  8C 39 F0 1D 4F          ··p9··p· ·9··O   \n" +
                    "# position: 621, header: 1\n" +
                    "--- !!data #binary\n" +
                    "00000270    10 6E 61 6D 65 5F 2D  31 31 35 35 38 36 39 33  ·name_- 11558693\n" +
                    "00000280 32 35 6F 0E FB 68 D8 9C  B8 19 FC CC 2C 35 92 F9 25o··h·· ····,5··\n" +
                    "00000290 4D 68 E5 F1 2C 55 F0 B8  46 09                   Mh··,U·· F·      \n" +
                    "# position: 666, header: 2\n" +
                    "--- !!data #binary\n" +
                    "00000290                                            10 6E                ·n\n" +
                    "000002a0 61 6D 65 5F 2D 31 31 35  34 37 31 35 30 37 39 90 ame_-115 4715079·\n" +
                    "000002b0 45 C5 E6 F7 B9 1A 4B EA  C3 2F 7F 17 5F 10 01 5C E·····K· ·/··_··\\\n" +
                    "000002c0 6E 62 FC CC 5E CC DA                             nb··^··          \n" +
                    "...\n" +
                    "# 130357 bytes remaining\n";
            String expectedLazy = "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY_LIGHT,\n" +
                    "  writePosition: 467,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: !int 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 0,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: -1,\n" +
                    "  recovery: !TimedStoreRecovery {\n" +
                    "    timeStamp: 0\n" +
                    "  },\n" +
                    "  deltaCheckpointInterval: 0\n" +
                    "}\n" +
                    "# position: 377, header: 0\n" +
                    "--- !!data #binary\n" +
                    "00000170                                         10 6E 61               ·na\n" +
                    "00000180 6D 65 5F 2D 31 31 35 35  34 38 34 35 37 36 7A CB me_-1155 484576z·\n" +
                    "00000190 93 3D 38 51 D9 D4 F6 C9  2D A3 BD 70 39 9B B7 70 ·=8Q···· -··p9··p\n" +
                    "000001a0 E9 8C 39 F0 1D 4F                                ··9··O           \n" +
                    "# position: 422, header: 1\n" +
                    "--- !!data #binary\n" +
                    "000001a0                                10 6E 61 6D 65 5F            ·name_\n" +
                    "000001b0 2D 31 31 35 35 38 36 39  33 32 35 6F 0E FB 68 D8 -1155869 325o··h·\n" +
                    "000001c0 9C B8 19 FC CC 2C 35 92  F9 4D 68 E5 F1 2C 55 F0 ·····,5· ·Mh··,U·\n" +
                    "000001d0 B8 46 09                                         ·F·              \n" +
                    "# position: 467, header: 2\n" +
                    "--- !!data #binary\n" +
                    "000001d0                      10  6E 61 6D 65 5F 2D 31 31        · name_-11\n" +
                    "000001e0 35 34 37 31 35 30 37 39  90 45 C5 E6 F7 B9 1A 4B 54715079 ·E·····K\n" +
                    "000001f0 EA C3 2F 7F 17 5F 10 01  5C 6E 62 FC CC 5E CC DA ··/··_·· \\nb··^··\n" +
                    "# position: 512, header: 2 EOF\n" +
                    "--- !!not-ready-meta-data! #binary\n" +
                    "...\n" +
                    "# 130556 bytes remaining\n" +
                    "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY_LIGHT,\n" +
                    "  writePosition: 467,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: !int 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 0,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: -1,\n" +
                    "  recovery: !TimedStoreRecovery {\n" +
                    "    timeStamp: 0\n" +
                    "  },\n" +
                    "  deltaCheckpointInterval: 0\n" +
                    "}\n" +
                    "# position: 377, header: 0\n" +
                    "--- !!data #binary\n" +
                    "00000170                                         10 6E 61               ·na\n" +
                    "00000180 6D 65 5F 2D 31 31 35 35  34 38 34 35 37 36 7A CB me_-1155 484576z·\n" +
                    "00000190 93 3D 38 51 D9 D4 F6 C9  2D A3 BD 70 39 9B B7 70 ·=8Q···· -··p9··p\n" +
                    "000001a0 E9 8C 39 F0 1D 4F                                ··9··O           \n" +
                    "# position: 422, header: 1\n" +
                    "--- !!data #binary\n" +
                    "000001a0                                10 6E 61 6D 65 5F            ·name_\n" +
                    "000001b0 2D 31 31 35 35 38 36 39  33 32 35 6F 0E FB 68 D8 -1155869 325o··h·\n" +
                    "000001c0 9C B8 19 FC CC 2C 35 92  F9 4D 68 E5 F1 2C 55 F0 ·····,5· ·Mh··,U·\n" +
                    "000001d0 B8 46 09                                         ·F·              \n" +
                    "# position: 467, header: 2\n" +
                    "--- !!data #binary\n" +
                    "000001d0                      10  6E 61 6D 65 5F 2D 31 31        · name_-11\n" +
                    "000001e0 35 34 37 31 35 30 37 39  90 45 C5 E6 F7 B9 1A 4B 54715079 ·E·····K\n" +
                    "000001f0 EA C3 2F 7F 17 5F 10 01  5C 6E 62 FC CC 5E CC DA ··/··_·· \\nb··^··\n" +
                    "# position: 512, header: 2 EOF\n" +
                    "--- !!not-ready-meta-data! #binary\n" +
                    "...\n" +
                    "# 130556 bytes remaining\n" +
                    "--- !!meta-data #binary\n" +
                    "header: !SCQStore {\n" +
                    "  wireType: !WireType BINARY_LIGHT,\n" +
                    "  writePosition: 467,\n" +
                    "  roll: !SCQSRoll {\n" +
                    "    length: !int 86400000,\n" +
                    "    format: yyyyMMdd,\n" +
                    "    epoch: 0\n" +
                    "  },\n" +
                    "  indexing: !SCQSIndexing {\n" +
                    "    indexCount: 8,\n" +
                    "    indexSpacing: 1,\n" +
                    "    index2Index: 0,\n" +
                    "    lastIndex: 0\n" +
                    "  },\n" +
                    "  lastAcknowledgedIndexReplicated: -1,\n" +
                    "  recovery: !TimedStoreRecovery {\n" +
                    "    timeStamp: 0\n" +
                    "  },\n" +
                    "  deltaCheckpointInterval: 0\n" +
                    "}\n" +
                    "# position: 377, header: 0\n" +
                    "--- !!data #binary\n" +
                    "00000170                                         10 6E 61               ·na\n" +
                    "00000180 6D 65 5F 2D 31 31 35 35  34 38 34 35 37 36 7A CB me_-1155 484576z·\n" +
                    "00000190 93 3D 38 51 D9 D4 F6 C9  2D A3 BD 70 39 9B B7 70 ·=8Q···· -··p9··p\n" +
                    "000001a0 E9 8C 39 F0 1D 4F                                ··9··O           \n" +
                    "# position: 422, header: 1\n" +
                    "--- !!data #binary\n" +
                    "000001a0                                10 6E 61 6D 65 5F            ·name_\n" +
                    "000001b0 2D 31 31 35 35 38 36 39  33 32 35 6F 0E FB 68 D8 -1155869 325o··h·\n" +
                    "000001c0 9C B8 19 FC CC 2C 35 92  F9 4D 68 E5 F1 2C 55 F0 ·····,5· ·Mh··,U·\n" +
                    "000001d0 B8 46 09                                         ·F·              \n" +
                    "# position: 467, header: 2\n" +
                    "--- !!data #binary\n" +
                    "000001d0                      10  6E 61 6D 65 5F 2D 31 31        · name_-11\n" +
                    "000001e0 35 34 37 31 35 30 37 39  90 45 C5 E6 F7 B9 1A 4B 54715079 ·E·····K\n" +
                    "000001f0 EA C3 2F 7F 17 5F 10 01  5C 6E 62 FC CC 5E CC DA ··/··_·· \\nb··^··\n" +
                    "...\n" +
                    "# 130556 bytes remaining\n";
            assertEquals(lazyIndexing ? expectedLazy : expectedEager, queue.dump());

            System.out.println("Wrote: " + numWritten + " messages");

            long numRead = 0;
            final TestBytesMarshallable reusableData = new TestBytesMarshallable(0);
            final ExcerptTailer currentPosTailer = queue.createTailer()
                    .toStart();
            final ExcerptTailer endPosTailer = queue.createTailer().toEnd();
            while (currentPosTailer.index() < endPosTailer.index()) {
                try {
                    assertTrue(currentPosTailer.readBytes(reusableData));
                } catch (AssertionError e) {
                    System.err.println("Could not read data at index: " +
                            numRead + " " +
                            Long.toHexString(currentPosTailer.cycle()) + " " +
                            Long.toHexString(currentPosTailer.index()) + " " +
                            e.getMessage() + " " +
                            e);
                    throw e;
                }
                numRead++;
            }
            assertFalse(currentPosTailer.readBytes(reusableData));

            System.out.println("Wrote " + numWritten + " Read " + numRead);
            try {
                IOTools.deleteDirWithFiles(basePath, 2);
            } catch (IORuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    private static class TestBytesMarshallable implements WriteBytesMarshallable, ReadBytesMarshallable {
        @Nullable
        String _name;
        long _value1;
        long _value2;
        long _value3;

        public TestBytesMarshallable(int i) {
            final Random rand = new Random(i);
            _name = "name_" + rand.nextInt();
            _value1 = rand.nextLong();
            _value2 = rand.nextLong();
            _value3 = rand.nextLong();
        }

        @Override
        public void writeMarshallable(@NotNull BytesOut bytes) {
            bytes.writeUtf8(_name);
            bytes.writeLong(_value1);
            bytes.writeLong(_value2);
            bytes.writeLong(_value3);
        }

        @Override
        public void readMarshallable(@NotNull BytesIn bytes) throws IORuntimeException {
            _name = bytes.readUtf8();
            _value1 = bytes.readLong();
            _value2 = bytes.readLong();
            _value3 = bytes.readLong();
        }
    }
}