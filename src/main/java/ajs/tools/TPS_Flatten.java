package ajs.tools;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum;
import net.haesleinhuepf.clij2.CLIJ2;

/**
 * Example plugin: load CSV landmarks (x,y,z), fit TPS and flatten stack to XY-plane.
 * CSV rows with three columns: x,y,z (pixel coordinates, 0-based z slice indices).
 * Landmarks are projected to z = average_z of all landmarks (flat XY plane).
 */
public class TPS_Flatten implements PlugIn {

    // Caps voxels per single GPU kernel dispatch to keep it short enough to avoid
    // Windows GPU driver TDR watchdog resets (which invalidate the OpenCL command queue).
    private static final int MAX_VOXELS_PER_LAUNCH = 16_000_000;

    @Override
    public void run(String arg) {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) { IJ.noImage(); return; }

        String csvPath = IJ.getFilePath("Choose landmarks CSV (x,y,z)");
        if (csvPath == null) return;

        List<double[]> srcList = new ArrayList<>();
        double avgZ = 0;
        int xi = 0, yi = 1, zi = 2, ti=-1; // column indices for x,y,z in CSV
        boolean foundHeader = false;
        try (BufferedReader br = new BufferedReader(new FileReader(new File(csvPath)))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] toks = line.split("[,\t ]+");
                if (toks.length < 3) continue;
                if(foundHeader && ti>=0 && toks.length>ti && toks[ti].matches("\\d+")) {
                    int frame = Integer.parseInt(toks[ti]);
                    if(frame>1) {
                        continue; // skip non-first frame landmarks
                    }
                }
                if(!foundHeader){
                    for(int i=0;i<toks.length;i++){
                        if(toks[i].equalsIgnoreCase("x")) {xi=i; foundHeader=true;}
                        else if(toks[i].equalsIgnoreCase("y")) yi=i;
                        else if(toks[i].equalsIgnoreCase("z")) zi=i;
                        else if(toks[i].equalsIgnoreCase("slice")) zi=i;
                        else if(toks[i].equalsIgnoreCase("frame")) ti=i;
                    }
                    if(foundHeader) continue; // skip header line
                }
                double x = Double.parseDouble(toks[xi]);
                double y = Double.parseDouble(toks[yi]);
                double z = Double.parseDouble(toks[zi]);
                srcList.add(new double[]{x, y, z});
                //srcList.add(new double[]{x, y, z+10});
                avgZ += z;
            }
        } catch (Exception e) {
            IJ.error("Failed to read CSV: " + e.getMessage());
            return;
        }
        if (srcList.size() < 4) { IJ.error("Need at least 4 landmarks for TPS"); return; }
        
        // Convert to arrays
        int N = srcList.size();
        avgZ /= N/2;
        double[][] src = new double[N][3];
        for (int i = 0; i < N; i++) src[i] = srcList.get(i);

        flattenStack(imp, src, true);
        // Build target points: project each landmark to XY-plane at z = avgZ
        double[][] tgt = new double[N][3];
        for (int i = 0; i < N; i++) {
            float add=(i%2==0)?0:10;
            tgt[i][0] = src[i][0];
            tgt[i][1] = src[i][1];
            tgt[i][2] = avgZ + add;
        }

        IJ.log("Loaded " + N + " landmarks. Avg Z: " + String.format("%.2f", avgZ));
        IJ.log("Source z range: [" + getMinZ(src) + ", " + getMaxZ(src) + "]");

        GenericDialog gd = new GenericDialog("TPS Flatten Options");
        gd.addNumericField("Regularization lambda", 1e-3, 6);
        gd.addCheckbox("Use GPU acceleration (NVIDIA CUDA)?", true);
        gd.showDialog();
        if (gd.wasCanceled()) return;
        double lambda = gd.getNextNumber();
        boolean useGPU = gd.getNextBoolean();

        // Fit TPS: target -> source (inverted for resampling)
        ThinPlateSpline3D tps = new ThinPlateSpline3D();
        tps.fit(tgt, src, lambda);

        // Resample
        if (useGPU) {
            flattenStackGPU(imp, tps);
        } else {
            flattenStack(imp, tps);
        }
    }

    public void flattenStack(ImagePlus imp, double[][] srcPts, boolean useGPU ) {
        ThinPlateSpline3D tps = fitFlattenTPS(srcPts, 1e-3);
        if (useGPU) {
            flattenStackGPU(imp, tps);
        } else {
            flattenStack(imp, tps);
        }
    }

    private ThinPlateSpline3D fitFlattenTPS(double[][] src, double lambda) {
        int N = src.length;
        double avgZ = 0;
        for (int i = 0; i < N; i++) {
            avgZ += src[i][2];
        }
        avgZ /= N;
        double[][] srcPts = new double[N*2][3];
        for(int i=0;i<N;i++){
            srcPts[i][0]=src[i][0];
            srcPts[i][1]=src[i][1];
            srcPts[i][2]=src[i][2];
            srcPts[i+N][0]=src[i][0];
            srcPts[i+N][1]=src[i][1];
            srcPts[i+N][2]=src[i][2]+10;
        }
        // Build target points: project each landmark to XY-plane at z = avgZ
        // Duplicate landmarks with z+10 to help TPS shift all z points together
        double[][] tgt = new double[N*2][3];
        for (int i = 0; i < N; i++) {
            tgt[i][0] = srcPts[i][0];
            tgt[i][1] = srcPts[i][1];
            tgt[i][2] = avgZ;
            tgt[i+N][0] = srcPts[i][0];
            tgt[i+N][1] = srcPts[i][1];
            tgt[i+N][2] = avgZ+10;
        }
        ThinPlateSpline3D tps=null;
        try{
            tps = new ThinPlateSpline3D(srcPts, tgt, lambda);
        } catch (Exception e) {
            IJ.error("Failed to fit TPS\n"+e.getLocalizedMessage());
            return null;
        }
        IJ.log("Loaded " + N + " landmarks. Avg Z: " + String.format("%.2f", avgZ));
        return tps;
    }

    private void flattenStackGPU(ImagePlus oimp, ThinPlateSpline3D tps) {
        long startTime = System.currentTimeMillis();

        try {
            CLIJ2 clij2 = CLIJ2.getInstance();

            int w = oimp.getWidth(), h = oimp.getHeight();
            int sls = oimp.getNSlices(), frms = oimp.getNFrames(), chs = oimp.getNChannels();

            IJ.log("GPU setup: using device: " + clij2.getGPUName());

            // Prepare TPS parameters for GPU
            float[] tpsWeights = tps.getWeights();
            float[] affineCoeff = tps.getAffineCoefficients();
            float[] landmarkSrc = tps.getSourceLandmarks();
            int N = landmarkSrc.length / 3;

            net.haesleinhuepf.clij.clearcl.ClearCLContext context = clij2.getCLIJ().getClearCLContext();

            // Push buffers to GPU
            net.haesleinhuepf.clij.clearcl.ClearCLBuffer weightsGPU = context.createBuffer(NativeTypeEnum.Float,
                (long)tpsWeights.length);
            weightsGPU.readFrom(java.nio.FloatBuffer.wrap(tpsWeights), true);

            net.haesleinhuepf.clij.clearcl.ClearCLBuffer affineGPU = context.createBuffer(NativeTypeEnum.Float,
                (long)affineCoeff.length);
            affineGPU.readFrom(java.nio.FloatBuffer.wrap(affineCoeff), true);

            net.haesleinhuepf.clij.clearcl.ClearCLBuffer landmarkGPU = context.createBuffer(NativeTypeEnum.Float,
                (long)landmarkSrc.length);
            landmarkGPU.readFrom(java.nio.FloatBuffer.wrap(landmarkSrc), true);

            // Prepare output hyperstack
            int newsls=sls+(int)ThinPlateSpline3D.computeMaxZDisplacement(tps, w, h, sls, w/6,1);

            // Determine whether the volume needs to be split into XY tiles to fit on the GPU.
            // CLIJ's ClearCL API (this version) has no "free memory" query, only total device
            // memory and the max single-allocation size, so budget conservatively against those.
            int bytesPerPixel = getBytesPerPixel(oimp.getBitDepth());
            net.haesleinhuepf.clij.clearcl.ClearCLDevice device = context.getDevice();
            long totalMem = device.getGlobalMemorySizeInBytes();
            long maxAlloc = device.getMaxMemoryAllocationSizeInBytes();
            long memBudget = (long) (totalMem * 0.5); // leave headroom for driver/OS/other buffers
            long allocBudget = (long) (maxAlloc * 0.9);

            long pixelsLimitFromTotal = Math.max(1, memBudget / (((long) sls + newsls) * bytesPerPixel));
            long pixelsLimitFromAlloc = Math.max(1, allocBudget / ((long) Math.max(sls, newsls) * bytesPerPixel));
            long maxPixelsPerTile = Math.min(pixelsLimitFromTotal, pixelsLimitFromAlloc);

            int tilesX = 1, tilesY = 1;
            if (maxPixelsPerTile < (long) w * h) {
                int tileSide = Math.max(1, (int) Math.sqrt((double) maxPixelsPerTile));
                tilesX = Math.max(1, (int) Math.ceil((double) w / tileSide));
                tilesY = Math.max(1, (int) Math.ceil((double) h / tileSide));
                IJ.log(String.format("GPU memory: %.2f GB total (max alloc %.2f GB); volume needs splitting -> %d x %d XY tiles",
                    totalMem / 1e9, maxAlloc / 1e9, tilesX, tilesY));
            }
            int tileW = (int) Math.ceil((double) w / tilesX);
            int tileH = (int) Math.ceil((double) h / tilesY);

            // Overlap margin so TPS-displaced samples near tile edges still fall inside the cropped input
            int margin = 0;
            if (tilesX * tilesY > 1) {
                margin = (int) Math.ceil(ThinPlateSpline3D.computeMaxXYDisplacement(tps, w, h, sls, Math.max(1, w / 12))) + 2;
                IJ.log("XY tile overlap margin: " + margin + " px");
            }

            // Load kernel once
            String kernelCode = loadKernelSource();
            if (kernelCode == null) {
                IJ.error("Failed to load tps_resample.cl kernel");
                return;
            }
            net.haesleinhuepf.clij.clearcl.ClearCLProgram program = context.createProgram(kernelCode);
            program.build();
            net.haesleinhuepf.clij.clearcl.ClearCLKernel kernel = program.createKernel("tpsResample");

            ImageStack finalstack = new ImageStack(w, h, chs * newsls * frms);
            for (int idx = 1; idx <= chs * newsls * frms; idx++) {
                finalstack.setProcessor(createBlankProcessor(oimp.getBitDepth(), w, h), idx);
            }

            int totalUnits = tilesX * tilesY * frms * chs;
            int unitsDone = 0;

            for (int ty = 0; ty < tilesY; ty++) {
                int outY0 = ty * tileH;
                int outTileH = Math.min(tileH, h - outY0);
                for (int tx = 0; tx < tilesX; tx++) {
                    int outX0 = tx * tileW;
                    int outTileW = Math.min(tileW, w - outX0);

                    int inX0 = Math.max(0, outX0 - margin);
                    int inY0 = Math.max(0, outY0 - margin);
                    int inTileW = Math.min(w, outX0 + outTileW + margin) - inX0;
                    int inTileH = Math.min(h, outY0 + outTileH + margin) - inY0;
                    Rectangle cropRect = new Rectangle(inX0, inY0, inTileW, inTileH);

                    for (int fr = 1; fr <= frms; fr++) {
                        for (int ch = 1; ch <= chs; ch++) {
                            IJ.showProgress((double) unitsDone / totalUnits);
                            unitsDone++;

                            // Build single-channel, tile-cropped ImagePlus for this channel/frame
                            ImagePlus imp = IJ.createImage("gpushifter", inTileW, inTileH, sls, oimp.getBitDepth());
                            for (int z = 1; z <= sls; z++) {
                                ImageProcessor srcIp = oimp.getStack().getProcessor(oimp.getStackIndex(ch, z, fr));
                                srcIp.setRoi(cropRect);
                                imp.getStack().setProcessor(srcIp.crop(), z);
                            }

                            // Push input and create output sized to this tile
                            net.haesleinhuepf.clij.clearcl.ClearCLImage inputGPU = clij2.convert(imp, net.haesleinhuepf.clij.clearcl.ClearCLImage.class);
                            net.haesleinhuepf.clij.clearcl.ClearCLImage outputGPU = clij2.create(new long[]{outTileW, outTileH, newsls}, inputGPU.getChannelDataType());

                            // Set kernel args that don't change across Z-chunks
                            kernel.setArgument("inputImage", inputGPU);
                            kernel.setArgument("outputImage", outputGPU);
                            kernel.setArgument("tpsWeights", weightsGPU);
                            kernel.setArgument("affineCoeff", affineGPU);
                            kernel.setArgument("landmarkSrc", landmarkGPU);
                            kernel.setArgument("N", N);
                            kernel.setArgument("outTileWidth", outTileW);
                            kernel.setArgument("outTileHeight", outTileH);
                            kernel.setArgument("outOffsetX", outX0);
                            kernel.setArgument("outOffsetY", outY0);
                            kernel.setArgument("outDepth", newsls);
                            kernel.setArgument("fullWidth", w);
                            kernel.setArgument("fullHeight", h);
                            kernel.setArgument("inTileWidth", inTileW);
                            kernel.setArgument("inTileHeight", inTileH);
                            kernel.setArgument("inOffsetX", inX0);
                            kernel.setArgument("inOffsetY", inY0);
                            kernel.setArgument("imgDepth", sls);

                            // Chunk the Z range across multiple kernel launches so a single dispatch
                            // stays short enough to avoid GPU driver watchdog (TDR) timeouts/resets
                            // on large volumes, which would otherwise leave the command queue invalid.
                            int zChunk = Math.max(1, MAX_VOXELS_PER_LAUNCH / Math.max(1, outTileW * outTileH));
                            for (int z0 = 0; z0 < newsls; z0 += zChunk) {
                                int chunkLen = Math.min(zChunk, newsls - z0);
                                kernel.setArgument("outOffsetZ", z0);
                                kernel.setGlobalSizes(new long[]{outTileW, outTileH, chunkLen});
                                kernel.run(true);
                            }

                            // Pull result back and paste into the full-size output stack
                            ImagePlus result = clij2.convert(outputGPU, ImagePlus.class);
                            for (int z = 1; z <= newsls; z++) {
                                int idx = ch + (z - 1) * chs + (fr - 1) * chs * newsls;
                                finalstack.getProcessor(idx).insert(result.getStack().getProcessor(z), outX0, outY0);
                            }

                            // Cleanup per-tile/channel
                            inputGPU.close();
                            outputGPU.close();
                            imp.close();
                            result.close();
                        }
                    }
                }
            }

            // Final cleanup
            IJ.showProgress(1.0);
            weightsGPU.close();
            affineGPU.close();
            landmarkGPU.close();
            kernel.close();
            program.close();

            ImagePlus finalimp=new ImagePlus(oimp.getTitle() + "-TPSFlat-GPU", finalstack);
            if(oimp.isHyperStack()) {
                finalimp.setDimensions(chs, newsls, frms);
                if(oimp.isComposite()){
                    finalimp=new CompositeImage(finalimp);
                    ((CompositeImage)finalimp).setMode(((CompositeImage)oimp).getMode());
                    for(int c=1;c<=chs;c++){
                        ((CompositeImage)finalimp).setChannelLut(((CompositeImage)oimp).getChannelLut(c), c);
                    }
                }
            }else {
                finalimp.setDisplayRange(oimp.getDisplayRangeMin(), oimp.getDisplayRangeMax());
            }
            finalimp.setCalibration(oimp.getCalibration());
            finalimp.show();
            Slicelabel_Transfer.transferSliceLabels(oimp, finalimp);
            finalimp.setProperty("Info",oimp.getInfoProperty());

            IJ.log("GPU processing time: " + String.format("%.2f", (System.currentTimeMillis()-startTime) / 1000.0) + " seconds");
            IJ.showStatus("GPU flattening complete");

        } catch (Exception e) {
            IJ.error("GPU Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int getBytesPerPixel(int bitDepth) {
        switch (bitDepth) {
            case 8: return 1;
            case 16: return 2;
            case 24: return 4; // RGB color
            default: return 4; // 32-bit float
        }
    }

    private ImageProcessor createBlankProcessor(int bitDepth, int w, int h) {
        switch (bitDepth) {
            case 8: return new ByteProcessor(w, h);
            case 16: return new ShortProcessor(w, h);
            case 24: return new ColorProcessor(w, h);
            default: return new FloatProcessor(w, h);
        }
    }

    private void flattenStack(ImagePlus imp, ThinPlateSpline3D tps) {
        long startTime = System.currentTimeMillis();
        
        int w = imp.getWidth(), h = imp.getHeight();
        int sls = imp.getNSlices(), frms = imp.getNFrames(), chs = imp.getNChannels();
        ImageStack outStack = new ImageStack(w, h, imp.getStackSize());

        AtomicInteger outOfBounds = new AtomicInteger(0);
        AtomicInteger inBounds = new AtomicInteger(0);
        AtomicLong minDisp = new AtomicLong(Double.doubleToLongBits(Double.MAX_VALUE));
        AtomicLong maxDisp = new AtomicLong(Double.doubleToLongBits(0));

        int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();


        IJ.showStatus("TPS flattening using CPU with " + numThreads + " threads...");
        int totalSlices = frms * sls * chs;
        AtomicInteger sliceCount = new AtomicInteger(0);

        for (int t = 0; t < frms; t++) {
            for (int z = 0; z < sls; z++) {
                for (int c = 0; c < chs; c++) {
                    final int fr = t, sl = z, ch = c;
                    final int sliceIdx = sliceCount.getAndIncrement();

                    futures.add(executor.submit(() -> {
                        FloatProcessor fp = new FloatProcessor(w, h);
                        for (int y = 0; y < h; y++) {
                            if (IJ.escapePressed()) return;
                            for (int x = 0; x < w; x++) {
                                double[] targetPt = new double[]{x, y, sl};
                                double[] srcPt = tps.transform(targetPt);

                                // Track displacement
                                double disp = Math.sqrt(Math.pow(srcPt[0] - x, 2) + Math.pow(srcPt[1] - y, 2) + Math.pow(srcPt[2] - sl, 2));
                                updateMinMax(minDisp, maxDisp, disp);

                                double val = sampleTrilinear(imp, ch, fr, srcPt[0], srcPt[1], srcPt[2]);
                                if (isOutOfBounds(imp, srcPt[0], srcPt[1], srcPt[2])) outOfBounds.incrementAndGet();
                                else inBounds.incrementAndGet();
                                fp.setf(x, y, (float) val);
                            }
                        }
                        synchronized (outStack) {
                            outStack.setProcessor(fp, imp.getStackIndex(ch + 1, sl + 1, fr + 1));
                        }

                        // Update progress
                        int done = sliceIdx + 1;
                        IJ.showProgress((double) done / (double) totalSlices);
                    }));
                }
            }
        }

        // Wait for all tasks to complete
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                IJ.error("Threading error: " + e.getMessage());
            }
        }
        executor.shutdown();

        long endTime = System.currentTimeMillis();
        long elapsed = endTime - startTime;

        IJ.showProgress(1.0);
        int total = w * h * sls;
        double minD = Double.longBitsToDouble(minDisp.get());
        double maxD = Double.longBitsToDouble(maxDisp.get());
        IJ.log("Flattening complete: " + inBounds.get() + " in-bounds, " + outOfBounds.get() + " out-of-bounds (" +
               String.format("%.1f%%", 100.0 * outOfBounds.get() / total) + ")");
        IJ.log("Displacement range: [" + String.format("%.2f", minD) + ", " + String.format("%.2f", maxD) + "]");
        IJ.log("Processing time: " + String.format("%.2f", elapsed / 1000.0) + " seconds");

        ImagePlus out = new ImagePlus(imp.getTitle() + "-TPSFlat", outStack);
        if(imp.isHyperStack()) {
            out.setDimensions(chs, sls, frms);
        }
        if(imp.isComposite()) {
            out=new CompositeImage(out);
            ((CompositeImage)out).setMode(((CompositeImage)imp).getMode());
            for(int c=1;c<=chs;c++){
                ((CompositeImage)out).setChannelLut(((CompositeImage)imp).getChannelLut(c), c);
            }
        }
        out.setCalibration(imp.getCalibration());
        out.show();
        IJ.showStatus("TPS flattening complete");
    }

    private synchronized void updateMinMax(AtomicLong minDisp, AtomicLong maxDisp, double disp) {
        while (true) {
            long currentMinBits = minDisp.get();
            double currentMin = Double.longBitsToDouble(currentMinBits);
            if (disp >= currentMin) break;
            if (minDisp.compareAndSet(currentMinBits, Double.doubleToLongBits(disp))) break;
        }
        while (true) {
            long currentMaxBits = maxDisp.get();
            double currentMax = Double.longBitsToDouble(currentMaxBits);
            if (disp <= currentMax) break;
            if (maxDisp.compareAndSet(currentMaxBits, Double.doubleToLongBits(disp))) break;
        }
    }

    private boolean isOutOfBounds(ImagePlus imp, double x, double y, double z) {
        int w = imp.getWidth(), h = imp.getHeight(), sls = imp.getNSlices();
        return x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= sls;
    }

    private double getMinZ(double[][] pts) {
        double m = Double.MAX_VALUE;
        for (double[] p : pts) m = Math.min(m, p[2]);
        return m;
    }

    private double getMaxZ(double[][] pts) {
        double m = -Double.MAX_VALUE;
        for (double[] p : pts) m = Math.max(m, p[2]);
        return m;
    }

    private String loadKernelSource() {
        try {
            InputStream is = this.getClass().getClassLoader().getResourceAsStream("tps_resample.cl");
            if (is == null) {
                IJ.log("Warning: tps_resample.cl not found in resources");
                return null;
            }
            // Java 8 compatible: read stream manually
            java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            is.close();
            return result.toString("UTF-8");
        } catch (Exception e) {
            IJ.error("Error loading kernel: " + e.getMessage());
            return null;
        }
    }

    // Improved trilinear sampling with proper interpolation at all 8 corners
    private double sampleTrilinear(ImagePlus imp, int ch, int fr, double x, double y, double z) {
        int w = imp.getWidth(), h = imp.getHeight(), sls = imp.getNSlices();

        // Clamp to valid range (allowing interpolation at boundaries)
        x = Math.max(0, Math.min(w - 1.0, x));
        y = Math.max(0, Math.min(h - 1.0, y));
        z = Math.max(0, Math.min(sls - 1.0, z));

        // Get integer parts and weights
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        int z1 = Math.min(z0 + 1, sls - 1);

        double wx1 = x - x0, wx0 = 1.0 - wx1;
        double wy1 = y - y0, wy0 = 1.0 - wy1;
        double wz1 = z - z0, wz0 = 1.0 - wz1;

        // Sample 8 corners and interpolate
        ImageProcessor ip0 = imp.getStack().getProcessor(imp.getStackIndex(ch + 1, z0 + 1, fr + 1));
        ImageProcessor ip1 = imp.getStack().getProcessor(imp.getStackIndex(ch + 1, z1 + 1, fr + 1));
        double v000 = ip0.getf(x0, y0);
        double v001 = ip1.getf(x0, y0);
        double v010 = ip0.getf(x0, y1);
        double v011 = ip1.getf(x0, y1);
        double v100 = ip0.getf(x1, y0);
        double v101 = ip1.getf(x1, y0);
        double v110 = ip0.getf(x1, y1);
        double v111 = ip1.getf(x1, y1);

        return wx0 * wy0 * wz0 * v000 +
               wx0 * wy0 * wz1 * v001 +
               wx0 * wy1 * wz0 * v010 +
               wx0 * wy1 * wz1 * v011 +
               wx1 * wy0 * wz0 * v100 +
               wx1 * wy0 * wz1 * v101 +
               wx1 * wy1 * wz0 * v110 +
               wx1 * wy1 * wz1 * v111;
    }
}
