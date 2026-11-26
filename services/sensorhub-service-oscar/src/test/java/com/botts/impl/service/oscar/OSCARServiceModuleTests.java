package com.botts.impl.service.oscar;

import com.botts.api.service.bucket.IBucketService;
import com.botts.impl.service.bucket.BucketService;
import com.botts.impl.service.bucket.BucketServiceConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.service.HttpServer;
import org.sensorhub.impl.service.HttpServerConfig;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OSCARServiceModuleTests {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    public static class SlowStartingBucketService extends BucketService {
        static volatile long artificialStartDelayMs = 220L;

        @Override
        protected void doStart() throws SensorHubException {
            try {
                Thread.sleep(artificialStartDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SensorHubException("Interrupted slow bucket startup", e);
            }
            super.doStart();
        }
    }

    public static class FailingBucketService extends BucketService {
        @Override
        public void doInit() throws SensorHubException {
            throw new SensorHubException("Simulated cold-disk mount failure");
        }
    }

    @Test
    public void readinessIsWithdrawnBeforeBaseShutdown() throws SensorHubException {
        List<String> lifecycle = new ArrayList<>();
        OSCARServiceModule module = new OSCARServiceModule() {
            @Override void stopApplicationReadiness() { lifecycle.add("readiness"); }
            @Override void stopBaseModule() { lifecycle.add("base"); }
        };

        module.doStop();

        assertEquals(List.of("readiness", "base"), lifecycle);
    }

    @Test
    public void everyWorkerIsSignalledBeforeTheFirstAwait() throws SensorHubException {
        List<String> lifecycle = new ArrayList<>();
        OSCARServiceModule module = new OSCARServiceModule() {
            @Override void stopBaseModule() { lifecycle.add("base"); }
            @Override void requestStatisticsOutputStop() { lifecycle.add("request-statistics"); }
            @Override void requestDatabasePurgerStop() { lifecycle.add("request-purger"); }
            @Override void requestVideoRetentionStop() { lifecycle.add("request-video"); }
            @Override void requestStoragePressureRetentionStop() { lifecycle.add("request-pressure"); }

            @Override
            void awaitStatisticsOutputStop(long deadline) {
                assertEquals(List.of("base", "request-statistics", "request-purger",
                        "request-video", "request-pressure"), lifecycle);
                lifecycle.add("await-statistics");
            }

            @Override void awaitDatabasePurgerStop(long deadline) { lifecycle.add("await-purger"); }
            @Override void awaitVideoRetentionStop(long deadline) { lifecycle.add("await-video"); }
            @Override void awaitStoragePressureRetentionStop(long deadline) { lifecycle.add("await-pressure"); }
            @Override void unregisterWebIdHandler() { lifecycle.add("web-id"); }
            @Override void unregisterMediaHandler() { lifecycle.add("media"); }
        };

        module.doStop();

        assertEquals(List.of("base", "request-statistics", "request-purger",
                "request-video", "request-pressure", "await-statistics", "await-purger",
                "await-video", "await-pressure", "web-id", "media"), lifecycle);
    }

    @Test
    public void firstAwaitFailureCannotSkipIndependentServiceCleanup() {
        List<String> cleanup = new ArrayList<>();
        OSCARServiceModule module = new OSCARServiceModule() {
            @Override void stopBaseModule() { cleanup.add("base"); }
            @Override void requestStatisticsOutputStop() { cleanup.add("request-statistics"); }
            @Override void requestDatabasePurgerStop() { cleanup.add("request-purger"); }
            @Override void requestVideoRetentionStop() { cleanup.add("request-video"); }
            @Override void requestStoragePressureRetentionStop() { cleanup.add("request-pressure"); }

            @Override
            void awaitStatisticsOutputStop(long deadline) {
                cleanup.add("await-statistics");
                throw new IllegalStateException("fixture statistics timeout");
            }

            @Override void awaitDatabasePurgerStop(long deadline) { cleanup.add("await-purger"); }
            @Override void awaitVideoRetentionStop(long deadline) { cleanup.add("await-video"); }
            @Override void awaitStoragePressureRetentionStop(long deadline) { cleanup.add("await-pressure"); }
            @Override void unregisterWebIdHandler() { cleanup.add("web-id"); }
            @Override void unregisterMediaHandler() { cleanup.add("media"); }
        };

        SensorHubException failure = assertThrows(SensorHubException.class, module::doStop);

        assertEquals(List.of("base", "request-statistics", "request-purger", "request-video",
                "request-pressure", "await-statistics", "await-purger", "await-video",
                "await-pressure", "web-id", "media"), cleanup);
        assertTrue(failure.getMessage().contains("statistics output"));
    }

    @Test
    public void cleanupFailuresAreAggregatedAfterEveryStepRuns() {
        List<String> cleanup = new ArrayList<>();
        OSCARServiceModule module = new OSCARServiceModule() {
            @Override void stopBaseModule() { cleanup.add("base"); }
            @Override void requestStatisticsOutputStop() { cleanup.add("request-statistics"); }
            @Override void requestDatabasePurgerStop() { cleanup.add("request-purger"); }
            @Override void requestVideoRetentionStop() { cleanup.add("request-video"); }
            @Override void requestStoragePressureRetentionStop() { cleanup.add("request-pressure"); }
            @Override void awaitStatisticsOutputStop(long deadline) { cleanup.add("await-statistics"); }

            @Override
            void awaitDatabasePurgerStop(long deadline) throws SensorHubException {
                cleanup.add("await-purger");
                throw new SensorHubException("first");
            }

            @Override
            void awaitVideoRetentionStop(long deadline) {
                cleanup.add("await-video");
                throw new AssertionError("second");
            }

            @Override void awaitStoragePressureRetentionStop(long deadline) { cleanup.add("await-pressure"); }
            @Override void unregisterWebIdHandler() { cleanup.add("web-id"); }
            @Override void unregisterMediaHandler() { cleanup.add("media"); }
        };

        SensorHubException failure = assertThrows(SensorHubException.class, module::doStop);

        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("video retention"));
        assertEquals(11, cleanup.size());
    }

    @Test
    public void everyAwaitReceivesOneAbsoluteModuleDeadline() throws SensorHubException {
        List<Long> deadlines = new ArrayList<>();
        OSCARServiceModule module = new OSCARServiceModule() {
            @Override void stopBaseModule() { }
            @Override void requestStatisticsOutputStop() { }
            @Override void requestDatabasePurgerStop() { }
            @Override void requestVideoRetentionStop() { }
            @Override void requestStoragePressureRetentionStop() { }
            @Override void awaitStatisticsOutputStop(long deadline) { deadlines.add(deadline); }
            @Override void awaitDatabasePurgerStop(long deadline) { deadlines.add(deadline); }
            @Override void awaitVideoRetentionStop(long deadline) { deadlines.add(deadline); }
            @Override void awaitStoragePressureRetentionStop(long deadline) { deadlines.add(deadline); }
            @Override void unregisterWebIdHandler() { }
            @Override void unregisterMediaHandler() { }
        };

        module.doStop();

        assertEquals(4, deadlines.size());
        assertTrue(deadlines.stream().allMatch(deadline -> deadline.equals(deadlines.get(0))));
        assertTrue("OSCAR must retain headroom inside the core ten-second stop timeout",
                OSCARServiceModule.STOP_BUDGET_NANOS <
                        java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
    }

    @Test
    public void waitsForSlowStartingBucketServiceBeyondInitialBudgetAndStartsCleanly() throws Exception {
        assertTrue("Default BucketService wait budget must exceed legacy 10s cap",
                OSCARServiceModule.BUCKET_SERVICE_WAIT_TIMEOUT_MS >= 60_000L);

        SensorHub hub = new SensorHub();
        hub.start();
        try {
            var reg = hub.getModuleRegistry();

            HttpServerConfig httpCfg = new HttpServerConfig();
            httpCfg.id = UUID.randomUUID().toString();
            httpCfg.autoStart = true;
            try (ServerSocket ss = new ServerSocket(0)) {
                httpCfg.httpPort = ss.getLocalPort();
            }
            HttpServer http = (HttpServer) reg.loadModule(httpCfg);
            assertTrue(http.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));

            BucketServiceConfig bucketCfg = new BucketServiceConfig();
            bucketCfg.id = UUID.randomUUID().toString();
            bucketCfg.moduleClass = SlowStartingBucketService.class.getName();
            bucketCfg.autoStart = false;
            bucketCfg.fileStoreRootDir = tempFolder.newFolder("slow-buckets").getAbsolutePath();
            bucketCfg.initialBuckets = new ArrayList<>(List.of("reports", "videos", "sitemap", "adjudication"));
            SlowStartingBucketService bucket = (SlowStartingBucketService) reg.loadModule(bucketCfg);
            bucket.init();
            assertTrue(bucket.waitForState(ModuleEvent.ModuleState.INITIALIZED, 5_000));

            Thread delayedStartThread = new Thread(() -> {
                try {
                    bucket.start();
                } catch (SensorHubException ignored) {
                }
            }, "slow-bucket-starter");
            delayedStartThread.setDaemon(true);
            delayedStartThread.start();

            OSCARServiceModule module = new OSCARServiceModule() {
                @Override long bucketServiceWaitTimeoutMs() { return 60L; }
                @Override long bucketServiceActiveStartExtensionMs() { return 3_000L; }
                @Override long bucketServicePollSliceMs() { return 20L; }
            };
            module.setParentHub(hub);
            OSCARServiceConfig cfg = new OSCARServiceConfig();
            cfg.id = UUID.randomUUID().toString();
            cfg.nodeId = "resilient-bucket-boot-node";
            module.setConfiguration(cfg);
            module.init();
            assertTrue(module.waitForState(ModuleEvent.ModuleState.INITIALIZED, 5_000));
            module.start();
            assertTrue(module.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));
            try {
                assertTrue(module.isStarted());
                IBucketService attached = module.getBucketService();
                assertSame(bucket, attached);
                assertNotNull(attached.getBucketStore());
            } finally {
                module.stop();
            }
        } finally {
            hub.stop();
        }
    }

    @Test
    public void exitsPromptlyWhenStoppingOrInterruptedAndFailsFastWhenBucketServiceErrors() throws Exception {
        SensorHub hub = new SensorHub();
        hub.start();
        try {
            // 1. Prompt exit when stoppingRequested transitions true
            OSCARServiceModule stoppingModule = new OSCARServiceModule() {
                @Override long bucketServiceWaitTimeoutMs() { return 30_000L; }
                @Override long bucketServicePollSliceMs() { return 25L; }
            };
            stoppingModule.setParentHub(hub);

            CountDownLatch enteredWait = new CountDownLatch(1);
            Thread stopper = new Thread(() -> {
                try {
                    enteredWait.await(2, TimeUnit.SECONDS);
                    Thread.sleep(50L);
                    stoppingModule.stoppingRequested = true;
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "oscar-stop-trigger");
            stopper.setDaemon(true);
            stopper.start();

            long t0 = System.nanoTime();
            enteredWait.countDown();
            SensorHubException stopErr = assertThrows(SensorHubException.class, stoppingModule::resolveBucketServiceReady);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue("Expected prompt exit on stop (< 2000ms), took " + elapsedMs + " ms", elapsedMs < 2_000L);
            assertTrue(stopErr.getMessage().contains("stopped"));

            // 2. Prompt exit when calling thread is interrupted
            OSCARServiceModule interruptedModule = new OSCARServiceModule() {
                @Override long bucketServiceWaitTimeoutMs() { return 30_000L; }
                @Override long bucketServicePollSliceMs() { return 25L; }
            };
            interruptedModule.setParentHub(hub);
            Thread.currentThread().interrupt();
            try {
                assertThrows(SensorHubException.class, interruptedModule::resolveBucketServiceReady);
            } finally {
                Thread.interrupted(); // clear interrupt status for subsequent assertions
            }

            // 3. Fast failure when BucketService permanently fails with an error
            var reg = hub.getModuleRegistry();
            BucketServiceConfig failingCfg = new BucketServiceConfig();
            failingCfg.id = UUID.randomUUID().toString();
            failingCfg.moduleClass = FailingBucketService.class.getName();
            failingCfg.autoStart = false;
            failingCfg.fileStoreRootDir = tempFolder.newFolder("failing-buckets").getAbsolutePath();
            FailingBucketService failingBucket = (FailingBucketService) reg.loadModule(failingCfg);
            try {
                failingBucket.init();
            } catch (SensorHubException expectedSync) {
                // Synchronous init path
            }
            long waitErrDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (failingBucket.getCurrentError() == null && System.nanoTime() < waitErrDeadline) {
                Thread.sleep(20L);
            }
            assertNotNull("Expected failing bucket service to record module error", failingBucket.getCurrentError());

            OSCARServiceModule fastFailModule = new OSCARServiceModule() {
                @Override long bucketServiceWaitTimeoutMs() { return 30_000L; }
                @Override long bucketServicePollSliceMs() { return 25L; }
            };
            fastFailModule.setParentHub(hub);
            long errStart = System.nanoTime();
            SensorHubException failErr = assertThrows(SensorHubException.class, fastFailModule::resolveBucketServiceReady);
            long errElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - errStart);
            assertTrue("Expected fast failure on errored BucketService (< 2000ms), took " + errElapsedMs + " ms",
                    errElapsedMs < 2_000L);
            assertTrue(failErr.getMessage().contains("Bucket Service"));
        } finally {
            hub.stop();
        }
    }
}
