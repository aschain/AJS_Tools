package ajs.tools;
import ij.plugin.PlugIn;
import ij.*;
import ij.gui.*;
import ij.process.ImageProcessor;

/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class Mosaic_Overlap_Fix implements PlugIn {
	
	private static final int EWIDTH=1024;
	
	/**
	 * This method gets called by ImageJ / Fiji.
	 *
	 * @param arg can be specified in plugins.config
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	@Override
	public void run(String arg) {
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) return;
		int width=imp.getWidth(), height=imp.getHeight();
		GenericDialog gd=new GenericDialog("Mosaic Overlap Fix");
		gd.addNumericField("Overlap?", 40, 0);
		gd.addNumericField("Number of images in a row?", (int)(width/EWIDTH), 0);
		gd.addNumericField("Number of images in a column?", (int)Math.max((height/EWIDTH),1), 0);
		gd.showDialog();
		if(gd.wasCanceled()) return;
		int overlap=(int)gd.getNextNumber();
		int xCount=(int)gd.getNextNumber();
		int yCount=(int)gd.getNextNumber();
		int iwidth=width/xCount;
		int iheight=height/yCount;
		int stackSize=imp.getStackSize();
		for (int i=1; i<=stackSize; i++) {
			ImageProcessor ip=imp.getStack().getProcessor(i);
			int yi=-1, ostarty=0, oendy=0;
			for (int y=0; y<height; y++) {
				int xi=-1, ostartx=0, oendx=0;
				if(y==oendy && yi<(yCount-1)){
					yi++;
					ostarty=(yi+1)*(iheight-overlap);
					oendy=ostarty+overlap;
				}
				for (int x=0; x<width; x++) {
					if(x==oendx && xi<(xCount-1)){
						xi++;
						ostartx=(xi+1)*(iwidth-overlap);
						oendx=ostartx+overlap;
					}
					if(x<ostartx && y<ostarty && xi==0 && yi==0){
							x=ostartx-1;
					} else if( (((x>=ostartx && x<oendx) && (x<(xCount*(iwidth-overlap))) && xi<(xCount-1)) || 
								((y>=ostarty && y<oendy) && (y<(yCount*(iheight-overlap))) && yi<(yCount-1))) && 
								x<(xCount*(iwidth-overlap)+overlap) && y<(yCount*(iheight-overlap)+overlap)) {
						int xc=x+(overlap*xi), yc=y+(overlap*yi);
						int xcf=xc, ycf=yc;
						if(x>=ostartx && x<oendx && xi<(xCount-1)) xcf+=overlap;
						if(y>=ostarty && y<oendy && yi<(yCount-1)) ycf+=overlap;
						int a=ip.get(xc,yc);
						int b=ip.get(xcf, ycf);
						int value=Math.max(a,b);
						ip.set(x, y, value);
					} else if(x<(xCount*(iwidth-overlap)+overlap) && y<(yCount*(iheight-overlap)+overlap)) {
						ip.set(x, y, ip.get(x+overlap*xi,y+overlap*yi));
					}else{
						ip.set(x, y, 0);
					}
				}
			}
		}
	imp.updateAndDraw();
	}
}
