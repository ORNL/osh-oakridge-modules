package org.sensorhub.impl.utils.rad.eml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * Isolated dynamically loaded EML runtime backed by a dedicated URLClassLoader.
 * Zero LLNL classes are referenced at compile time or allowed to escape this wrapper.
 */
public final class EmlLoadedRuntime implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(EmlLoadedRuntime.class);
    private static final Object GLOBAL_INIT_LOCK = new Object();
    private static final int MAX_POOL_SIZE_PER_MODE = 4;

    private final Path extractedRoot;
    private final Path configDir;
    private final Path collimatedModelFile;
    private final Path uncollimatedModelFile;
    private final Path metricCsvFile;
    private final URLClassLoader classLoader;
    private final EmlRuntimeStatus status;

    private final Class<?> docReaderCls;
    private final Method createReaderMethod;
    private final Method loadFileMethod;
    private final Class<?> laneDbCls;
    private final Class<?> dailyLoaderCls;
    private final Constructor<?> dailyLoaderCtor;
    private final Method setDefaultIntervalsMethod;
    private final Method setDefaultOccupancyHoldinMethod;
    private final Method setBatchModeMethod;
    private final Method loadFirstOccupancyMethod;

    private final Class<?> analysisCls;
    private final Class<?> vm250AnalysisCls;
    private final Class<?> classifierCls;
    private final Constructor<?> classifierCtor;
    private final Method getFeatureNamesMethod;
    private final Method setClassifierMethod;
    private final Method processRecordMethod;
    private final Method allocateResultMethod;

    private final Class<?> internalRecordCls;
    private final Method getLaneMethod;
    private final Method setPanelsMethod;
    private final Method setLaneWidthMethod;
    private final Constructor<?> vm250RecordCtor;
    private final Constructor<?> resultsCtor;
    private final Method setPortIdMethod;
    private final Method setLaneIdLongMethod;
    private final Method setLaneIdIntMethod;

    private final Method getResultMethod;
    private final Method isFallbackMethod;
    private final Method getExceptionMethod;
    private final Method getReleaseProbabilityMethod;
    private final Method getInvestigateProbabilityMethod;
    private final Method getErnieGammaAlertMethod;
    private final Method getErnieNeutronAlertMethod;
    private final Method getVehicleClassMethod;
    private final Method getVehicleLengthMethod;
    private final Method getYellowLightMessageMethod;
    private final Method getVersionIdMethod;
    private final Method getModelIdMethod;
    private final Method getThresholdsMethod;
    private final Method getPortIdMethod;
    private final Method getLaneIdMethod;
    private final Method getDateTimeMethod;
    private final Method getSegmentIdMethod;
    private final Method getRpmResultMethod;
    private final Method getRpmGammaAlertMethod;
    private final Method getRpmNeutronAlertMethod;
    private final Method getRpmScanErrorMethod;
    private final Method getNumberOfSourcesMethod;
    private final Method getSourceMethod;
    private final Method getOverallSourceMethod;

    private final Object laneDatabase;
    private final Object collimatedLaneDef;
    private final Object uncollimatedLaneDef;

    private final ArrayBlockingQueue<Object> collimatedPool = new ArrayBlockingQueue<>(MAX_POOL_SIZE_PER_MODE);
    private final ArrayBlockingQueue<Object> uncollimatedPool = new ArrayBlockingQueue<>(MAX_POOL_SIZE_PER_MODE);
    private final AtomicInteger collimatedCreated = new AtomicInteger();
    private final AtomicInteger uncollimatedCreated = new AtomicInteger();

    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private volatile boolean closed = false;

    private EmlLoadedRuntime(
            String archiveFileName,
            String sha256,
            EmlArchiveExtractor.ExtractedBundle bundle) throws Exception {
        this.extractedRoot = bundle.rootDir();
        this.configDir = bundle.configDir();
        this.collimatedModelFile = resolveModelFile(configDir, true);
        this.uncollimatedModelFile = resolveModelFile(configDir, false);
        this.metricCsvFile = configDir.resolve("CMU_metric.csv");
        if (!Files.isRegularFile(metricCsvFile)) {
            throw new IOException("EML config directory is missing CMU_metric.csv");
        }
        if (!Files.isRegularFile(configDir.resolve("vm250LaneDatabase.xml"))) {
            throw new IOException("EML config directory is missing vm250LaneDatabase.xml");
        }

        URL[] urls = new URL[bundle.jarFiles().size()];
        for (int i = 0; i < bundle.jarFiles().size(); i++) {
            urls[i] = bundle.jarFiles().get(i).toUri().toURL();
        }
        this.classLoader = new URLClassLoader(urls, EmlLoadedRuntime.class.getClassLoader());

        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(this.classLoader);
        try {
            synchronized (GLOBAL_INIT_LOCK) {
                this.docReaderCls = Class.forName("gov.llnl.utility.xml.bind.DocumentReader", true, classLoader);
                this.createReaderMethod = docReaderCls.getMethod("create", Class.class);
                this.loadFileMethod = docReaderCls.getMethod("loadFile", Path.class);

                this.laneDbCls = Class.forName("gov.llnl.ernie.data.LaneDatabase", true, classLoader);
                Class<?> vm250LaneDbCls = Class.forName("gov.llnl.ernie.vm250.VM250LaneDatabase", true, classLoader);
                Class<?> laneDefCls = Class.forName("gov.llnl.ernie.data.LaneDefinition", true, classLoader);

                Object laneDbReader = createReaderMethod.invoke(null, laneDbCls);
                this.laneDatabase = loadFileMethod.invoke(laneDbReader, configDir.resolve("vm250LaneDatabase.xml"));

                Object foundCollimated = null;
                Object foundUncollimated = null;
                Iterable<?> defs = (Iterable<?>) vm250LaneDbCls.getMethod("getDefaultLanes").invoke(laneDatabase);
                Method isCollMethod = laneDefCls.getMethod("isCollimated");
                for (Object def : defs) {
                    boolean coll = (boolean) isCollMethod.invoke(def);
                    if (coll && foundCollimated == null)
                        foundCollimated = def;
                    else if (!coll && foundUncollimated == null)
                        foundUncollimated = def;
                }
                if (foundCollimated == null && foundUncollimated == null) {
                    throw new IllegalStateException("vm250LaneDatabase.xml defined no default lanes");
                }
                this.collimatedLaneDef = foundCollimated != null ? foundCollimated : foundUncollimated;
                this.uncollimatedLaneDef = foundUncollimated != null ? foundUncollimated : foundCollimated;

                this.dailyLoaderCls = Class.forName("gov.llnl.ernie.vm250.tools.DailyFileLoader", true, classLoader);
                this.dailyLoaderCtor = dailyLoaderCls.getConstructor(laneDbCls);
                this.setDefaultIntervalsMethod = dailyLoaderCls.getMethod("setDefaultIntervals", int.class);
                this.setDefaultOccupancyHoldinMethod = dailyLoaderCls.getMethod("setDefaultOccupancyHoldin", int.class);
                this.setBatchModeMethod = dailyLoaderCls.getMethod("setBatchMode", boolean.class);
                this.loadFirstOccupancyMethod = dailyLoaderCls.getMethod("loadFirstOccupancy", Stream.class);

                this.analysisCls = Class.forName("gov.llnl.ernie.Analysis", true, classLoader);
                this.vm250AnalysisCls = Class.forName("gov.llnl.ernie.vm250.VM250Analysis", true, classLoader);
                this.classifierCls = Class.forName("gov.llnl.ernie.classifier.Classifier", true, classLoader);
                this.classifierCtor = classifierCls.getConstructor(String.class, String.class);
                this.getFeatureNamesMethod = findOptionalMethod(classifierCls, "getFeatureNames");
                this.setClassifierMethod = vm250AnalysisCls.getMethod("setClassifier", classifierCls);

                Class<?> recordIface = Class.forName("gov.llnl.ernie.data.Record", true, classLoader);
                Class<?> analysisResultCls = Class.forName("gov.llnl.ernie.AnalysisResult", true, classLoader);
                this.processRecordMethod = analysisCls.getMethod("processRecord", recordIface);
                this.allocateResultMethod = analysisCls.getMethod("allocateResult");

                this.internalRecordCls = Class.forName("gov.llnl.ernie.vm250.data.VM250RecordInternal", true, classLoader);
                Class<?> laneImplCls = Class.forName("gov.llnl.ernie.data.LaneImpl", true, classLoader);
                Class<?> vm250RecordCls = Class.forName("gov.llnl.ernie.vm250.data.VM250Record", true, classLoader);
                this.getLaneMethod = internalRecordCls.getMethod("getLane");
                this.setPanelsMethod = laneImplCls.getMethod("setPanels", int.class);
                this.setLaneWidthMethod = laneImplCls.getMethod("setLaneWidth", double.class);
                this.vm250RecordCtor = vm250RecordCls.getConstructor(internalRecordCls);

                Class<?> resultsCls = Class.forName("gov.llnl.ernie.api.Results", true, classLoader);
                this.resultsCtor = resultsCls.getDeclaredConstructor(analysisCls, analysisResultCls);
                this.resultsCtor.setAccessible(true);
                this.setPortIdMethod = findOptionalMethod(resultsCls, "setPortID", String.class);
                this.setLaneIdLongMethod = findOptionalMethod(resultsCls, "setLaneID", long.class);
                this.setLaneIdIntMethod = findOptionalMethod(resultsCls, "setLaneID", int.class);

                this.getResultMethod = resultsCls.getMethod("getResult");
                this.isFallbackMethod = resultsCls.getMethod("isFallback");
                this.getExceptionMethod = resultsCls.getMethod("getException");
                this.getReleaseProbabilityMethod = resultsCls.getMethod("getReleaseProbability");
                this.getInvestigateProbabilityMethod = resultsCls.getMethod("getInvestigateProbability");
                this.getErnieGammaAlertMethod = resultsCls.getMethod("getERNIEGammaAlert");
                this.getErnieNeutronAlertMethod = resultsCls.getMethod("getERNIENeutronAlert");
                this.getVehicleClassMethod = resultsCls.getMethod("getVehicleClass");
                this.getVehicleLengthMethod = resultsCls.getMethod("getVehicleLength");
                this.getYellowLightMessageMethod = resultsCls.getMethod("getYellowLightMessage");
                this.getVersionIdMethod = resultsCls.getMethod("getVersionID");
                this.getModelIdMethod = resultsCls.getMethod("getModelID");
                this.getThresholdsMethod = findOptionalMethod(resultsCls, "getThresholds");
                this.getPortIdMethod = resultsCls.getMethod("getPortID");
                this.getLaneIdMethod = resultsCls.getMethod("getLaneID");
                this.getDateTimeMethod = resultsCls.getMethod("getDateTime");
                this.getSegmentIdMethod = resultsCls.getMethod("getSegmentId");
                this.getRpmResultMethod = resultsCls.getMethod("getRPMResult");
                this.getRpmGammaAlertMethod = resultsCls.getMethod("getRPMGammaAlert");
                this.getRpmNeutronAlertMethod = resultsCls.getMethod("getRPMNeutronAlert");
                this.getRpmScanErrorMethod = resultsCls.getMethod("getRPMScanError");
                this.getNumberOfSourcesMethod = resultsCls.getMethod("getNumberOfSources");
                this.getSourceMethod = resultsCls.getMethod("getSource", int.class);
                this.getOverallSourceMethod = resultsCls.getMethod("getOverallSource");

                // Pre-warm one collimated analyzer immediately to validate XML + classifier weights on upload
                Object initialAnalyzer = createAnalyzerInstance(true);
                String detectedVersion = detectVersionId(initialAnalyzer, archiveFileName);
                String detectedCollimatedModel = detectModelId(initialAnalyzer, collimatedModelFile);
                String uncollimatedModelHint = stripExtension(uncollimatedModelFile.getFileName().toString());
                collimatedCreated.set(1);
                collimatedPool.offer(initialAnalyzer);

                this.status = new EmlRuntimeStatus(
                        true,
                        archiveFileName,
                        sha256,
                        detectedVersion,
                        detectedCollimatedModel,
                        uncollimatedModelHint,
                        Instant.now(),
                        "");
            }
        } catch (Throwable t) {
            try {
                this.classLoader.close();
            } catch (IOException ignored) {
            }
            EmlArchiveExtractor.deleteRecursivelyQuietly(this.extractedRoot);
            if (t instanceof InvocationTargetException ite && ite.getCause() instanceof Exception cause)
                throw cause;
            if (t instanceof Exception ex)
                throw ex;
            throw new RuntimeException(t);
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    public static EmlLoadedRuntime open(String archiveFileName, String sha256, EmlArchiveExtractor.ExtractedBundle bundle)
            throws Exception {
        return new EmlLoadedRuntime(archiveFileName, sha256, bundle);
    }

    public EmlRuntimeStatus getStatus() {
        return status;
    }

    public EmlEvaluationResult evaluate(EmlLaneParameters params, Stream<String> occupancyLines) throws Exception {
        lifecycleLock.readLock().lock();
        try {
            if (closed)
                throw new IllegalStateException("EML runtime has been closed");

            ClassLoader prev = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);
            Object analyzer = null;
            try {
                Object loader = dailyLoaderCtor.newInstance(laneDatabase);
                setDefaultIntervalsMethod.invoke(loader, params.intervals());
                setDefaultOccupancyHoldinMethod.invoke(loader, params.occupancyHoldin());
                setBatchModeMethod.invoke(loader, false);

                Object internal = loadFirstOccupancyMethod.invoke(loader, occupancyLines);
                if (internal == null) {
                    return null;
                }

                Object lane = getLaneMethod.invoke(internal);
                setPanelsMethod.invoke(lane, 4);
                Object laneDef = params.collimated() ? collimatedLaneDef : uncollimatedLaneDef;
                invokeAssignLane(lane, laneDef);
                setLaneWidthMethod.invoke(lane, params.laneWidth());

                Object vm250Rec = vm250RecordCtor.newInstance(internal);
                analyzer = borrowAnalyzer(params.collimated());
                Object analysisRes;
                synchronized (analyzer) {
                    try {
                        analysisRes = processRecordMethod.invoke(analyzer, vm250Rec);
                    } catch (InvocationTargetException ite) {
                        Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                        logger.debug("EML processRecord reported analysis fallback on lane {}: {}",
                                params.portId(), cause.getMessage());
                        String modelUsed = params.collimated() ? status.collimatedModelId() : status.uncollimatedModelId();
                        return new EmlEvaluationResult(
                                status.versionId(),
                                modelUsed,
                                0.05,
                                params.portId(),
                                params.laneId(),
                                System.currentTimeMillis(),
                                0L,
                                "ALARM",
                                false,
                                false,
                                false,
                                "INVESTIGATE",
                                true,
                                cause.toString(),
                                1.0,
                                0.0,
                                false,
                                false,
                                0,
                                0.0,
                                "",
                                List.of(),
                                null);
                    }
                    Object results = resultsCtor.newInstance(analyzer, analysisRes);
                    if (setPortIdMethod != null)
                        setPortIdMethod.invoke(results, params.portId());
                    if (setLaneIdLongMethod != null)
                        setLaneIdLongMethod.invoke(results, (long) params.laneId());
                    else if (setLaneIdIntMethod != null)
                        setLaneIdIntMethod.invoke(results, params.laneId());
                    return toPureResult(results, params);
                }
            } catch (InvocationTargetException ite) {
                if (ite.getCause() instanceof Exception ex)
                    throw ex;
                throw ite;
            } finally {
                if (analyzer != null) {
                    returnAnalyzer(params.collimated(), analyzer);
                }
                Thread.currentThread().setContextClassLoader(prev);
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private Object borrowAnalyzer(boolean collimated) throws Exception {
        ArrayBlockingQueue<Object> pool = collimated ? collimatedPool : uncollimatedPool;
        AtomicInteger created = collimated ? collimatedCreated : uncollimatedCreated;

        Object existing = pool.poll();
        if (existing != null)
            return existing;

        while (true) {
            int current = created.get();
            if (current >= MAX_POOL_SIZE_PER_MODE) {
                return pool.take();
            }
            if (created.compareAndSet(current, current + 1)) {
                try {
                    return createAnalyzerInstance(collimated);
                } catch (Exception e) {
                    created.decrementAndGet();
                    throw e;
                }
            }
        }
    }

    private void returnAnalyzer(boolean collimated, Object analyzer) {
        ArrayBlockingQueue<Object> pool = collimated ? collimatedPool : uncollimatedPool;
        pool.offer(analyzer);
    }

    private Object createAnalyzerInstance(boolean collimated) throws Exception {
        synchronized (GLOBAL_INIT_LOCK) {
            Object analysisReader = createReaderMethod.invoke(null, analysisCls);
            Object analysis = loadFileMethod.invoke(analysisReader, configDir.resolve("vm250Analysis.xml"));
            Path modelFile = collimated ? collimatedModelFile : uncollimatedModelFile;
            Object classifier = classifierCtor.newInstance(
                    modelFile.toAbsolutePath().toString(),
                    metricCsvFile.toAbsolutePath().toString());
            if (getFeatureNamesMethod != null) {
                getFeatureNamesMethod.invoke(classifier);
            }
            setClassifierMethod.invoke(analysis, classifier);
            return analysis;
        }
    }

    private String detectVersionId(Object initialAnalyzer, String archiveFileName) {
        try {
            Class<?> ctxImplCls = Class.forName("gov.llnl.ernie.data.AnalysisContextualInfoImpl", true, classLoader);
            Object ctx = ctxImplCls.getConstructor().newInstance();
            Method verMethod = findOptionalDeclaredOrPublic(ctxImplCls, "getVersionID");
            if (verMethod != null) {
                String ver = safeString(verMethod.invoke(ctx)).trim();
                if (!ver.isEmpty())
                    return ver;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object emptyResults = resultsCtor.newInstance(initialAnalyzer, allocateResultMethod.invoke(initialAnalyzer));
            String ver = safeString(getVersionIdMethod.invoke(emptyResults)).trim();
            if (!ver.isEmpty())
                return ver;
        } catch (Throwable ignored) {
        }
        if (archiveFileName != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?(?:-[A-Za-z0-9._-]+)?)")
                    .matcher(archiveFileName);
            if (m.find())
                return m.group(1);
        }
        return "unknown";
    }

    private String detectModelId(Object initialAnalyzer, Path modelFile) {
        try {
            Method getModelName = findOptionalDeclaredOrPublic(initialAnalyzer.getClass(), "getModelName");
            if (getModelName != null) {
                String model = safeString(getModelName.invoke(initialAnalyzer)).trim();
                if (!model.isEmpty())
                    return model;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object emptyResults = resultsCtor.newInstance(initialAnalyzer, allocateResultMethod.invoke(initialAnalyzer));
            String model = safeString(getModelIdMethod.invoke(emptyResults)).trim();
            if (!model.isEmpty())
                return model;
        } catch (Throwable ignored) {
        }
        return stripExtension(modelFile.getFileName().toString());
    }

    private EmlEvaluationResult toPureResult(Object results, EmlLaneParameters params) throws Exception {
        String versionId = safeString(getVersionIdMethod.invoke(results));
        String modelId = safeString(getModelIdMethod.invoke(results));
        double primaryThreshold = extractPrimaryThreshold(results);

        String portId = safeString(getPortIdMethod.invoke(results));
        if (portId.isEmpty())
            portId = params.portId();
        long laneId = toLong(getLaneIdMethod.invoke(results), params.laneId());
        long dateTimeMillis = extractEpochMillis(getDateTimeMethod.invoke(results));
        long segmentId = toLong(getSegmentIdMethod.invoke(results), 0L);

        Object rpmResObj = getRpmResultMethod.invoke(results);
        String rpmResult = rpmResObj != null ? rpmResObj.toString() : "NONE";
        boolean rpmGamma = Boolean.TRUE.equals(getRpmGammaAlertMethod.invoke(results));
        boolean rpmNeutron = Boolean.TRUE.equals(getRpmNeutronAlertMethod.invoke(results));
        boolean rpmScanError = Boolean.TRUE.equals(getRpmScanErrorMethod.invoke(results));

        Object resObj = getResultMethod.invoke(results);
        String action = resObj != null ? resObj.toString() : "NONE";
        boolean fallback = Boolean.TRUE.equals(isFallbackMethod.invoke(results));
        Object excObj = getExceptionMethod.invoke(results);
        String excMessage = excObj != null ? excObj.toString() : "";

        double releaseProb = toDouble(getReleaseProbabilityMethod.invoke(results), 0.0);
        double investigateProb = toDouble(getInvestigateProbabilityMethod.invoke(results), 0.0);
        boolean ernieGamma = Boolean.TRUE.equals(getErnieGammaAlertMethod.invoke(results));
        boolean ernieNeutron = Boolean.TRUE.equals(getErnieNeutronAlertMethod.invoke(results));
        int vehicleClass = (int) toLong(getVehicleClassMethod.invoke(results), 0L);
        double vehicleLength = toDouble(getVehicleLengthMethod.invoke(results), 0.0);
        String yellowLightMsg = safeString(getYellowLightMessageMethod.invoke(results));

        int numSources = (int) toLong(getNumberOfSourcesMethod.invoke(results), 0L);
        List<EmlSourceBreakdown> sources = new ArrayList<>(Math.max(0, numSources));
        for (int i = 0; i < numSources; i++) {
            Object src = getSourceMethod.invoke(results, i);
            if (src != null)
                sources.add(extractSource(src, modelId));
        }

        Object overallObj = getOverallSourceMethod.invoke(results);
        EmlSourceBreakdown overall = overallObj != null ? extractSource(overallObj, modelId) : null;

        return new EmlEvaluationResult(
                versionId,
                modelId,
                primaryThreshold,
                portId,
                laneId,
                dateTimeMillis,
                segmentId,
                rpmResult,
                rpmGamma,
                rpmNeutron,
                rpmScanError,
                action,
                fallback,
                excMessage,
                investigateProb,
                releaseProb,
                ernieGamma,
                ernieNeutron,
                vehicleClass,
                vehicleLength,
                yellowLightMsg,
                sources,
                overall);
    }

    private EmlSourceBreakdown extractSource(Object src, String fallbackModelId) throws Exception {
        Class<?> cls = src.getClass();
        String sourceType = safeString(invokeDeclaredOrPublic(src, cls, "getSourceType"));
        Method classifierMethod = findOptionalDeclaredOrPublic(cls, "getClassifierUsed");
        String classifierUsed = classifierMethod != null ? safeString(classifierMethod.invoke(src)) : fallbackModelId;
        if (classifierUsed.isEmpty())
            classifierUsed = fallbackModelId;

        double x1 = toDouble(invokeDeclaredOrPublic(src, cls, "getxLocation1"), 0.0);
        double x2 = toDouble(invokeDeclaredOrPublic(src, cls, "getxLocation2"), 0.0);
        double y = toDouble(invokeDeclaredOrPublic(src, cls, "getyLocation"), 0.0);
        double z = toDouble(invokeDeclaredOrPublic(src, cls, "getzLocation"), 0.0);
        double nonEmit = toDouble(invokeDeclaredOrPublic(src, cls, "getProbabilityNonEmitting"), 0.0);
        double norm = toDouble(invokeDeclaredOrPublic(src, cls, "getProbabilityNORM"), 0.0);
        double threat = toDouble(invokeDeclaredOrPublic(src, cls, "getProbabilityThreat"), 0.0);

        return new EmlSourceBreakdown(sourceType, classifierUsed, x1, x2, y, z, nonEmit, norm, threat);
    }

    private double extractPrimaryThreshold(Object results) {
        if (getThresholdsMethod == null)
            return 0.05;
        try {
            Object thresholds = getThresholdsMethod.invoke(results);
            if (thresholds == null)
                return 0.05;
            Method getPrimary = findOptionalDeclaredOrPublic(thresholds.getClass(), "getPrimary");
            if (getPrimary != null)
                return toDouble(getPrimary.invoke(thresholds), 0.05);
        } catch (Exception ignored) {
        }
        return 0.05;
    }

    private static long extractEpochMillis(Object dateTimeObj) {
        if (dateTimeObj == null)
            return System.currentTimeMillis();
        if (dateTimeObj instanceof Instant instant)
            return instant.toEpochMilli();
        if (dateTimeObj instanceof Number num)
            return num.longValue();
        try {
            Method toEpochMilli = dateTimeObj.getClass().getMethod("toEpochMilli");
            return toLong(toEpochMilli.invoke(dateTimeObj), System.currentTimeMillis());
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }

    private static void invokeAssignLane(Object lane, Object laneDef) throws Exception {
        for (Method m : lane.getClass().getMethods()) {
            if ("assign".equals(m.getName()) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(laneDef)) {
                m.invoke(lane, laneDef);
                return;
            }
        }
    }

    private static Object invokeDeclaredOrPublic(Object target, Class<?> cls, String name) throws Exception {
        Method m = findOptionalDeclaredOrPublic(cls, name);
        return m != null ? m.invoke(target) : null;
    }

    private static Method findOptionalDeclaredOrPublic(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
            try {
                Method m = cls.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored2) {
                return null;
            }
        }
    }

    private static Method findOptionalMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Path resolveModelFile(Path configDir, boolean collimated) throws IOException {
        List<String> candidates = collimated
                ? List.of("multi_site_collimated.txt", "uber_SSL_model_collimated.txt")
                : List.of("multi_site_uncollimated.txt", "uber_SSL_model_uncollimated.txt");
        for (String name : candidates) {
            Path p = configDir.resolve(name);
            if (Files.isRegularFile(p))
                return p;
        }
        String suffix = collimated ? "_collimated.txt" : "_uncollimated.txt";
        try (Stream<Path> list = Files.list(configDir)) {
            Path dynamic = list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElse(null);
            if (dynamic != null)
                return dynamic;
        }
        throw new IOException("EML config directory is missing " + (collimated ? "collimated" : "uncollimated") + " classifier model");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safeString(Object o) {
        return o == null ? "" : o.toString();
    }

    private static double toDouble(Object o, double fallback) {
        if (o instanceof Number n)
            return n.doubleValue();
        return fallback;
    }

    private static long toLong(Object o, long fallback) {
        if (o instanceof Number n)
            return n.longValue();
        return fallback;
    }

    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed)
                return;
            closed = true;
            collimatedPool.clear();
            uncollimatedPool.clear();
            try {
                classLoader.close();
            } catch (IOException e) {
                logger.debug("Error closing retired EML URLClassLoader", e);
            }
            EmlArchiveExtractor.deleteRecursivelyQuietly(extractedRoot);
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }
}
