package ajs.tools;

import ij.plugin.*;
import ij.process.*;
import ij.*;
import ij.gui.*;
import java.awt.AWTEvent;

/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class RemoveColor implements PlugIn, DialogListener {
	
	ImagePlus imp=null;
	int ch1=2,ch2=3;
	short[] backupPx=null;
	ShortProcessor ip=null;
	boolean wasReset=true;
	
	
	/**
	 * This method gets called by ImageJ / Fiji.
	 *
	 * @param arg can be specified in plugins.config
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	@Override
	public void run(String arg){
		
		GenericDialog gd=new GenericDialog("Remove Color Ratio");
		gd.addNumericField("Channel to be modified (Divisor of ratio)", 2, 0);
		gd.addNumericField("Channel to compare (Numerator of ratio)", 3, 0);
		gd.showDialog();
		
		if(gd.wasCanceled())return;
		
		ch1=(int)gd.getNextNumber();
		ch2=(int)gd.getNextNumber();
		
		imp=WindowManager.getCurrentImage();
		imp.setPosition(ch1, imp.getZ(), imp.getT());
		ip=(ShortProcessor)imp.getProcessor();
		short[] imagepx=(short[])ip.getPixels();
		backupPx=new short[imagepx.length];
		for(int i=0;i<imagepx.length;i++)backupPx[i]=imagepx[i];
		
		gd=new GenericDialog("Remove Color Ratio");
		gd.addNumericField("Biggest factor to divide out", 0.9, 2);
		gd.addNumericField("Ideal Ratio to be removed", 0.56, 3);
		gd.addNumericField("Max ratio difference", 0.7, 3);
		gd.addDialogListener(this);
		gd.showDialog();
		if(!wasReset)reset();
		imp.updateAndDraw();
		if(gd.wasCanceled()) {return;}
		float bfac=(float)gd.getNextNumber();
		float idealRatio=(float)gd.getNextNumber();
		float ratdiff=(float)gd.getNextNumber();
		
		imp.setPosition(ch1, imp.getZ(), imp.getT());
		
		ImageStack st=imp.getStack();
		//int w=imp.getWidth(), h=imp.getHeight();
		int frms=imp.getNFrames(), sls=imp.getNSlices();
		double frmsd=(double) frms, slsd=(double)sls;
		//int sl=imp.getZ(), fr=imp.getT();
		for(int fr=1;fr<=frms;fr++) {
			for(int sl=1; sl<=sls; sl++) {
				IJ.showStatus("Removing by color ratio...");
				IJ.showProgress(((double)fr*slsd+(double)sl)/(frmsd*slsd));
				ShortProcessor ip1=(ShortProcessor)(st.getProcessor(imp.getStackIndex(ch1,sl,fr))), ip2=(ShortProcessor)st.getProcessor(imp.getStackIndex(ch2,sl,fr));
				removeRatio(ip1, ip2, bfac, idealRatio, ratdiff);
			}
		}
		imp.updateAndDraw();
		
	}
	
	public static void removeRatio(ShortProcessor ip1, ShortProcessor ip2, float bfac, float idealRatio, float ratdiff) {
		int h=ip1.getHeight(), w=ip1.getWidth();
		for(int y=0;y<h;y++) {
			for(int x=0; x<w; x++){
				float g=ip1.getPixelValue(x,y), r=ip2.getPixelValue(x, y), rat=r/g;
				float factor=1.0f-(bfac-(bfac*Math.min(Math.abs(rat-idealRatio),ratdiff)/ratdiff));
				ip1.set(x, y, (int)(g*factor));
			}
		}
	}

	@Override
	public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {

		float bfac=(float)gd.getNextNumber();
		float idealRatio=(float)gd.getNextNumber();
		float ratdiff=(float)gd.getNextNumber();
		if(bfac<=0 || idealRatio<=0 || ratdiff<=0) {
			if(!wasReset)reset();
			imp.updateAndDraw();
			return false;
		}
		int z=imp.getZ(), t=imp.getT();
		imp.setPosition(ch1, z, t);
		ImageStack st=imp.getStack();
		ShortProcessor ip1=(ShortProcessor)(st.getProcessor(imp.getStackIndex(ch1,z,t))), ip2=(ShortProcessor)st.getProcessor(imp.getStackIndex(ch2,z,t));
		if(!wasReset)reset();
		removeRatio(ip1, ip2, bfac, idealRatio, ratdiff);
		ip1.resetMinAndMax();
		imp.updateAndDraw();
		wasReset=false;
		return true;
	}
	
	private void reset() {
		if(ip==null || backupPx==null) {return;}
		for(int y=0;y<ip.getHeight();y++)for(int x=0;x<ip.getWidth();x++)ip.set(x,y,backupPx[y*imp.getWidth()+x]);
		wasReset=true;
	}
	
}
