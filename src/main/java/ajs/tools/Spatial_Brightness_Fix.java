package ajs.tools;

import ij.plugin.PlugIn;
import ij.*;
import ij.process.*;


public class Spatial_Brightness_Fix implements PlugIn  {
    
    public static final double BG_MAP_GB_RADIUS=100.0;
	public static final double BG_MAP_GB__POST_RADIUS=20.0;
    public static final String BG_MAP_PREFIX="BMap-";

    @Override
	public void run(String arg) {
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null){IJ.noImage(); return;}
		ImagePlus bmap=WindowManager.getImage(BG_MAP_PREFIX+imp.getTitle());
		if(bmap==null){ genBrightnessTimeMap(); return;}
		
		applyBrightnessTimeMap(imp, bmap);
	}

    public void applyBrightnessTimeMap(ImagePlus imp, ImagePlus bmap){
        ImageStack bstack=bmap.getStack();
		ImageStack stack=imp.getStack();
		for(int ch=1; ch<=imp.getNChannels(); ch++) {
			for(int z=1; z<=imp.getNSlices(); z++) {
				for(int t=1; t<=imp.getNFrames(); t++) {
					ImageProcessor ip=stack.getProcessor(imp.getStackIndex(ch, z, t));
					ImageProcessor bip=bstack.getProcessor(bmap.getStackIndex(1, 1, t));
					for(int y=0; y<ip.getHeight(); y++) {
						for(int x=0; x<ip.getWidth(); x++) {
							int v=ip.getPixel(x, y);
							float d=bip.getf(x,y);
							ip.putPixel(x, y, (int)(v/d));
						}
					}
				}
			}
		}
    }

	public void genBrightnessTimeMap(){
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) return;
		String title=imp.getTitle();
		ImagePlus zave=AJ_Misc_Plugins.ZProjector(imp, 0, true, 1, imp.getNSlices());
		ImagePlus aveimp=AJ_Misc_Plugins.CProjector(imp, 0, true, true, 1, imp.getNChannels());
		zave.close();
		ImageStack stack=aveimp.getStack();
		for(int fr=1; fr<=aveimp.getNFrames(); fr++){
			ImageProcessor ip=stack.getProcessor(fr);
			//Large Gaussian blur to remove small features and get a background map
			new ij.plugin.filter.GaussianBlur().blurGaussian(ip, BG_MAP_GB_RADIUS);
			//Remove 0's from the edges
			int ah=aveimp.getHeight()/2;
			int aw=aveimp.getWidth()/2;
			int xadj=-1, yadj=-1;
			for(int y=ah; y<aveimp.getHeight(); y++){
				xadj=-1; yadj=-1;
				for(int x=aw; x<aveimp.getWidth(); x++){
					int val=ip.getPixel(x, y);
					if(val==0) ip.putPixel(x, y, ip.getPixel(x+xadj, y+yadj));
				}
				xadj=1; yadj=-1;
				for(int x=aw; x>0; x--){
					int val=ip.getPixel(x, y);
					if(val==0) ip.putPixel(x, y, ip.getPixel(x+xadj, y+yadj));
				}
			}
			for(int y=ah; y>=0; y--){
				xadj=-1; yadj=1;
				for(int x=aw; x<aveimp.getWidth(); x++){
					int val=ip.getPixel(x, y);
					if(val==0) ip.putPixel(x, y, ip.getPixel(x+xadj, y+yadj));
				}
				xadj=1; yadj=1;
				for(int x=aw; x>0; x--){
					int val=ip.getPixel(x, y);
					if(val==0) ip.putPixel(x, y, ip.getPixel(x+xadj, y+yadj));
				}
			}
		}
		ImageProcessor firstip=aveimp.getStack().getProcessor(1);
		ImagePlus fimp=IJ.createHyperStack(BG_MAP_PREFIX+title, imp.getWidth(), imp.getHeight(), 1, 1, imp.getNFrames(), 32);
		ImageStack fstack=fimp.getStack();
		for(int fr=1; fr<=aveimp.getNFrames(); fr++){
			ImageProcessor ip=aveimp.getStack().getProcessor(fr);
			ImageProcessor finip=fstack.getProcessor(fr);
			for(int y=0; y<ip.getHeight(); y++){
				for(int x=0; x<ip.getWidth(); x++){
					float f=firstip.getf(x, y);
					float v=ip.getf(x, y);
					finip.setf(x, y, v/f);
				}
			}
			new ij.plugin.filter.GaussianBlur().blurGaussian(finip, BG_MAP_GB__POST_RADIUS);
		}
		fimp.show();
	}
}
