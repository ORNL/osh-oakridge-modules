/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.engine;

import java.util.List;

/**
 * Greedy Connectionist Temporal Classification (CTC) decoder for PP-OCR recognition outputs.
 *
 * <p>Class {@code 0} is the CTC blank token, classes {@code 1..charset.size()} map to
 * {@code dict.txt} entries, and class {@code charset.size() + 1} maps to ASCII space.</p>
 */
public class CtcGreedyDecoder {

    public record Decoded(String text, float confidence) {}

    private final List<String> charset;

    public CtcGreedyDecoder(List<String> charset) {
        this.charset = List.copyOf(charset);
    }

    public Decoded decode(float[][] logits) {
        if (logits == null || logits.length == 0)
            return new Decoded("", 0f);

        boolean preNormalized = looksLikeProbabilities(logits);
        StringBuilder text = new StringBuilder();
        double confidenceSum = 0.0;
        int emitted = 0;
        int prevClass = 0;

        for (float[] step : logits) {
            if (step == null || step.length == 0)
                continue;
            int best = 0;
            float bestVal = step[0];
            for (int c = 1; c < step.length; c++) {
                if (step[c] > bestVal) {
                    bestVal = step[c];
                    best = c;
                }
            }
            if (best != 0 && best != prevClass) {
                text.append(classToChar(best));
                confidenceSum += preNormalized ? bestVal : softmaxProbability(step, best);
                emitted++;
            }
            prevClass = best;
        }

        float confidence = emitted > 0 ? (float) (confidenceSum / emitted) : 0f;
        return new Decoded(text.toString(), confidence);
    }

    private static boolean looksLikeProbabilities(float[][] logits) {
        if (logits.length == 0 || logits[0] == null || logits[0].length == 0)
            return false;
        double sum = 0.0;
        for (float v : logits[0]) {
            if (v < 0f || v > 1.0001f)
                return false;
            sum += v;
        }
        return Math.abs(sum - 1.0) < 0.01;
    }

    private String classToChar(int classIndex) {
        int dictIndex = classIndex - 1;
        if (dictIndex >= 0 && dictIndex < charset.size())
            return charset.get(dictIndex);
        return " ";
    }

    private static double softmaxProbability(float[] step, int index) {
        double max = step[index];
        double sum = 0.0;
        for (float v : step)
            sum += Math.exp(v - max);
        return sum > 0.0 ? (1.0 / sum) : 0.0;
    }
}
