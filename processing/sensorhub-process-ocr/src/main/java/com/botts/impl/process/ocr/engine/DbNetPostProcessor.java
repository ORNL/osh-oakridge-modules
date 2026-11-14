/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.engine;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Converts a DBNet text-detection probability map into axis-aligned bounding boxes in original
 * frame coordinates: binarizes, extracts 4-connected components, filters tiny blobs, applies
 * DBNet area/perimeter unclipping, and merges adjacent boxes on the same horizontal text line.
 */
public class DbNetPostProcessor {

    static final float BINARIZE_THRESHOLD = 0.3f;
    static final int MIN_COMPONENT_AREA = 30;
    static final double UNCLIP_RATIO = 1.5;
    static final double SAME_LINE_MIN_VERTICAL_OVERLAP = 0.5;
    static final double SAME_LINE_MAX_GAP_HEIGHTS = 1.5;

    public List<Rectangle> extractBoxes(float[][] probMap,
                                        double scaleX,
                                        double scaleY,
                                        int imageWidth,
                                        int imageHeight) {
        if (probMap == null || probMap.length == 0 || probMap[0] == null || probMap[0].length == 0)
            return List.of();
        if (imageWidth <= 1 || imageHeight <= 1)
            return List.of();

        int h = probMap.length;
        int w = probMap[0].length;

        boolean[][] mask = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            float[] row = probMap[y];
            for (int x = 0; x < w; x++) {
                mask[y][x] = row[x] > BINARIZE_THRESHOLD;
            }
        }

        List<Rectangle> mapBoxes = connectedComponentBoxes(mask, h, w);
        List<Rectangle> boxes = new ArrayList<>(mapBoxes.size());

        for (Rectangle box : mapBoxes) {
            if ((long) box.width * box.height < MIN_COMPONENT_AREA)
                continue;

            int x = (int) Math.round(box.x * scaleX);
            int y = (int) Math.round(box.y * scaleY);
            int bw = (int) Math.round(box.width * scaleX);
            int bh = (int) Math.round(box.height * scaleY);

            int unclip = (int) Math.round(UNCLIP_RATIO * (bw * (double) bh) / Math.max(1.0, 2.0 * (bw + bh)));
            x -= unclip;
            y -= unclip;
            bw += 2 * unclip;
            bh += 2 * unclip;

            x = Math.max(0, x);
            y = Math.max(0, y);
            bw = Math.min(bw, imageWidth - x);
            bh = Math.min(bh, imageHeight - y);
            if (bw <= 1 || bh <= 1)
                continue;

            boxes.add(new Rectangle(x, y, bw, bh));
        }

        return mergeSameLine(boxes);
    }

    private List<Rectangle> connectedComponentBoxes(boolean[][] mask, int h, int w) {
        List<Rectangle> boxes = new ArrayList<>();
        int[] queue = new int[h * w];

        for (int sy = 0; sy < h; sy++) {
            for (int sx = 0; sx < w; sx++) {
                if (!mask[sy][sx])
                    continue;

                int head = 0;
                int tail = 0;
                queue[tail++] = sy * w + sx;
                mask[sy][sx] = false;
                int minX = sx;
                int maxX = sx;
                int minY = sy;
                int maxY = sy;

                while (head < tail) {
                    int cell = queue[head++];
                    int cy = cell / w;
                    int cx = cell % w;
                    minX = Math.min(minX, cx);
                    maxX = Math.max(maxX, cx);
                    minY = Math.min(minY, cy);
                    maxY = Math.max(maxY, cy);

                    if (cy > 0 && mask[cy - 1][cx]) { mask[cy - 1][cx] = false; queue[tail++] = cell - w; }
                    if (cy < h - 1 && mask[cy + 1][cx]) { mask[cy + 1][cx] = false; queue[tail++] = cell + w; }
                    if (cx > 0 && mask[cy][cx - 1]) { mask[cy][cx - 1] = false; queue[tail++] = cell - 1; }
                    if (cx < w - 1 && mask[cy][cx + 1]) { mask[cy][cx + 1] = false; queue[tail++] = cell + 1; }
                }

                boxes.add(new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1));
            }
        }
        return boxes;
    }

    public List<Rectangle> mergeSameLine(List<Rectangle> boxes) {
        List<Rectangle> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingInt((Rectangle r) -> r.y).thenComparingInt(r -> r.x));

        List<Rectangle> merged = new ArrayList<>();
        for (Rectangle box : sorted) {
            Rectangle target = null;
            for (Rectangle existing : merged) {
                if (isSameLine(existing, box)) {
                    target = existing;
                    break;
                }
            }
            if (target != null)
                target.setBounds(target.union(box));
            else
                merged.add(new Rectangle(box));
        }
        return merged;
    }

    private static boolean isSameLine(Rectangle a, Rectangle b) {
        int overlapTop = Math.max(a.y, b.y);
        int overlapBottom = Math.min(a.y + a.height, b.y + b.height);
        int overlap = overlapBottom - overlapTop;
        int minHeight = Math.min(a.height, b.height);
        if (minHeight <= 0 || overlap < minHeight * SAME_LINE_MIN_VERTICAL_OVERLAP)
            return false;

        int gap = Math.max(a.x, b.x) - Math.min(a.x + a.width, b.x + b.width);
        return gap <= minHeight * SAME_LINE_MAX_GAP_HEIGHTS;
    }
}
