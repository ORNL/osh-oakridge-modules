package org.sensorhub.impl.utils.rad.eml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Isolated dynamically loaded EML runtime backed by a dedicated URLClassLoader.
 * Supports both the public {@link EmlAlarmAssessmentAlgorithm} SPI (including
 * zero-dependency standalone assessment JARs) and the external LLNL ERNIE VM-250
 * runtime contract.
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
    private final EmlAlarmAssessmentAlgorithm customAlgorithm;

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

        URL[] urls = new URL[bundle.jarFiles().size()];
        for (int i = 0; i < bundle.jarFiles().size(); i++) {
            urls[i] = bundle.jarFiles().get(i).toUri().toURL();
        }
        this.classLoader = new URLClassLoader(urls, EmlLoadedRuntime.class.getClassLoader());

        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(this.classLoader);
        try {
            synchronized (GLOBAL_INIT_LOCK) {
                EmlAlarmAssessmentAlgorithm discoveredCustom = discoverCustomAlgorithm(classLoader, extractedRoot, configDir);
                if (discoveredCustom != null) {
                    this.customAlgorithm = discoveredCustom;
                    this.customAlgorithm.initialize(extractedRoot);
                    String ver = safeString(this.customAlgorithm.getVersionId()).trim();
                    if (ver.isEmpty()) {
                        ver = "1.0.0";
                    }
                    String collModel = safeString(this.customAlgorithm.getCollimatedModelId()).trim();
                    if (collModel.isEmpty()) {
                        collModel = this.customAlgorithm.getClass().getSimpleName();
                    }
                    String uncollModel = safeString(this.customAlgorithm.getUncollimatedModelId()).trim();
                    if (uncollModel.isEmpty()) {
                        uncollModel = collModel;
                    }
                    this.collimatedModelFile = null;
                    this.uncollimatedModelFile = null;
                    this.metricCsvFile = null;
                    this.docReaderCls = null;
                    this.createReaderMethod = null;
                    this.loadFileMethod = null;
                    this.laneDbCls = null;
                    this.dailyLoaderCls = null;
                    this.dailyLoaderCtor = null;
                    this.setDefaultIntervalsMethod = null;
                    this.setDefaultOccupancyHoldinMethod = null;
                    this.setBatchModeMethod = null;
                    this.loadFirstOccupancyMethod = null;
                    this.analysisCls = null;
                    this.vm250AnalysisCls = null;
                    this.classifierCls = null;
                    this.classifierCtor = null;
                    this.getFeatureNamesMethod = null;
                    this.setClassifierMethod = null;
                    this.processRecordMethod = null;
                    this.allocateResultMethod = null;
                    this.internalRecordCls = null;
                    this.getLaneMethod = null;
                    this.setPanelsMethod = null;
                    this.setLaneWidthMethod = null;
                    this.vm250RecordCtor = null;
                    this.resultsCtor = null;
                    this.setPortIdMethod = null;
                    this.setLaneIdLongMethod = null;
                    this.setLaneIdIntMethod = null;
                    this.getResultMethod = null;
                    this.isFallbackMethod = null;
                    this.getExceptionMethod = null;
                    this.getReleaseProbabilityMethod = null;
                    this.getInvestigateProbabilityMethod = null;
                    this.getErnieGammaAlertMethod = null;
                    this.getErnieNeutronAlertMethod = null;
                    this.getVehicleClassMethod = null;
                    this.getVehicleLengthMethod = null;
                    this.getYellowLightMessageMethod = null;
                    this.getVersionIdMethod = null;
                    this.getModelIdMethod = null;
                    this.getThresholdsMethod = null;
                    this.getPortIdMethod = null;
                    this.getLaneIdMethod = null;
                    this.getDateTimeMethod = null;
                    this.getSegmentIdMethod = null;
                    this.getRpmResultMethod = null;
                    this.getRpmGammaAlertMethod = null;
                    this.getRpmNeutronAlertMethod = null;
                    this.getRpmScanErrorMethod = null;
                    this.getNumberOfSourcesMethod = null;
                    this.getSourceMethod = null;
                    this.getOverallSourceMethod = null;
                    this.laneDatabase = null;
                    this.collimatedLaneDef = null;
                    this.uncollimatedLaneDef = null;
                    this.status = new EmlRuntimeStatus(
                            true,
                            archiveFileName,
                            sha256,
                            ver,
                            collModel,
                            uncollModel,
                            Instant.now(),
                            "");
                } else {
                    this.customAlgorithm = null;
                    this.collimatedModelFile = resolveModelFile(configDir, true);
                    this.uncollimatedModelFile = resolveModelFile(configDir, false);
                    this.metricCsvFile = configDir.resolve("CMU_metric.csv");
                    if (!Files.isRegularFile(metricCsvFile)) {
                        throw new IOException("EML config directory is missing CMU_metric.csv");
                    }
                    if (!Files.isRegularFile(configDir.resolve("vm250LaneDatabase.xml"))) {
                        throw new IOException("EML config directory is missing vm250LaneDatabase.xml");
                    }

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
            if (customAlgorithm != null) {
                try {
                    return customAlgorithm.evaluate(params, occupancyLines);
                } finally {
                    Thread.currentThread().setContextClassLoader(prev);
                }
            }
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

    private static EmlAlarmAssessmentAlgorithm discoverCustomAlgorithm(
            URLClassLoader classLoader,
            Path extractedRoot,
            Path configDir) throws Exception {
        ServiceLoader<EmlAlarmAssessmentAlgorithm> loader = ServiceLoader.load(EmlAlarmAssessmentAlgorithm.class, classLoader);
        for (EmlAlarmAssessmentAlgorithm impl : loader) {
            return impl;
        }

        String className = resolveCustomAlgorithmClassName(classLoader, extractedRoot, configDir);
        if (className == null || className.isBlank()) {
            return null;
        }
        Class<?> rawCls = Class.forName(className.trim(), true, classLoader);
        Object instance = rawCls.getDeclaredConstructor().newInstance();
        if (instance instanceof EmlAlarmAssessmentAlgorithm typed) {
            return typed;
        }
        return new ReflectiveCustomAlgorithmAdapter(instance);
    }

    private static String resolveCustomAlgorithmClassName(
            URLClassLoader classLoader,
            Path extractedRoot,
            Path configDir) throws IOException {
        List<Path> propCandidates = List.of(
                configDir.resolve(EmlAlarmAssessmentAlgorithm.DESCRIPTOR_FILENAME),
                extractedRoot.resolve(EmlAlarmAssessmentAlgorithm.DESCRIPTOR_FILENAME),
                extractedRoot.resolve("META-INF").resolve(EmlAlarmAssessmentAlgorithm.DESCRIPTOR_FILENAME));
        for (Path p : propCandidates) {
            if (Files.isRegularFile(p)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                }
                String cls = props.getProperty("class");
                if (cls != null && !cls.isBlank()) {
                    return cls.trim();
                }
            }
        }

        URL propUrl = classLoader.findResource("META-INF/" + EmlAlarmAssessmentAlgorithm.DESCRIPTOR_FILENAME);
        if (propUrl == null) {
            propUrl = classLoader.findResource(EmlAlarmAssessmentAlgorithm.DESCRIPTOR_FILENAME);
        }
        if (propUrl != null) {
            Properties props = new Properties();
            try (InputStream in = propUrl.openStream()) {
                props.load(in);
            }
            String cls = props.getProperty("class");
            if (cls != null && !cls.isBlank()) {
                return cls.trim();
            }
        }

        Path extractedSpi = extractedRoot.resolve("META-INF").resolve("services")
                .resolve(EmlAlarmAssessmentAlgorithm.class.getName());
        if (Files.isRegularFile(extractedSpi)) {
            for (String line : Files.readAllLines(extractedSpi, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    return trimmed;
                }
            }
        }

        Enumeration<URL> manifests = classLoader.findResources("META-INF/MANIFEST.MF");
        while (manifests.hasMoreElements()) {
            URL url = manifests.nextElement();
            try (InputStream in = url.openStream()) {
                Manifest mf = new Manifest(in);
                String attr = mf.getMainAttributes().getValue(EmlAlarmAssessmentAlgorithm.MANIFEST_CLASS_ATTRIBUTE);
                if (attr != null && !attr.isBlank()) {
                    return attr.trim();
                }
            } catch (IOException ignored) {
            }
        }
        Path extractedManifest = extractedRoot.resolve("META-INF").resolve("MANIFEST.MF");
        if (Files.isRegularFile(extractedManifest)) {
            try (InputStream in = Files.newInputStream(extractedManifest)) {
                Manifest mf = new Manifest(in);
                String attr = mf.getMainAttributes().getValue(EmlAlarmAssessmentAlgorithm.MANIFEST_CLASS_ATTRIBUTE);
                if (attr != null && !attr.isBlank()) {
                    return attr.trim();
                }
            }
        }
        return null;
    }

    private static final class ReflectiveCustomAlgorithmAdapter implements EmlAlarmAssessmentAlgorithm {
        private final Object delegate;
        private final Method initMethod;
        private final Method versionMethod;
        private final Method collModelMethod;
        private final Method uncollModelMethod;
        private final Method evalTypedMethod;
        private final Method evalMapMethod;
        private final Method closeMethod;

        ReflectiveCustomAlgorithmAdapter(Object delegate) throws NoSuchMethodException {
            this.delegate = delegate;
            Class<?> cls = delegate.getClass();
            this.initMethod = findOptionalDeclaredOrPublic(cls, "initialize", Path.class);
            this.versionMethod = findOptionalDeclaredOrPublic(cls, "getVersionId");
            this.collModelMethod = findOptionalDeclaredOrPublic(cls, "getCollimatedModelId");
            this.uncollModelMethod = findOptionalDeclaredOrPublic(cls, "getUncollimatedModelId");
            this.evalTypedMethod = findOptionalDeclaredOrPublic(cls, "evaluate", EmlLaneParameters.class, List.class);
            this.evalMapMethod = findOptionalDeclaredOrPublic(cls, "evaluate", Map.class, List.class);
            this.closeMethod = findOptionalDeclaredOrPublic(cls, "close");
            if (this.evalTypedMethod == null && this.evalMapMethod == null) {
                throw new NoSuchMethodException(cls.getName()
                        + " must implement EmlAlarmAssessmentAlgorithm or define evaluate(Map<String,Object>, List<String>)");
            }
        }

        @Override
        public void initialize(Path extractedPluginRoot) throws Exception {
            if (initMethod != null) {
                initMethod.invoke(delegate, extractedPluginRoot);
            }
        }

        @Override
        public String getVersionId() {
            if (versionMethod != null) {
                try {
                    return safeString(versionMethod.invoke(delegate));
                } catch (Exception ignored) {
                }
            }
            return "1.0.0";
        }

        @Override
        public String getCollimatedModelId() {
            if (collModelMethod != null) {
                try {
                    return safeString(collModelMethod.invoke(delegate));
                } catch (Exception ignored) {
                }
            }
            return delegate.getClass().getSimpleName();
        }

        @Override
        public String getUncollimatedModelId() {
            if (uncollModelMethod != null) {
                try {
                    return safeString(uncollModelMethod.invoke(delegate));
                } catch (Exception ignored) {
                }
            }
            return getCollimatedModelId();
        }

        @Override
        @SuppressWarnings("unchecked")
        public EmlEvaluationResult evaluate(EmlLaneParameters laneParameters, List<String> dailyFileLines) throws Exception {
            Object raw;
            if (evalTypedMethod != null) {
                raw = evalTypedMethod.invoke(delegate, laneParameters, dailyFileLines);
            } else {
                Map<String, Object> paramMap = new LinkedHashMap<>();
                paramMap.put("portId", laneParameters.portId());
                paramMap.put("laneId", laneParameters.laneId());
                paramMap.put("collimated", laneParameters.collimated());
                paramMap.put("laneWidth", laneParameters.laneWidth());
                paramMap.put("intervals", laneParameters.intervals());
                paramMap.put("occupancyHoldin", laneParameters.occupancyHoldin());
                raw = evalMapMethod.invoke(delegate, paramMap, dailyFileLines);
            }
            if (raw == null) {
                return null;
            }
            if (raw instanceof EmlEvaluationResult eval) {
                return eval;
            }
            if (raw instanceof Map<?, ?> map) {
                return mapToEvaluationResult((Map<String, Object>) map, laneParameters);
            }
            throw new IllegalStateException("Unsupported return type from custom alarm assessment algorithm: " + raw.getClass());
        }

        private EmlEvaluationResult mapToEvaluationResult(Map<String, Object> m, EmlLaneParameters params) {
            String ver = safeString(m.getOrDefault("versionId", getVersionId()));
            String defaultModel = params.collimated() ? getCollimatedModelId() : getUncollimatedModelId();
            String model = safeString(m.getOrDefault("modelId", defaultModel));
            double threshold = toDouble(m.get("primaryThreshold"), 0.05);
            String portId = safeString(m.getOrDefault("portId", params.portId()));
            long laneId = toLong(m.get("laneId"), params.laneId());
            long dtMillis = toLong(m.get("dateTimeEpochMillis"), System.currentTimeMillis());
            long segId = toLong(m.get("segmentId"), 0L);
            String rpmRes = safeString(m.getOrDefault("rpmResult", "ALARM"));
            boolean rpmGamma = Boolean.TRUE.equals(m.get("rpmGammaAlert"));
            boolean rpmNeutron = Boolean.TRUE.equals(m.get("rpmNeutronAlert"));
            boolean rpmScanErr = Boolean.TRUE.equals(m.get("rpmScanError"));
            String decision = safeString(m.getOrDefault("result", m.getOrDefault("decision", "INVESTIGATE")));
            boolean fallback = Boolean.TRUE.equals(m.get("fallback"));
            String excMsg = safeString(m.get("exceptionMessage"));
            double relProb = toDouble(m.get("releaseProbability"), "RELEASE".equalsIgnoreCase(decision) ? 1.0 : 0.0);
            double invProb = toDouble(m.get("investigateProbability"), Math.max(0.0, 1.0 - relProb));
            boolean ernieGamma = Boolean.TRUE.equals(m.get("ernieGammaAlert"));
            boolean ernieNeutron = Boolean.TRUE.equals(m.get("ernieNeutronAlert"));
            int vehClass = (int) toLong(m.get("vehicleClass"), 0L);
            double vehLen = toDouble(m.get("vehicleLength"), 0.0);
            String yellowMsg = safeString(m.get("yellowLightMessage"));

            String overallSourceType = safeString(m.getOrDefault("overallSourceType",
                    "RELEASE".equalsIgnoreCase(decision) ? "NORM" : "Threat"));
            double probNonEmit = toDouble(m.get("probabilityNonEmitting"), 0.0);
            double probNorm = toDouble(m.get("probabilityNORM"), "RELEASE".equalsIgnoreCase(decision) ? relProb : 0.0);
            double probThreat = toDouble(m.get("probabilityThreat"), invProb);
            EmlSourceBreakdown overall = new EmlSourceBreakdown(
                    overallSourceType, model, 0.0, 0.0, 0.0, 0.0, probNonEmit, probNorm, probThreat);
            return new EmlEvaluationResult(
                    ver, model, threshold, portId, laneId, dtMillis, segId,
                    rpmRes, rpmGamma, rpmNeutron, rpmScanErr, decision, fallback, excMsg,
                    invProb, relProb, ernieGamma, ernieNeutron, vehClass, vehLen, yellowMsg,
                    List.of(overall), overall);
        }

        @Override
        public void close() {
            if (closeMethod != null) {
                try {
                    closeMethod.invoke(delegate);
                } catch (Exception ignored) {
                }
            }
        }
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
            if (customAlgorithm != null) {
                try {
                    customAlgorithm.close();
                } catch (Exception e) {
                    logger.debug("Error closing custom alarm assessment algorithm", e);
                }
            }
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
