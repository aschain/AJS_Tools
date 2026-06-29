import tifffile as tf
import sys
from pathlib import Path

image_path = Path(sys.argv[1]).resolve()
zlevel = -1
if len(sys.argv) > 2:
    zlevel = int(sys.argv[2])

# 4. Use the path
if image_path.exists():
    print(f"Working on image: \n{image_path}")
else:
    print("Error: The path does not exist.")
    sys.exit()

# Load multidimensional image
img_st = tf.memmap(image_path)

if len(img_st.shape) > 4:
    if zlevel < 0:
        print(f"Image has too many dimensions ({len(img_st.shape)}). Do a z-projection first.")
        sys.exit()
    if zlevel > img_st.shape[1]:
        print(f"Supplied Z ({zlevel}) was out of range 1-{img_st.shape[1]}")
        sys.exit()
    print(f"Using slice {zlevel} out of {img_st.shape[1]}")
    img_st = img_st[:, zlevel-1, :, :, :]

if len(img_st.shape) > 3 and img_st.shape[1] > 1:
    print(f"Using channel 2 out of {img_st.shape[1]}...")
    img_st = img_st[:, 1, :, :]

print(f"Image shape: {img_st.shape}")

from cellpose import models, io
# Load model
model = models.CellposeModel(gpu=True, pretrained_model='CP_20250108_115906')
io.logger_setup()
if len(img_st.shape) > 2:
    masks, flows, styles = model.eval(img_st, diameter=30, z_axis=0, channels=[0, 0], stitch_threshold=0.2)
else:
    masks, flows, styles = model.eval(img_st, diameter=30, z_axis=0, channels=[0, 0])
tf.imwrite(f"{image_path.parent / image_path.stem}-AJTCTcp.tif", masks)
print(f"Wrote file: {image_path.parent / image_path.stem}-AJTCTcp.tif")
