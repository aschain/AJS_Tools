package ajs.tools;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

/**
 * Thin-plate spline (3D) implementation using EJML for dense solves.
 * Fits a mapping from source points to target points in R^3.
 */
public class ThinPlateSpline3D {
    private int N;
    private double[][] src; // N x 3
    private DMatrixRMaj W;  // N x 3 weights
    private DMatrixRMaj Acoef; // 4 x 3 affine coefficients

    public ThinPlateSpline3D() {}

    public ThinPlateSpline3D(double[][] srcPts, double[][] tgtPts, double lambda) {
        fit(tgtPts, srcPts, lambda);
    }

    /**
     * Fit TPS mapping from srcPts -> tgtPts with regularization lambda.
     * Both arrays must have same length N and each point length 3.
     */
    public void fit(double[][] srcPts, double[][] tgtPts, double lambda) {
        if (srcPts == null || tgtPts == null) throw new IllegalArgumentException("Points null");
        if (srcPts.length != tgtPts.length) throw new IllegalArgumentException("Mismatched point counts");
        this.N = srcPts.length;
        this.src = new double[N][3];
        for (int i = 0; i < N; i++) {
            System.arraycopy(srcPts[i], 0, this.src[i], 0, 3);
        }

        int M = N + 4;
        DMatrixRMaj A = new DMatrixRMaj(M, M);
        DMatrixRMaj B = new DMatrixRMaj(M, 3);

        // Fill K block with phi(r)=r (3D radial basis)
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                double r = dist(srcPts[i], srcPts[j]);
                A.set(i, j, r);
            }
            // regularize diagonal
            if (lambda != 0) {
                A.add(i, i, lambda);
            }
        }

        // Fill P (N x 4) and P^T
        for (int i = 0; i < N; i++) {
            A.set(i, N + 0, 1.0);
            A.set(i, N + 1, srcPts[i][0]);
            A.set(i, N + 2, srcPts[i][1]);
            A.set(i, N + 3, srcPts[i][2]);

            A.set(N + 0, i, 1.0);
            A.set(N + 1, i, srcPts[i][0]);
            A.set(N + 2, i, srcPts[i][1]);
            A.set(N + 3, i, srcPts[i][2]);
        }

        // RHS B: top N rows = tgt coordinates, bottom 4 rows = 0
        for (int i = 0; i < N; i++) {
            B.set(i, 0, tgtPts[i][0]);
            B.set(i, 1, tgtPts[i][1]);
            B.set(i, 2, tgtPts[i][2]);
        }

        // Solve A X = B
        DMatrixRMaj X = new DMatrixRMaj(M, 3);
        CommonOps_DDRM.solve(A, B, X);

        // Extract W (N x 3)
        W = new DMatrixRMaj(N, 3);
        for (int i = 0; i < N; i++) {
            for (int d = 0; d < 3; d++) W.set(i, d, X.get(i, d));
        }

        // Extract affine (4 x 3)
        Acoef = new DMatrixRMaj(4, 3);
        for (int i = 0; i < 4; i++) {
            for (int d = 0; d < 3; d++) Acoef.set(i, d, X.get(N + i, d));
        }
    }

    /** Transform a single 3-vector point (x,y,z) -> mapped 3-vector. */
    public double[] transform(double[] x) {
        if (x.length < 3) throw new IllegalArgumentException("Input point must be length 3");
        double[] out = new double[3];

        // affine part
        out[0] = Acoef.get(0, 0) + Acoef.get(1, 0) * x[0] + Acoef.get(2, 0) * x[1] + Acoef.get(3, 0) * x[2];
        out[1] = Acoef.get(0, 1) + Acoef.get(1, 1) * x[0] + Acoef.get(2, 1) * x[1] + Acoef.get(3, 1) * x[2];
        out[2] = Acoef.get(0, 2) + Acoef.get(1, 2) * x[0] + Acoef.get(2, 2) * x[1] + Acoef.get(3, 2) * x[2];

        // radial part
        for (int i = 0; i < N; i++) {
            double r = dist(x, src[i]);
            double w0 = W.get(i, 0);
            double w1 = W.get(i, 1);
            double w2 = W.get(i, 2);
            out[0] += r * w0;
            out[1] += r * w1;
            out[2] += r * w2;
        }
        return out;
    }

    /** Get TPS weights matrix (N x 3). Returns copy. */
    public float[] getWeights() {
        float[] wts = new float[N * 3];
        for (int i = 0; i < N; i++) {
            for (int d = 0; d < 3; d++) {
                wts[i*3 + d] = (float) W.get(i, d);
            }
        }
        return wts;
    }

    /** Get affine coefficients matrix (4 x 3). Returns copy. */
    public float[] getAffineCoefficients() {
        float[] aff = new float[4 * 3];
        for (int i = 0; i < 4; i++) {
            for (int d = 0; d < 3; d++) {
                aff[i*3 + d] = (float) Acoef.get(i, d);
            }
        }
        return aff;
    }

    /** Get source landmarks. Returns copy. */
    public float[] getSourceLandmarks() {
        float[] lm = new float[N * 3];
        for (int i = 0; i < N; i++) {
            for (int d = 0; d < 3; d++) {
                lm[i*3 + d] = (float) src[i][d];
            }
        }
        return lm;
    }

    /** Get number of landmarks. */
    public int getNumLandmarks() {
        return N;
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double computeMaxZDisplacement(ThinPlateSpline3D tps, int w, int h, int sls, int xystep, int zstep) {
        double maxDz = 0.0;
        double[] pt = new double[3];
        
        for (int z = 0; z < sls; z += Math.max(1, zstep)) {
            pt[2] = z;
            for (int y = 0; y < h; y += Math.max(1, xystep)) {
                pt[1] = y;
                for (int x = 0; x < w; x += Math.max(1, xystep)) {
                    pt[0] = x;
                    double[] mapped = tps.transform(pt);
                    double dz = Math.abs(mapped[2] - pt[2]);
                    if (dz > maxDz) maxDz = dz;
                }
            }
        }
        return maxDz;
    }
}
