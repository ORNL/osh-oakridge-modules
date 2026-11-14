/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.engine;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DbNetPostProcessorTest {

    @Test
    void extractsSortedUnclippedBoxesAndFiltersNoise() {
        int mapW = 160;
        int mapH = 96;
        float[][] prob = new float[mapH][mapW];

        // Top text bar
        fillRect(prob, 20, 18, 70, 10, 0.85f);
        // Bottom text bar
        fillRect(prob, 30, 54, 60, 10, 0.80f);
        // 1x1 noise pixel
        prob[5][5] = 0.95f;

        List<Rectangle> boxes = new DbNetPostProcessor().extractBoxes(prob, 4.0, 4.0, 640, 384);
        assertEquals(2, boxes.size());
        assertTrue(boxes.get(0).y < boxes.get(1).y);
        assertTrue(boxes.get(0).width > 200);
    }

    private static void fillRect(float[][] map, int x0, int y0, int w, int h, float val) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                map[y][x] = val;
            }
        }
    }
}
