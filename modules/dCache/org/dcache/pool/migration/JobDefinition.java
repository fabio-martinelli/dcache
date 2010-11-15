package org.dcache.pool.migration;

import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import org.dcache.pool.repository.CacheEntry;

/**
 * Immutable record class holding the parameters that define a job.
 */
public class JobDefinition
{
    public final List<CacheEntryFilter> filters;
    public final CacheEntryMode sourceMode;
    public final CacheEntryMode targetMode;
    public final PoolSelectionStrategy selectionStrategy;
    public final Comparator<CacheEntry> comparator;
    public final RefreshablePoolList poolList;
    public final long refreshPeriod;
    public final boolean isPermanent;
    public final boolean isEager;
    public final boolean mustMovePins;
    public final boolean computeChecksumOnUpdate;

    public JobDefinition(List<CacheEntryFilter> filters,
                         CacheEntryMode sourceMode,
                         CacheEntryMode targetMode,
                         PoolSelectionStrategy selectionStrategy,
                         Comparator<CacheEntry> comparator,
                         RefreshablePoolList poolList,
                         long refreshPeriod,
                         boolean isPermanent,
                         boolean isEager,
                         boolean mustMovePins,
                         boolean computeChecksumOnUpdate)
    {
        this.filters = Collections.unmodifiableList(filters);
        this.sourceMode = sourceMode;
        this.targetMode = targetMode;
        this.selectionStrategy = selectionStrategy;
        this.comparator = comparator;
        this.poolList = poolList;
        this.refreshPeriod = refreshPeriod;
        this.isPermanent = isPermanent;
        this.isEager = isEager;
        this.mustMovePins = mustMovePins;
        this.computeChecksumOnUpdate = computeChecksumOnUpdate;
    }
}