package ajs.tools;

import ij.plugin.*;
import ij.process.*;
import ij.*;
import ij.gui.*;
import java.awt.AWTEvent;
import java.awt.Checkbox;
import java.awt.Label;
import java.awt.Point;

/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class RemoveColor implements PlugIn, DialogListener, ImageListener {
	
	ImagePlus imp=null;
	int chRef=1,chTarget=2;
	short[] backupPx=null;
	ShortProcessor ip=null;
	boolean simpleSubtract=false;
	Label aveLabel=null;
	NonBlockingGenericDialog gd=null;
	
	/**
	 * This method gets called by ImageJ / Fiji.
	 *
	 * @param arg can be specified in plugins.config
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	@Override
	public void run(String arg){
		
		imp=WindowManager.getCurrentImage();
		if(imp==null) {
			IJ.noImage();
			return;
		}
		ImagePlus.addImageListener(this);
		
		gd=new NonBlockingGenericDialog("Remove Color Ratio");
		gd.addNumericField("Channel to compare to (Reference, Numerator of ratio)", chRef, 0);
		gd.addNumericField("Channel to be modified (Target, Divisor of ratio)", chTarget, 0);
		gd.addNumericField("Min Reference value:", 1, 0);
		gd.addNumericField("Min Target value:", 1, 0);
		float ave=getAverageRatio(imp, chTarget, chRef, true, 1f, 1f);
		gd.addMessage("Average color ratio is: "+ave);
		aveLabel=(Label)gd.getMessage();
		gd.addCheckbox("Substract out multiple of ratio", simpleSubtract);
		gd.addCheckbox("Substract out pixels closest to ratio", !simpleSubtract);
		gd.addNumericField("Maximum factor to reduce by", 0.9, 2);
		gd.addNumericField("Ideal Ratio to be removed", ave, 3);
		gd.addNumericField("Ratio range", 0.2, 3);
		gd.addCheckbox("(Calculate ave ratio from whole stack?)", true);
		gd.addDialogListener(this);
		gd.showDialog();
		reset();
		imp.updateAndDraw();
		if(gd.wasCanceled()) {return;}
		chRef=(int)gd.getNextNumber();
		chTarget=(int)gd.getNextNumber();
		float minRef=(float)gd.getNextNumber();
		float minTarget=(float)gd.getNextNumber();
		float bfac=(float)gd.getNextNumber();
		float idealRatio=(float)gd.getNextNumber();
		float ratdiff=(float)gd.getNextNumber()+idealRatio;
		
		removeRatioStack(imp, chTarget, chRef, bfac, idealRatio, ratdiff, minRef, minTarget, simpleSubtract);
	}

	public static float getAverageRatio(ImagePlus imp, int chTarget, int chRef, boolean fullstack, float minRef, float minTarget) {
		return getAverageRatio(imp, chTarget, chRef, fullstack, minRef, minTarget, null);
	}

	public static float getAverageRatio(ImagePlus imp, int chTarget, int chRef, boolean fullstack, float minRef, float minTarget, Roi roi) {
		int slstart=imp.getZ(), slend=slstart, frstart=imp.getT(), frend=frstart;
		if(fullstack) {
			slstart=1; slend=imp.getNSlices();
			frstart=1; frend=imp.getNFrames();
		}
		return getAverageRatio(imp, chTarget, chRef, slstart, slend, frstart, frend, minRef, minTarget, roi);
	}

	/**
	 * Calculate average ratio of two channels over specified (1-based) slices and frames
	 * @param imp ImagePlus
	 * @param chTarget channel to be changed
	 * @param chRef channel to compare to
	 * @param slstart	1-based start slice
	 * @param slend	1-based end slice
	 * @param frstart	1-based start frame
	 * @param frend	1-based end frame
	 * @param minRef	minimum reference pixel value
	 * @param minTarget	minimum target pixel value
	 * @param roi	optional ROI to limit calculation to (or null)
	 * @return
	 */
	public static float getAverageRatio(ImagePlus imp, int chTarget, int chRef, int slstart, int slend, int frstart, int frend, float minRef, float minTarget, Roi roi) {
		double sumRatios=0.0;
		int count=0;
		ImageStack st=imp.getStack();
		for(int fr=frstart;fr<=frend;fr++) {
			for(int sl=slstart; sl<=slend; sl++) {
				ShortProcessor ipTarget=(ShortProcessor)(st.getProcessor(imp.getStackIndex(chTarget,sl,fr))), ipRef=(ShortProcessor)st.getProcessor(imp.getStackIndex(chRef,sl,fr));
				int h=ipTarget.getHeight(), w=ipTarget.getWidth();
				Point[] cpoints=null;
				if(roi!=null) cpoints=roi.getContainedPoints();
				if(cpoints!=null) {
					for(Point p:cpoints) {
						float t=ipTarget.getPixelValue(p.x,p.y), r=ipRef.getPixelValue(p.x, p.y);
						if(r>=minRef && t>=minTarget) {
							sumRatios+=r/t;
							count++;
						}
					}
				}else{
					for(int y=0;y<h;y++) {
						for(int x=0; x<w; x++){
							float t=ipTarget.getPixelValue(x,y), r=ipRef.getPixelValue(x, y);
							if(r>=minRef && t>=minTarget) {
								sumRatios+=r/t;
								count++;
							}
						}
					}
				}
			}
		}
		double avgRatio=sumRatios/(double)count;
		return (float)avgRatio;
	}

	public static void removeRatioStack(ImagePlus imp, int chTarget, int chRef, float bfac, float idealRatio, float ratdiff, float minRef, float minTarget, boolean simpleSubtract) {
		ImageStack st=imp.getStack();
		int frms=imp.getNFrames(), sls=imp.getNSlices();
		double frmsd=(double) frms, slsd=(double)sls;
		for(int fr=1;fr<=frms;fr++) {
			for(int sl=1; sl<=sls; sl++) {
				IJ.showStatus("Removing by color ratio...");
				IJ.showProgress(((double)fr*slsd+(double)sl)/(frmsd*slsd));
				ShortProcessor ipTarget=(ShortProcessor)(st.getProcessor(imp.getStackIndex(chTarget,sl,fr))), ipRef=(ShortProcessor)st.getProcessor(imp.getStackIndex(chRef,sl,fr));
				removeRatio(ipTarget, ipRef, bfac, idealRatio, ratdiff, minRef, minTarget, simpleSubtract);
			}
		}
		imp.updateAndDraw();
	}

	public static void subtractRatioStack(ImagePlus imp, int chTarget, int chRef, int minRef, int minTarget) {
		removeRatioStack(imp, chTarget, chRef, 0f, getAverageRatio(imp, chTarget, chRef, true, minRef, minTarget), 0f, minRef, minTarget, true);
	}
	
	public static void removeRatio(ShortProcessor ipTarget, ShortProcessor ipRef, float bfac, float idealRatio, float ratdiff, float minRef, float minTarget, boolean simpleSubtract) {
		int h=ipTarget.getHeight(), w=ipTarget.getWidth();
		for(int y=0;y<h;y++) {
			for(int x=0; x<w; x++){
				float t=ipTarget.getPixelValue(x,y), r=ipRef.getPixelValue(x, y);
				if(r>=minRef || t>=minTarget){
					if(simpleSubtract) {
						ipTarget.set(x, y, (int)(Math.max(t-(r/idealRatio),0f)));
					}else{
						float rat=r/t;
						float factor=1.0f-(bfac-(bfac*Math.min(Math.abs(rat-idealRatio),ratdiff)/ratdiff));
						ipTarget.set(x, y, (int)(t*factor));
					}
				}
			}
		}
	}

	@Override
	public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {

		imp.unlock();
		chRef=(int)gd.getNextNumber();
		chTarget=(int)gd.getNextNumber();
		float minRef=(float)gd.getNextNumber();
		float minTarget=(float)gd.getNextNumber();
		boolean updatesimpleSubtract=gd.getNextBoolean();
		boolean nss=gd.getNextBoolean();
		float bfac=(float)gd.getNextNumber();
		float idealRatio=(float)gd.getNextNumber();
		float ratdiff=(float)gd.getNextNumber()+idealRatio;

		if(simpleSubtract){
			if(!updatesimpleSubtract)simpleSubtract=false;
			if(nss)simpleSubtract=false;
		}else{
			if(updatesimpleSubtract)simpleSubtract=true;
			if(!nss)simpleSubtract=true;
		}

		((Checkbox)gd.getCheckboxes().get(0)).setState(simpleSubtract);
		((Checkbox)gd.getCheckboxes().get(1)).setState(!simpleSubtract);

		reset();
		int z=imp.getZ(), t=imp.getT();
		imp.setPosition(chTarget, z, t);
		ip=(ShortProcessor)imp.getStack().getProcessor(imp.getStackIndex(chTarget,z,t));
		short[] imagepx=(short[])ip.getPixels();
		backupPx=new short[imagepx.length];
		for(int i=0;i<imagepx.length;i++)backupPx[i]=imagepx[i];

		if(bfac<=0 || idealRatio<=0 || ratdiff<=0) {
			imp.updateAndDraw();
			return false;
		}
		ImageStack st=imp.getStack();
		ShortProcessor ipTarget=ip, ipRef=(ShortProcessor)st.getProcessor(imp.getStackIndex(chRef,z,t));
		aveLabel.setText("Average color ratio is: "+getAverageRatio(imp, chTarget, chRef, gd.getNextBoolean(), minRef, minTarget));
		gd.repaint();
		removeRatio(ipTarget, ipRef, bfac, idealRatio, ratdiff, minRef, minTarget, simpleSubtract);
		ipTarget.resetMinAndMax();
		imp.updateAndDraw();
		return true;
	}
	
	private void reset() {
		if(ip==null || backupPx==null) {return;}
		for(int y=0;y<ip.getHeight();y++)for(int x=0;x<ip.getWidth();x++)ip.set(x,y,backupPx[y*imp.getWidth()+x]);
	}

	@Override
	public void imageOpened(ImagePlus imp) {
	}

	@Override
	public void imageClosed(ImagePlus imp) {
	}

	@Override
	public void imageUpdated(ImagePlus imp) {
		if(imp==this.imp){
			dialogItemChanged(gd, null);
		}
	}
	
}
