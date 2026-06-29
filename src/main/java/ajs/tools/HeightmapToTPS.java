package ajs.tools;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Utilities for extracting minimal control point sets from heightmaps
 * for TPS representation.
 */
public class HeightmapToTPS {

    /**
     * Extract minimal control points from a 32-bit heightmap image.
     * Uses iterative refinement: starts with coarse grid, fits TPS, identifies
     * high-error regions, adaptively adds points.
     *
     * @param heightmap 32-bit image where pixel values represent z-coordinates
     * @param initialGridSpacing initial grid sampling distance (pixels)
     * @param maxIterations maximum refinement iterations
     * @param errorThreshold max RMS error allowed per region; stops when met
     * @param maxPoints hard limit on number of control points
     * @return array of control points [x, y, z] in pixel coordinates
     */
    public static double[][] extractControlPoints(ImagePlus heightmap,
                                                   int initialGridSpacing,
                                                   int maxIterations,
                                                   double errorThreshold,
                                                   int maxPoints) {
        if (heightmap == null) throw new IllegalArgumentException("Heightmap cannot be null");
        if (heightmap.getBitDepth() != 32) throw new IllegalArgumentException("Image must be 32-bit float");

        FloatProcessor fp = (FloatProcessor) heightmap.getProcessor();
        int w = fp.getWidth();
        int h = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();

        List<double[]> controlPoints = new ArrayList<>();

        // Initialize with coarse grid
        int spacing = initialGridSpacing;
        for (int y = spacing / 2; y < h; y += spacing) {
            for (int x = spacing / 2; x < w; x += spacing) {
                int idx = y * w + x;
                controlPoints.add(new double[]{x, y, pixels[idx]});
            }
        }

        // Iterative refinement
        for (int iter = 0; iter < maxIterations && controlPoints.size() < maxPoints; iter++) {
            // Fit TPS to current points
            double[][] srcPts = new double[controlPoints.size()][3];
            double[][] tgtPts = new double[controlPoints.size()][3];
            for (int i = 0; i < controlPoints.size(); i++) {
                double[] pt = controlPoints.get(i);
                srcPts[i] = pt.clone();
                tgtPts[i] = pt.clone();
            }

            ThinPlateSpline3D tps = new ThinPlateSpline3D();
            tps.fit(srcPts, tgtPts, 1e-3);

            // Compute residual error across the heightmap
            double maxError = 0;
            int maxErrorX = -1, maxErrorY = -1;
            double totalError = 0;
            int sampleCount = 0;

            int sampleSpacing = Math.max(1, spacing / 2);
            for (int y = 0; y < h; y += sampleSpacing) {
                for (int x = 0; x < w; x += sampleSpacing) {
                    int idx = y * w + x;
                    double actualZ = pixels[idx];
                    double[] pred = tps.transform(new double[]{x, y, actualZ});
                    double error = Math.abs(pred[2] - actualZ);
                    totalError += error;
                    sampleCount++;

                    if (error > maxError) {
                        maxError = error;
                        maxErrorX = x;
                        maxErrorY = y;
                    }
                }
            }

            double rmsError = Math.sqrt(totalError / Math.max(1, sampleCount));
            System.out.println(String.format("Iteration %d: %d points, RMS error=%.6f, max error=%.6f at (%d,%d)",
                    iter, controlPoints.size(), rmsError, maxError, maxErrorX, maxErrorY));

            // Stop if error threshold met
            if (rmsError <= errorThreshold) {
                System.out.println("Converged at iteration " + iter);
                break;
            }

            // Add point at location of highest error
            if (maxErrorX >= 0 && maxErrorY >= 0) {
                int idx = maxErrorY * w + maxErrorX;
                double[] newPt = new double[]{maxErrorX, maxErrorY, pixels[idx]};

                // Check if point already exists to avoid duplicates
                boolean exists = false;
                for (double[] cp : controlPoints) {
                    if (Math.abs(cp[0] - newPt[0]) < 0.5 && Math.abs(cp[1] - newPt[1]) < 0.5) {
                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    controlPoints.add(newPt);
                }
            }
        }

        double[][] result = new double[controlPoints.size()][3];
        for (int i = 0; i < controlPoints.size(); i++) {
            result[i] = controlPoints.get(i);
        }
        return result;
    }

    /**
     * Advanced version using multi-level refinement with error bins.
     * Divides the image into regions and refines those with highest error.
     *
     * @param heightmap 32-bit height image
     * @param initialGridSpacing coarse grid spacing
     * @param maxPoints hard limit on control points
     * @param maxIterations max refinement passes
     * @return array of [x, y, z] control points
     */
    public static double[][] extractControlPointsAdaptive(ImagePlus heightmap,
                                                           int initialGridSpacing,
                                                           int maxPoints,
                                                           int maxIterations) {
        if (heightmap == null) throw new IllegalArgumentException("Heightmap cannot be null");
        if (heightmap.getBitDepth() != 32) throw new IllegalArgumentException("Image must be 32-bit float");

        FloatProcessor fp = (FloatProcessor) heightmap.getProcessor();
        int w = fp.getWidth();
        int h = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();

        List<double[]> controlPoints = new ArrayList<>();

        // Initialize coarse grid
        for (int y = initialGridSpacing / 2; y < h; y += initialGridSpacing) {
            for (int x = initialGridSpacing / 2; x < w; x += initialGridSpacing) {
                int idx = y * w + x;
                controlPoints.add(new double[]{x, y, pixels[idx]});
            }
        }

        // Adaptive refinement iterations
        for (int iter = 0; iter < maxIterations && controlPoints.size() < maxPoints; iter++) {
            // Fit TPS
            double[][] srcPts = new double[controlPoints.size()][3];
            double[][] tgtPts = new double[controlPoints.size()][3];
            for (int i = 0; i < controlPoints.size(); i++) {
                double[] pt = controlPoints.get(i);
                srcPts[i] = pt.clone();
                tgtPts[i] = pt.clone();
            }

            ThinPlateSpline3D tps = new ThinPlateSpline3D();
            tps.fit(srcPts, tgtPts, 1e-3);

            // Compute error field
            int gridSize = Math.max(2, initialGridSpacing / (iter + 2));
            PriorityQueue<ErrorRegion> errorQueue = new PriorityQueue<>((a, b) -> Double.compare(b.error, a.error));

            for (int y = 0; y < h; y += gridSize) {
                for (int x = 0; x < w; x += gridSize) {
                    int x2 = Math.min(x + gridSize, w - 1);
                    int y2 = Math.min(y + gridSize, h - 1);

                    // Compute average error in this region
                    double regionError = 0;
                    int count = 0;
                    for (int yy = y; yy <= y2; yy++) {
                        for (int xx = x; xx <= x2; xx++) {
                            int idx = yy * w + xx;
                            double actualZ = pixels[idx];
                            double[] pred = tps.transform(new double[]{xx, yy, actualZ});
                            regionError += Math.abs(pred[2] - actualZ);
                            count++;
                        }
                    }
                    regionError /= Math.max(1, count);

                    if (regionError > 0) {
                        errorQueue.offer(new ErrorRegion(x, y, x2, y2, regionError));
                    }
                }
            }

            // Add points to regions with highest error
            int pointsAddedThisIter = 0;
            int maxAddPerIter = Math.max(1, (maxPoints - controlPoints.size()) / 4);
            while (!errorQueue.isEmpty() && pointsAddedThisIter < maxAddPerIter && controlPoints.size() < maxPoints) {
                ErrorRegion region = errorQueue.poll();

                // Add point at region center or center-of-mass of highest errors
                int cx = (region.x1 + region.x2) / 2;
                int cy = (region.y1 + region.y2) / 2;

                // Find actual max error location in this region
                double maxErr = 0;
                int bestX = cx, bestY = cy;
                for (int yy = region.y1; yy <= region.y2; yy++) {
                    for (int xx = region.x1; xx <= region.x2; xx++) {
                        int idx = yy * w + xx;
                        double actualZ = pixels[idx];
                        double[] pred = tps.transform(new double[]{xx, yy, actualZ});
                        double err = Math.abs(pred[2] - actualZ);
                        if (err > maxErr) {
                            maxErr = err;
                            bestX = xx;
                            bestY = yy;
                        }
                    }
                }

                // Check for duplicate
                boolean exists = false;
                for (double[] cp : controlPoints) {
                    if (Math.abs(cp[0] - bestX) < 0.5 && Math.abs(cp[1] - bestY) < 0.5) {
                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    int idx = bestY * w + bestX;
                    controlPoints.add(new double[]{bestX, bestY, pixels[idx]});
                    pointsAddedThisIter++;
                }
            }

            System.out.println(String.format("Iteration %d: %d points, added %d",
                    iter, controlPoints.size(), pointsAddedThisIter));

            if (pointsAddedThisIter == 0) {
                System.out.println("No new points added; stopping.");
                break;
            }
        }

        double[][] result = new double[controlPoints.size()][3];
        for (int i = 0; i < controlPoints.size(); i++) {
            result[i] = controlPoints.get(i);
        }
        return result;
    }

    /**
     * Simple grid-based sampling without iterative refinement.
     * Useful for quick baseline or when simplicity is preferred.
     *
     * @param heightmap 32-bit image
     * @param gridSpacing approximately how far apart to space control points (pixels)
     * @return array of [x, y, z] control points
     */
    public static double[][] extractControlPointsSimple(ImagePlus heightmap, int gridSpacing) {
        if (heightmap == null) throw new IllegalArgumentException("Heightmap cannot be null");
        if (heightmap.getBitDepth() != 32) throw new IllegalArgumentException("Image must be 32-bit float");

        FloatProcessor fp = (FloatProcessor) heightmap.getProcessor();
        int w = fp.getWidth();
        int h = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();

        List<double[]> points = new ArrayList<>();
        for (int y = gridSpacing / 2; y < h; y += gridSpacing) {
            for (int x = gridSpacing / 2; x < w; x += gridSpacing) {
                int idx = y * w + x;
                points.add(new double[]{x, y, pixels[idx]});
            }
        }

        double[][] result = new double[points.size()][3];
        for (int i = 0; i < points.size(); i++) {
            result[i] = points.get(i);
        }
        return result;
    }

    /**
     * Internal class for tracking error regions.
     */
    private static class ErrorRegion {
        int x1, y1, x2, y2;
        double error;

        ErrorRegion(int x1, int y1, int x2, int y2, double error) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.error = error;
        }
    }
}
