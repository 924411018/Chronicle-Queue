package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.queue.impl.TableStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.function.ToIntFunction;

final class TableDirectoryListing implements DirectoryListing {
    private static final Logger LOGGER = LoggerFactory.getLogger(TableDirectoryListing.class);
    private static final String HIGHEST_CREATED_CYCLE = "listing.highestCycle";
    private static final String LOWEST_CREATED_CYCLE = "listing.lowestCycle";
    // visible for testing
    static final String LOCK = "listing.exclusiveLock";
    private static final String MOD_COUNT = "listing.modCount";
    private static final int UNSET_MAX_CYCLE = Integer.MIN_VALUE;
    private static final int UNSET_MIN_CYCLE = Integer.MAX_VALUE;
    private final TableStore tableStore;
    private final Path queuePath;
    private final ToIntFunction<File> fileToCycleFunction;
    private volatile LongValue maxCycleValue;
    private volatile LongValue minCycleValue;
    private volatile LongValue lock;
    private volatile LongValue modCount;
    private final boolean readOnly;

    TableDirectoryListing(
            final TableStore tableStore, final Path queuePath,
            final ToIntFunction<File> fileToCycleFunction,
            final boolean readOnly) {
        this.tableStore = tableStore;
        this.queuePath = queuePath;
        this.fileToCycleFunction = fileToCycleFunction;
        this.readOnly = readOnly;
    }

    @Override
    public void init() {
        tableStore.doWithExclusiveLock(ts -> {
            maxCycleValue = ts.acquireValueFor(HIGHEST_CREATED_CYCLE);
            minCycleValue = ts.acquireValueFor(LOWEST_CREATED_CYCLE);
            minCycleValue.compareAndSwapValue(Long.MIN_VALUE, UNSET_MIN_CYCLE);
            lock = ts.acquireValueFor(LOCK);
            modCount = ts.acquireValueFor(MOD_COUNT);
            if (lock.getVolatileValue() == Long.MIN_VALUE) {
                lock.compareAndSwapValue(Long.MIN_VALUE, 0);
            }
            if (modCount.getVolatileValue() == Long.MIN_VALUE) {
                modCount.compareAndSwapValue(Long.MIN_VALUE, 0);
            }
            return this;
        });
    }

    @Override
    public void refresh() {
        if (readOnly) {
            return;
        }
        refreshIndex();
    }

    @Override
    public void onFileCreated(final File file, final int cycle) {
        if (readOnly) {
            LOGGER.warn("DirectoryListing is read-only, not updating listing");
            return;
        }
        modCount.addAtomicValue(1);
        if (cycle > getMaxCreatedCycle()) {
            maxCycleValue.setMaxValue(cycle);
        }
        if (cycle < getMinCycleValue()) {
            minCycleValue.setMinValue(cycle);
        }
    }

    @Override
    public int getMaxCreatedCycle() {
        return getMaxCycleValue();
    }

    @Override
    public int getMinCreatedCycle() {
        return getMinCycleValue();
    }

    @Override
    public long modCount() {
        return modCount.getVolatileValue();
    }

    @Override
    public String toString() {
        return tableStore.dump();
    }

    private int getMaxCycleValue() {
        return (int) maxCycleValue.getVolatileValue();
    }

    private int getMinCycleValue() {
        return (int) minCycleValue.getVolatileValue();
    }

    private void refreshIndex() {
        final File[] queueFiles = queuePath.toFile().
                listFiles((d, f) -> f.endsWith(SingleChronicleQueue.SUFFIX));
        int min = UNSET_MIN_CYCLE;
        int max = UNSET_MAX_CYCLE;
        if (queueFiles != null) {
            for (File queueFile : queueFiles) {
                min = Math.min(fileToCycleFunction.applyAsInt(queueFile), min);
                max = Math.max(fileToCycleFunction.applyAsInt(queueFile), max);
            }
            maxCycleValue.setOrderedValue(max);
            minCycleValue.setOrderedValue(min);
        }
    }

    void close() {
        tableStore.close();
    }
}