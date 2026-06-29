/**
 * OpenCL kernel: TPS evaluation + trilinear interpolation
 * Evaluates thin-plate-spline transform at each output voxel, then trilinearly samples input image.
 * 
 * Parameters:
 *   tpsWeights: N x 3 matrix (row-major), flattened
 *   affineCoeff: 4 x 3 matrix (row-major), flattened
 *   landmarkSrc: N x 3 matrix (row-major), flattened
 *   N: number of landmarks
 */

inline float dist3(float x1, float y1, float z1, float x2, float y2, float z2) {
    float dx = x1 - x2;
    float dy = y1 - y2;
    float dz = z1 - z2;
    return sqrt(dx*dx + dy*dy + dz*dz);
}

/**
 * Evaluate TPS at point (x, y, z).
 * Returns the transformed 3D point.
 */
float3 tpsEval(float x, float y, float z,
               global float *tpsWeights,
               global float *affineCoeff,
               global float *landmarkSrc,
               int N) {
    float3 out;
    
    // Affine part: stored row-major (4 rows x 3 cols)
    // aff[0..2] = [a00, a01, a02], aff[3..5] = [a10, a11, a12], etc.
    // output = [a00 + a10*x + a20*y + a30*z, a01 + a11*x + a21*y + a31*z, a02 + a12*x + a22*y + a32*z]
    out.x = affineCoeff[0] + affineCoeff[3]*x + affineCoeff[6]*y + affineCoeff[9]*z;
    out.y = affineCoeff[1] + affineCoeff[4]*x + affineCoeff[7]*y + affineCoeff[10]*z;
    out.z = affineCoeff[2] + affineCoeff[5]*x + affineCoeff[8]*y + affineCoeff[11]*z;
    
    // Radial part: sum_i w_i * r(x - src_i)
    for (int i = 0; i < N; i++) {
        float r = dist3(x, y, z, landmarkSrc[3*i], landmarkSrc[3*i+1], landmarkSrc[3*i+2]);
        out.x += tpsWeights[3*i] * r;
        out.y += tpsWeights[3*i+1] * r;
        out.z += tpsWeights[3*i+2] * r;
    }
    return out;
}

/**
 * Trilinear interpolation from input image at (sx, sy, sz).
 * Assumes clamping to valid bounds already done.
 */
float trilinearSample(read_only image3d_t inputImage, float sx, float sy, float sz) {
    int x0 = (int)floor(sx);
    int y0 = (int)floor(sy);
    int z0 = (int)floor(sz);
    int x1 = x0 + 1;
    int y1 = y0 + 1;
    int z1 = z0 + 1;
    
    float wx1 = sx - x0, wx0 = 1.0f - wx1;
    float wy1 = sy - y0, wy0 = 1.0f - wy1;
    float wz1 = sz - z0, wz0 = 1.0f - wz1;
    
    sampler_t sampler = CLK_NORMALIZED_COORDS_FALSE | CLK_ADDRESS_CLAMP | CLK_FILTER_NEAREST;
    
    float v000 = read_imagef(inputImage, sampler, (int4)(x0, y0, z0, 0)).x;
    float v001 = read_imagef(inputImage, sampler, (int4)(x0, y0, z1, 0)).x;
    float v010 = read_imagef(inputImage, sampler, (int4)(x0, y1, z0, 0)).x;
    float v011 = read_imagef(inputImage, sampler, (int4)(x0, y1, z1, 0)).x;
    float v100 = read_imagef(inputImage, sampler, (int4)(x1, y0, z0, 0)).x;
    float v101 = read_imagef(inputImage, sampler, (int4)(x1, y0, z1, 0)).x;
    float v110 = read_imagef(inputImage, sampler, (int4)(x1, y1, z0, 0)).x;
    float v111 = read_imagef(inputImage, sampler, (int4)(x1, y1, z1, 0)).x;
    
    return wx0*wy0*wz0*v000 + wx0*wy0*wz1*v001 + wx0*wy1*wz0*v010 + wx0*wy1*wz1*v011 +
           wx1*wy0*wz0*v100 + wx1*wy0*wz1*v101 + wx1*wy1*wz0*v110 + wx1*wy1*wz1*v111;
}

/**
 * Simple copy kernel for I/O testing
 */
__kernel void copyImage(
    read_only image3d_t inputImage,
    write_only image3d_t outputImage) {
    
    int x = get_global_id(0);
    int y = get_global_id(1);
    int z = get_global_id(2);
    
    sampler_t sampler = CLK_NORMALIZED_COORDS_FALSE | CLK_ADDRESS_CLAMP | CLK_FILTER_NEAREST;
    float val = read_imagef(inputImage, sampler, (int4)(x, y, z, 0)).x;
    write_imagef(outputImage, (int4)(x, y, z, 0), (float4)(val, 0, 0, 0));
}

/**
 * Simple identity kernel for testing (no TPS, just sample at output coords)
 */
__kernel void identitySample(
    read_only image3d_t inputImage,
    write_only image3d_t outputImage,
    int imgWidth,
    int imgHeight,
    int imgDepth) {
    
    int x = get_global_id(0);
    int y = get_global_id(1);
    int z = get_global_id(2);
    
    if (x >= imgWidth || y >= imgHeight || z >= imgDepth) return;
    
    // Just trilinear sample at same coordinates
    float val = trilinearSample(inputImage, (float)x, (float)y, (float)z);
    write_imagef(outputImage, (int4)(x, y, z, 0), (float4)(val, 0, 0, 0));
}

/**
 * Full TPS kernel: evaluate TPS transform and trilinearly resample input
 */
__kernel void tpsResample(
    read_only image3d_t inputImage,
    write_only image3d_t outputImage,
    global float *tpsWeights,
    global float *affineCoeff,
    global float *landmarkSrc,
    int N,
    int imgWidth,
    int imgHeight,
    int imgDepth) {
    
    int x = get_global_id(0);
    int y = get_global_id(1);
    int z = get_global_id(2);
    
    // if (x >= imgWidth || y >= imgHeight || z >= imgDepth) return;
    if (x >= imgWidth || y >= imgHeight) return;
    
    // Evaluate TPS at output voxel
    float3 srcPt = tpsEval((float)x, (float)y, (float)z, tpsWeights, affineCoeff, landmarkSrc, N);
    
    // Clamp to bounds
    srcPt.x = max(0.0f, min((float)(imgWidth - 1), srcPt.x));
    srcPt.y = max(0.0f, min((float)(imgHeight - 1), srcPt.y));
    //srcPt.z = max(0.0f, min((float)(imgDepth - 1), srcPt.z));
    if(srcPt.z < 0.0f || srcPt.z > (float)(imgDepth-1)) return;
    
    // Trilinear sample
    float val = trilinearSample(inputImage, srcPt.x, srcPt.y, srcPt.z);
    
    // Write output
    write_imagef(outputImage, (int4)(x, y, z, 0), (float4)(val, 0, 0, 0));
}

