# AJS Tools

AJS Tools is a collection of ImageJ/Fiji plugins for microscopy image processing, two-photon data import, and analysis workflows.

## What this package contains

The tools in this repository support tasks such as:

- Importing and organizing two-photon imaging datasets
- Transferring and editing slice labels and image metadata
- Trimming, projecting, and otherwise manipulating image stacks
- Measuring diameters and intensity profiles
- Flattening or correcting image stacks for skull or tissue alignment
- Automated Threshold-based quantification of cell shapes across time
- Used for training AI/ML models (Cellpose), and incorporating Cellpose output
- Analyzing GCaMP / fluorescence signals and exporting summary tables

The plugin entry points are defined in [src/main/resources/plugins.config](src/main/resources/plugins.config).

## Requirements

- Fiji or ImageJ
- Maven for compiling

The build uses ImageJ/Fiji dependencies and a few additional packages such as CLIJ2, Skeletonize3D, and AnalyzeSkeleton.

## Build

From the project root, run:

```bash
mvn package
```

This produces a plugin jar in the target directory.

## Install

After building, copy the generated jar from the target folder into your Fiji/ImageJ plugins directory, or if you have your imagej directory defined in maven, run:

```bash
mvn install
```

## Main plugin tools

### Image import and metadata

- TwoPhoton Import — opens two-photon image directories and imports stacks from common microscopy folders.
- Get 2p Info — prints or exposes two-photon image metadata and location information.
- Run AJS-Tools plugin — utility entry point for miscellaneous helper actions.
- AJ Options — opens options for the TwoPhoton Import workflow.

### Slice labels and image info

- Slicelabel Transfer — transfers slice labels from one image stack to another.
- Slicelabel Editor — Opens an editor window and allows text editing of slicelables (Just hit save in the editor to write the changes to the image in memory, the image must be saved independently to save all changes to disk!).
- Info Editor — a variant of the editor for editing the image info field.
- Print Times — overlays, prints, or gathers timing information on multi-frame image stacks based on times imported from Olympus-style pty files found in the slice labels (a line with ptytime: [ms after start time], and optionally a line with Starttime: [linux epoch start time]).

### Measurement and analysis

- Diameter Profile — measures vessel or axon diameter profiles from line or rectangle selections.
- Thresh-Cell-Transfer — interactive workflow for threshold-based cell analysis, ROI transfer to AI/ML-compatible mask images, and exported measurements including circularity.
- DC Pt Reslice — Create xz and yz images with information from the previous workflow to determine changes in z-positions of selected cells.
- Copy Stack Roi Means — copies ROI mean intensity values from a stack.
- GCaMP Data and Figure — analyzes GCaMP fluorescence traces and creates simple summary figures for importing into data analysis software.
- Distance From Roi to Labels — measures distances from ROI selections to label maps. Helpful for creating an ROI of blood vessels, and then calculating the distance of each selected (for example, in Thresh-Cell-Transfer) cell's distance to a blood vessel.

### Stack adjustment and projection

- Stack Trimmer — trims stacks by channel, slice, or frame range.
- Normalize Brightness — normalizes image brightness across selected dimensions based on projections.
- Image Translator by Pt — translates each slice of a frame of a multi-frame image based on selected or measured point locations or from previous SliceReg measurements.
- Combine by Pt — combines two multidimensional images into one larger image with alignment based on selecting a common point in each image.
- CCR2ConcatChs — Checks each open image for a matching image taken in the same location, but with a different wavelength, and concatenates the red channel of 730 nm image onto the 900 nm image (useful for sequential images of GFP/tdTomato in a mosaic).
- XYZ-Shift Channel — shifts a channel in X/Y/Z. Useful for fixing slight mismatches between channels in the previous plugin.
- Skull Leveler — Flattens or levels image stacks using map-based or Thin-Plate-Spline-based approaches. For example, image stacks of macrophages in the dura can be spread over many z-planes due to curvature of the skull. This plugin detects this curvature based on SHG of the skull captured in the blue channel and creates a heat map of the z-displacement and warps the image to correct for this. Alternately, cell positions can be selected in x,y,z with multi-point tool and a Thin Plate Spline technique (using GPU accelleration) is used for the correction.
- T Projector — Like ImageJ stack Z-projections but across time instead of z-slices.
- Z Step Max — performs a max intensity z-kernel filter.
- Concat Chs — concatenates images by their channels. Images must have matching slices and frames (found in Image > Stacks > Tools menu instead of Plugins > AJS).

## Notes

This project is an ImageJ 1.x plugin bundle and may require a Fiji-compatible environment for the most reliable experience.
