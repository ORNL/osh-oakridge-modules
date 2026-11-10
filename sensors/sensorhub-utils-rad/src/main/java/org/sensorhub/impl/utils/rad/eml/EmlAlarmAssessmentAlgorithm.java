package org.sensorhub.impl.utils.rad.eml;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Public Service Provider Interface (SPI) for pluggable alarm assessment algorithms
 * uploaded through the OSCAR Service EML plugin capability
 * (<b>Services &rarr; OSCAR Service &rarr; EML Plugin Path</b>).
 *
 * <p>Developers can package a custom alarm assessment algorithm as a {@code .jar},
 * {@code .zip}, {@code .tar.gz}, {@code .tgz}, or {@code .tar} archive and register
 * their implementation using any of the following discovery mechanisms:
 * <ol>
 *   <li>Standard Java {@link java.util.ServiceLoader} descriptor at
 *       {@code META-INF/services/org.sensorhub.impl.utils.rad.eml.EmlAlarmAssessmentAlgorithm}
 *       containing the fully qualified implementation class name.</li>
 *   <li>JAR manifest attribute {@code Oscar-Alarm-Assessment-Class: com.example.MyAssessor}
 *       in {@code META-INF/MANIFEST.MF}.</li>
 *   <li>Properties file {@code META-INF/oscar-alarm-assessment.properties} or
 *       {@code config/oscar-alarm-assessment.properties} with property
 *       {@code class=com.example.MyAssessor}.</li>
 * </ol>
 *
 * <p>Thread safety: a single algorithm instance is shared across lanes and may be
 * invoked concurrently by multiple lane threads; implementations of
 * {@link #evaluate(EmlLaneParameters, List)} must be thread-safe.
 */
public interface EmlAlarmAssessmentAlgorithm extends AutoCloseable {

    /**
     * Manifest attribute name supported in {@code META-INF/MANIFEST.MF} for
     * registering an alarm assessment implementation class.
     */
    String MANIFEST_CLASS_ATTRIBUTE = "Oscar-Alarm-Assessment-Class";

    /**
     * Properties file path (under {@code META-INF/} or {@code config/}) supported
     * for registering an alarm assessment implementation class via {@code class=...}.
     */
    String DESCRIPTOR_FILENAME = "oscar-alarm-assessment.properties";

    /**
     * Called once when the plugin archive is extracted and activated by
     * {@link EmlRuntimeRegistry}. Implementations may load model weights,
     * calibration tables, or configuration files from {@code extractedPluginRoot}.
     *
     * @param extractedPluginRoot private root directory of the unpacked plugin archive
     * @throws Exception if initialization or model validation fails (causes upload to fail closed)
     */
    default void initialize(Path extractedPluginRoot) throws Exception {
    }

    /**
     * Returns the algorithm version identifier reported in {@code emlContextual},
     * {@code emlAnalysis}, and automated {@code Code 9999} adjudication metadata.
     */
    default String getVersionId() {
        return "1.0.0";
    }

    /**
     * Returns the model identifier reported for collimated lanes ({@code isCollimated = true}).
     */
    default String getCollimatedModelId() {
        return getClass().getSimpleName();
    }

    /**
     * Returns the model identifier reported for uncollimated lanes ({@code isCollimated = false}).
     */
    default String getUncollimatedModelId() {
        return getCollimatedModelId();
    }

    /**
     * Evaluates a completed occupancy and returns the alarm assessment result.
     *
     * <p>The {@code dailyFileLines} list contains the ordered Rapiscan daily-file messages
     * captured for the occupancy:
     * <ul>
     *   <li>Most recent neutron background line ({@code NS, HH:MM:SS,N1,N2,N3,N4,...}), if present</li>
     *   <li>Most recent gamma background line ({@code GS, ...} / {@code GB, ...}), if present</li>
     *   <li>All occupancy scan lines from {@code GS} (Gamma Start) through {@code GX} (Gamma Exit),
     *       including {@code GB}, {@code GA}, {@code NB}, {@code NA}, {@code GH}, {@code GL},
     *       {@code NH}, {@code NL}, and terminal {@code GX, HH:MM:SS,<occupancyCount>}</li>
     * </ul>
     *
     * <p>Returning an {@link EmlEvaluationResult} with {@code result = "RELEASE"},
     * {@code fallback = false}, and empty {@code exceptionMessage} causes OSCAR to suppress
     * the alarm from the live Dashboard queue and automatically adjudicate it as
     * <b>Code 9999: EML Non-Threat</b>. Returning {@code result = "INVESTIGATE"},
     * {@code fallback = true}, or throwing an exception fails closed so the alarm remains
     * active for human operator adjudication on the Dashboard.
     *
     * @param laneParameters per-lane configuration and setup parameters ({@code SG1} intervals/hold-in, collimation, lane width)
     * @param dailyFileLines ordered daily-file lines for the occupancy
     * @return evaluation result, or {@code null} to skip EML publication for this occupancy
     * @throws Exception if evaluation fails (OSCAR logs the failure and fails closed to human review)
     */
    EmlEvaluationResult evaluate(EmlLaneParameters laneParameters, List<String> dailyFileLines) throws Exception;

    /**
     * Stream-based convenience overload invoked by {@link EmlLoadedRuntime}.
     */
    default EmlEvaluationResult evaluate(EmlLaneParameters laneParameters, Stream<String> occupancyLines) throws Exception {
        List<String> lines = occupancyLines != null ? occupancyLines.toList() : List.of();
        if (lines.isEmpty()) {
            return null;
        }
        return evaluate(laneParameters, lines);
    }

    /**
     * Called when the plugin is unloaded or superseded by a newer plugin upload.
     */
    @Override
    default void close() {
    }
}
