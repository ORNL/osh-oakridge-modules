package com.botts.impl.sensor.rapiscan;

import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.system.ISystemDriver;
import org.sensorhub.api.system.ISystemGroupDriver;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.sensor.SensorSystem;
import org.sensorhub.impl.sensor.SensorSystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coalesces and persists daily-file RPM setup parameter updates to {@code config.json}
 * asynchronously off the real-time TCP message reader thread.
 * <p>
 * When a single RPM emits {@code SG1..SG3} and {@code SN1..SN2} within tens of milliseconds,
 * or when up to 50+ lanes emit their daily setup blocks simultaneously at midnight, all
 * dirty configurations for a {@link ModuleRegistry} are coalesced into a single background
 * {@link ModuleRegistry#saveConfiguration(ModuleConfig...)} commit after a short debounce window.
 */
public final class RapiscanConfigSaver {

    private static final Logger logger = LoggerFactory.getLogger(RapiscanConfigSaver.class);
    public static final long DEFAULT_DEBOUNCE_MILLIS = 250L;

    private static volatile long debounceMillis = DEFAULT_DEBOUNCE_MILLIS;
    private static final ConcurrentHashMap<ModuleRegistry, RegistrySaveBatch> BATCHES = new ConcurrentHashMap<>();

    private static final class RegistrySaveBatch {
        final ModuleRegistry registry;
        final Set<ModuleConfig> dirtyConfigs = ConcurrentHashMap.newKeySet();
        final AtomicBoolean scheduled = new AtomicBoolean(false);
        final AtomicInteger commitCount = new AtomicInteger(0);

        RegistrySaveBatch(ModuleRegistry registry) {
            this.registry = registry;
        }
    }

    private RapiscanConfigSaver() {
    }

    public static void setDebounceMillis(long millis) {
        debounceMillis = Math.max(0L, millis);
    }

    public static long getDebounceMillis() {
        return debounceMillis;
    }

    public static int getCommitCount(ModuleRegistry registry) {
        if (registry == null) {
            return 0;
        }
        RegistrySaveBatch batch = BATCHES.get(registry);
        return batch != null ? batch.commitCount.get() : 0;
    }

    public static void resetCommitCount(ModuleRegistry registry) {
        if (registry == null) {
            return;
        }
        RegistrySaveBatch batch = BATCHES.get(registry);
        if (batch != null) {
            batch.commitCount.set(0);
        }
    }

    /**
     * Synchronizes the sensor's in-memory configuration with any parent {@link SensorSystem}
     * and schedules a coalesced background save on the {@link ModuleRegistry}.
     * Never blocks the caller thread on disk I/O.
     */
    public static void scheduleSave(RapiscanSensor sensor) {
        if (sensor == null) {
            return;
        }
        RapiscanConfig sensorConfig = sensor.getConfiguration();
        if (sensorConfig == null) {
            return;
        }
        if (sensorConfig.id == null) {
            sensorConfig.id = UUID.randomUUID().toString();
        }

        ModuleRegistry registry = resolveRegistry(sensor);
        ModuleConfig rootConfig = resolveAndSyncRootConfig(sensor, sensorConfig, registry);
        if (registry == null || rootConfig == null || registry.isShuttingDown()) {
            return;
        }

        RegistrySaveBatch batch = BATCHES.computeIfAbsent(registry, RegistrySaveBatch::new);
        batch.dirtyConfigs.add(rootConfig);
        if (batch.scheduled.compareAndSet(false, true)) {
            scheduleBatchFlush(batch);
        }
    }

    /**
     * Immediately flushes any pending configuration saves for the sensor's {@link ModuleRegistry}.
     */
    public static void flushNow(RapiscanSensor sensor) {
        if (sensor == null) {
            return;
        }
        flushNow(resolveRegistry(sensor));
    }

    /**
     * Immediately flushes any pending configuration saves for the given {@link ModuleRegistry}.
     */
    public static void flushNow(ModuleRegistry registry) {
        if (registry == null) {
            return;
        }
        RegistrySaveBatch batch = BATCHES.get(registry);
        if (batch != null) {
            flushBatch(batch);
        }
    }

    private static void scheduleBatchFlush(RegistrySaveBatch batch) {
        long delay = debounceMillis;
        try {
            if (delay <= 0L) {
                RapiscanThreadPoolManager.getInstance().submitProcessing(() -> flushBatch(batch));
            } else {
                RapiscanThreadPoolManager.getInstance().scheduleDelayedTask(
                        () -> flushBatch(batch), delay, TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException e) {
            // Fallback if executor was shut down in test environment
            flushBatch(batch);
        }
    }

    private static void flushBatch(RegistrySaveBatch batch) {
        synchronized (batch) {
            batch.scheduled.set(false);
            if (batch.registry.isShuttingDown()) {
                batch.dirtyConfigs.clear();
                return;
            }
            if (batch.dirtyConfigs.isEmpty()) {
                return;
            }
            List<ModuleConfig> configsToSave = new ArrayList<>(batch.dirtyConfigs.size());
            for (Iterator<ModuleConfig> it = batch.dirtyConfigs.iterator(); it.hasNext(); ) {
                ModuleConfig cfg = it.next();
                it.remove();
                if (cfg != null) {
                    if (cfg.id == null) {
                        cfg.id = UUID.randomUUID().toString();
                    }
                    configsToSave.add(cfg);
                }
            }
            if (!configsToSave.isEmpty()) {
                try {
                    batch.registry.saveConfiguration(configsToSave.toArray(new ModuleConfig[0]));
                    batch.commitCount.incrementAndGet();
                    logger.debug("Persisted {} updated RPM configuration(s) to repository", configsToSave.size());
                } catch (Exception e) {
                    logger.error("Failed to persist updated Rapiscan RPM configuration", e);
                }
            }
        }
        if (!batch.dirtyConfigs.isEmpty() && batch.scheduled.compareAndSet(false, true)) {
            scheduleBatchFlush(batch);
        }
    }

    private static ModuleRegistry resolveRegistry(RapiscanSensor sensor) {
        if (!sensor.hasParentHub()) {
            return null;
        }
        ISensorHub hub = sensor.getParentHub();
        return hub != null ? hub.getModuleRegistry() : null;
    }

    static ModuleConfig resolveAndSyncRootConfig(
            RapiscanSensor sensor, RapiscanConfig sensorConfig, ModuleRegistry registry) {
        ISystemGroupDriver<?> parentSystem = sensor.getParentSystem();
        if (parentSystem != null) {
            IModule<?> topModule = null;
            ISystemGroupDriver<?> current = parentSystem;
            ModuleConfig childConfig = sensorConfig;
            while (current != null) {
                if (current instanceof SensorSystem sensorSystem) {
                    syncMemberConfig(sensorSystem.getConfiguration(), childConfig);
                    topModule = sensorSystem;
                    childConfig = sensorSystem.getConfiguration();
                } else if (current instanceof IModule<?> mod) {
                    topModule = mod;
                    childConfig = mod.getConfiguration();
                }
                ISystemGroupDriver<?> next = current.getParentSystem();
                if (next == current) {
                    break;
                }
                current = next;
            }
            if (topModule != null && topModule.getConfiguration() != null) {
                return topModule.getConfiguration();
            }
        }

        if (registry != null) {
            if (sensorConfig.id != null && registry.isModuleLoaded(sensorConfig.id)) {
                return sensorConfig;
            }
            for (IModule<?> loaded : registry.getLoadedModules()) {
                if (loaded instanceof SensorSystem sensorSystem) {
                    SensorSystemConfig sysConfig = sensorSystem.getConfiguration();
                    if (syncMemberConfig(sysConfig, sensorConfig)) {
                        return sysConfig;
                    }
                }
            }
        }

        return sensorConfig;
    }

    private static boolean syncMemberConfig(SensorSystemConfig sysConfig, ModuleConfig childConfig) {
        if (sysConfig == null || sysConfig.subsystems == null || childConfig == null) {
            return false;
        }
        for (SensorSystemConfig.SystemMember member : sysConfig.subsystems) {
            if (member != null && member.config != null) {
                if (member.config == childConfig
                        || (childConfig.id != null && Objects.equals(member.config.id, childConfig.id))) {
                    member.config = childConfig;
                    return true;
                }
            }
        }
        return false;
    }
}
