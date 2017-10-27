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

package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.MappedBytes;
import net.openhft.chronicle.bytes.ref.BinaryLongReference;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.core.onoes.ExceptionHandler;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.core.values.LongArrayValues;
import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/*
 * Created by Peter Lawrey on 21/05/16.
 */
public class TimedStoreRecovery extends AbstractMarshallable implements StoreRecovery, Demarshallable {
    public static final StoreRecoveryFactory FACTORY = TimedStoreRecovery::new;
    private final LongValue timeStamp;

    @UsedViaReflection
    public TimedStoreRecovery(@NotNull WireIn in) {
        timeStamp = in.read("timeStamp").int64ForBinding(in.newLongReference());
    }

    public TimedStoreRecovery(@NotNull WireType wireType) {
        timeStamp = wireType.newLongReference().get();
    }

    @Override
    public long writeHeader(@NotNull Wire wire,
                            int length,
                            int safeLength,
                            long timeoutMS,
                            @Nullable final LongValue lastPosition) throws EOFException, UnrecoverableTimeoutException {
        try {
            return wire.writeHeader(length, safeLength, timeoutMS, TimeUnit.MILLISECONDS, lastPosition);
        } catch (TimeoutException e) {
            return recoverAndWriteHeader(wire, length, timeoutMS, lastPosition);
        }
    }

    @Override
    public void writeMarshallable(@NotNull WireOut out) {
        out.write("timeStamp").int64forBinding(0);
    }

    @NotNull
    private static ExceptionHandler warn() {
        // prevent a warning to be logged back to the same queue potentially corrupting it.
        // return Jvm.warn();
        return Slf4jExceptionHandler.WARN;
    }

    long acquireLock(long timeoutMS) {
        long start = System.currentTimeMillis();
        while (true) {
            long now = System.currentTimeMillis();
            long ts = timeStamp.getVolatileValue();
            final long tsEnd = now + timeoutMS / 2;
            if (ts < now && timeStamp.compareAndSwapValue(ts, tsEnd))
                return tsEnd;
            if (now >= start + timeoutMS) {
                warn().on(getClass(), "Unable to obtain the global lock in time, retrying");
                start = now;
            }
            Jvm.pause(1);
        }
    }

    void releaseLock(long tsEnd) {
        if (timeStamp.compareAndSwapValue(tsEnd, 0L))
            return;
        warn().on(getClass(), "Another thread obtained the lock ??");
    }

    @Override
    public long recoverIndex2Index(@NotNull LongValue index2Index, @NotNull Callable<Long> action, long timeoutMS) throws UnrecoverableTimeoutException {
        long tsEnd = acquireLock(timeoutMS);
        if (index2Index.getValue() == BinaryLongReference.LONG_NOT_COMPLETE) {
            warn().on(getClass(), "Rebuilding the index2index, resetting to 0");
            index2Index.setValue(0);
        } else {
            warn().on(getClass(), "The index2index value has changed, assuming it was recovered");
        }
        try {
            return action.call();
        } catch (Exception e) {
            throw Jvm.rethrow(e);
        } finally {
            releaseLock(tsEnd);
        }
    }

    @Override
    public long recoverSecondaryAddress(@NotNull LongArrayValues index2indexArr, int index2, @NotNull Callable<Long> action, long timeoutMS) throws UnrecoverableTimeoutException {
        long tsEnd = acquireLock(timeoutMS);
        if (index2indexArr.getValueAt(index2) == BinaryLongReference.LONG_NOT_COMPLETE) {
            warn().on(getClass(), "Rebuilding the index2index[" + index2 + "], resetting to 0");
            index2indexArr.setValueAt(index2, 0L);
        } else {
            warn().on(getClass(), "The index2index[" + index2 + "] value has changed, assuming it was recovered");
        }

        try {
            return action.call();
        } catch (Exception e) {
            throw Jvm.rethrow(e);
        } finally {
            releaseLock(tsEnd);
        }
    }

    @Override
    public long recoverAndWriteHeader(@NotNull Wire wire, int length, long timeoutMS, final LongValue lastPosition) throws UnrecoverableTimeoutException, EOFException {
        Bytes<?> bytes = wire.bytes();

        long offset = bytes.writePosition();
        int num = bytes.readVolatileInt(offset);

        String msgStart = "Unable to write a header at header number: " + Long.toHexString(wire.headerNumber()) + " position: " + offset;
        if (Wires.isNotComplete(num)) {
            // TODO Determine what the safe size should be.
            int sizeToSkip = 32 << 10;
            if (bytes instanceof MappedBytes) {
                MappedBytes mb = (MappedBytes) bytes;
                sizeToSkip = Maths.toUInt31(mb.mappedFile().overlapSize() / 2);
            }
            // pad to a 4 byte word.
            sizeToSkip = (sizeToSkip + 3) & ~3;
            sizeToSkip -= (int) (offset & 3);
            // clearing the start of the data so the meta data will look like 4 zero values with no event names.
            long pos = bytes.writePosition();
            try {
                bytes.writeSkip(4);
                wire.getValueOut().text("!! Skipped due to recovery of locked header !!");
                wire.addPadding(Math.toIntExact(sizeToSkip + (pos + 4) - bytes.writePosition()));
            } finally {
                bytes.writePosition(pos);
            }

            int emptyMetaData = Wires.META_DATA | sizeToSkip;
            if (bytes.compareAndSwapInt(offset, num, emptyMetaData)) {
                warn().on(getClass(), msgStart + " switching to a corrupt meta data message");
                bytes.writeSkip(sizeToSkip + 4);

            } else {
                int num2 = bytes.readVolatileInt(offset);
                warn().on(getClass(), msgStart + " already set to " + Integer.toHexString(num2));
            }

        } else {
            warn().on(getClass(), msgStart + " but message now exists.");
        }

        try {
            return wire.writeHeader(length, timeoutMS, TimeUnit.MILLISECONDS, lastPosition);

        } catch (TimeoutException e) {
            warn().on(getClass(), e);
            // Could happen if another thread recovers, writes 2 messages but the second one is corrupt.
            return recoverAndWriteHeader(wire, length, timeoutMS, lastPosition);

        } catch (EOFException e) {
            throw new AssertionError(e);
        }
    }
}
