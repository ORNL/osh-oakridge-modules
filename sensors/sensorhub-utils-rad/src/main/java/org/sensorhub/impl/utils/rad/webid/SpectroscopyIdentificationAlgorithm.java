/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package org.sensorhub.impl.utils.rad.webid;

import org.sensorhub.impl.utils.rad.model.WebIdAnalysis;

import java.nio.file.Path;

/**
 * Public Service Provider Interface (SPI) for pluggable secondary-inspection gamma spectroscopy
 * nuclide identification algorithms.
 *
 * <p>Custom implementations can be packaged as a {@code .jar}, {@code .zip}, {@code .tar.gz},
 * {@code .tgz}, or {@code .tar} archive and registered via:</p>
 * <ol>
 *   <li>{@code META-INF/services/org.sensorhub.impl.utils.rad.webid.SpectroscopyIdentificationAlgorithm}</li>
 *   <li>{@code META-INF/MANIFEST.MF} attribute {@code Oscar-Spectroscopy-Class}</li>
 *   <li>{@code META-INF/oscar-spectroscopy.properties} or {@code oscar-spectroscopy.properties}
 *       with {@code class=com.example.MyNuclideClassifier}</li>
 * </ol>
 */
@FunctionalInterface
public interface SpectroscopyIdentificationAlgorithm extends AutoCloseable {

    String MANIFEST_CLASS_ATTRIBUTE = "Oscar-Spectroscopy-Class";
    String DESCRIPTOR_FILENAME = "oscar-spectroscopy.properties";

    default void initialize(Path extractedPluginRoot) throws Exception {
    }

    default String getAlgorithmId() {
        return getClass().getSimpleName();
    }

    default String getVersionId() {
        return "1.0.0";
    }

    /**
     * Evaluates a spectroscopic foreground (and optional background) measurement and returns a
     * populated {@link WebIdAnalysis} record.
     */
    WebIdAnalysis analyze(SpectroscopyAnalysisInput input) throws Exception;

    @Override
    default void close() {
    }
}
