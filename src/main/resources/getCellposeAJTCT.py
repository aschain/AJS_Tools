import tifffile as tf
import sys
from pathlib import Path

# Usage: python getCellposeAJTCT.py <image_path> <channel> <slice>
if len(sys.argv) < 4:
    print("Usage: python getCellposeAJTCT.py <image_path> <channel> <slice>")
    print("      <image_path>: Path to the image file (e.g., .tif)")
    print("      <channel>: Channel to use (1-based) or 0 if only one channel")
    print("      <slice>: Slice number (1-based) or 0 if only 1 slice")
    sys.exit()

image_path = Path(sys.argv[1]).resolve()
channel = int(sys.argv[2])
slice = int(sys.argv[3])

# 4. Use the path
if image_path.exists():
    print(f"Working on image: \n{image_path}")
else:
    print("Error: The path does not exist.")
    sys.exit()

# Load multidimensional image
img_st = tf.memmap(image_path)  # type: ignore[assignment]
if len(img_st.shape) == 5:
    if slice < 1 or channel < 1:
        print(f"Image has too many dimensions ({len(img_st.shape)}). Must choose z and c.")
        sys.exit()
    if slice > img_st.shape[1]:
        print(f"Supplied Z ({slice}) was out of range 1-{img_st.shape[1]}")
        sys.exit()
    if channel > img_st.shape[2]:
        print(f"Supplied Channel ({channel}) was out of range 1-{img_st.shape[2]}")
        sys.exit()
    print(f"Using slice {slice} and channel {channel}")
    img_st = img_st[:, slice-1, channel-1, :, :]  # type: ignore[assignment]
else:
    if len(img_st.shape) == 4:
        if channel < 1:
            if slice < 1:
                print(f"Image has too many dimensions ({img_st.shape}). Please include a channel or slice selection.")
                sys.exit()
            else:
                if slice > img_st.shape[1]:
                    print(f"Supplied Z ({slice}) was out of range 1-{img_st.shape[1]}")
                    sys.exit()
                print(f"Using slice {slice} out of {img_st.shape[1]}...")
                img_st = img_st[:, slice-1, :, :]  # type: ignore[assignment]
        else:
            if channel > img_st.shape[1]:
                print(f"Supplied channel ({channel}) was out of range 1-{img_st.shape[1]}")
                sys.exit()
            if slice <= 0:
                print(f"Using channel {channel} out of {img_st.shape[1]}...")
                img_st = img_st[:, channel-1, :, :]  # type: ignore[assignment]
            else:
                if slice > img_st.shape[2]:
                    print(f"Supplied Z ({slice}) was out of range 1-{img_st.shape[2]}")
                    sys.exit()
                print(f"Using channel {channel} and slice {slice}...")
                img_st = img_st[slice-1, channel-1, :, :]  # type: ignore[assignment]
    else:
        if len(img_st.shape) == 3:
            if channel > 0 and slice > 0:
                print(f"Channel ({channel}) and Slice ({slice}) were provided but image does not have both channels and slices.")
                sys.exit()
            else:
                if channel > 0:
                    if channel > img_st.shape[0]:
                        print(f"Supplied channel ({channel}) was out of range 1-{img_st.shape[0]}")
                        sys.exit()
                    print(f"Using channel {channel} out of {img_st.shape[0]}...")
                    img_st = img_st[channel-1, :, :]  # type: ignore[assignment]
                elif slice > 0:
                    if slice > img_st.shape[0]:
                        print(f"Supplied Z ({slice}) was out of range 1-{img_st.shape[0]}")
                        sys.exit()
                    print(f"Using slice {slice} out of {img_st.shape[0]}...")
                    img_st = img_st[slice-1, :, :]  # type: ignore[assignment]

print(f"Using image with shape {img_st.shape}")

from cellpose import models, io  # type: ignore[import-untyped]  # noqa: E402
# Load model
model = models.CellposeModel(gpu=True, pretrained_model='CP_20250108_115906')
io.logger_setup()
if len(img_st.shape) > 2:
    masks, flows, styles = model.eval(img_st, diameter=30, z_axis=0, channels=[0, 0], stitch_threshold=0.2)
else:
    masks, flows, styles = model.eval(img_st, diameter=30, z_axis=0, channels=[0, 0])
tf.imwrite(f"{image_path.parent / image_path.stem}-AJTCTcp.tif", masks)
io.save_rois(masks, f"{image_path.parent / image_path.stem}-AJTCTcp")
print(f"Wrote file: {image_path.parent / image_path.stem}-AJTCTcp.tif")
