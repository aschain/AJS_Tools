package ajs.tools;
import ij.plugin.PlugIn;
import ij.*;
import ij.gui.*;
import ij.process.ImageProcessor;
import ij.process.Blitter;

/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class Mosaic_Combine_Fix implements PlugIn {
	
	private static final int EWIDTH=1024;
	
	/**
	 * This method gets called by ImageJ / Fiji.
	 *
	 * @param arg can be specified in plugins.config
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	@Override
	public void run(String arg) {
		if(arg.contentEquals("fix")) mosaicOverlapFix();
		else mosaicCombine();
	}

	public static void mosaicCombine(){
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) return;
		int nImages=WindowManager.getImageCount();
		if(nImages<2){
			mosaicOverlapFix();
			return;
		}
		String[] titles=WindowManager.getImageTitles();
		java.util.Arrays.sort(titles);
		boolean timeSeries=false;
		for(int i=0; i<nImages; i++){
			if(titles[i].matches("Image000[0-9]_01\\.oif") && WindowManager.getImage(titles[i].substring(0,11)+"2.oif")!=null){
				timeSeries=true;
				break;
			}
		}
		if(timeSeries){
			YesNoCancelDialog yncd=new YesNoCancelDialog(IJ.getInstance(), "Combine time series images?", "Combine time series images?");
			if(yncd.cancelPressed()) return;
			if(yncd.yesPressed()){
				for(int i=0; i<nImages; i++){
					if(titles[i].matches("Image000[0-9]_01\\.oif")){
						String basetitle=titles[i].substring(0,10);
						String cstr="  title="+titles[i]+"_CONCAT image1="+titles[i];
						int j=2;
						while(WindowManager.getImage(basetitle+(j<10?"0":"")+j+".oif")!=null){
							cstr+=" image"+j+"="+basetitle+(j<10?"0":"")+j+".oif";
							j++;
						}
						cstr=cstr+" image"+j+"=[-- None --]";
						if(j>2){
							ImagePlus imp1=WindowManager.getImage(titles[i]);
							int sls1=imp1.getNSlices(); int chs1=imp1.getNChannels();
							IJ.run("Concatenate...", cstr);
							IJ.run("Stack to Hyperstack...", "order=xyczt(default) channels="+chs1+" slices="+sls1+" frames="+(j-1)+" display=Composite");
							mosaicCombine();
							return;
						}
					}
				}
			}
		}
		boolean snake=false;
		int xmax=Math.sqrt(nImages)>0?((int)Math.sqrt(nImages)):1;
		int ymax=(int)Math.ceil((double)nImages/xmax);
		GenericDialog gd=new GenericDialog("Mosaic Combine or fix");
		gd.addMessage("There are "+nImages+" stacks open.");
		gd.addNumericField("Number of images in a row?", xmax, 0);
		gd.addNumericField("Number of images in a column?", ymax, 0);
		gd.addCheckbox("Are the rows each left-to-right? (or else they snake)", !snake);
		gd.addNumericField("X Overlap?", 40, 0);
		gd.addNumericField("Y Overlap?", 40, 0);
		gd.addChoice("Overlap method?", new String[]{"Max", "Average", "Transparent Zero", "Copy"}, "Copy");
		gd.showDialog();
		if(gd.wasCanceled()) return;
		xmax=(int)gd.getNextNumber();
		ymax=(int)gd.getNextNumber();
		snake=!gd.getNextBoolean();
		int xOverlap=(int)gd.getNextNumber();
		int yOverlap=(int)gd.getNextNumber();
		int overlapMethod=gd.getNextChoiceIndex();
		overlapMethod=(overlapMethod==0?Blitter.MAX:(overlapMethod==1?Blitter.AVERAGE:(overlapMethod==2?Blitter.COPY_ZERO_TRANSPARENT:Blitter.COPY)));
		mosaicCombine(xmax, ymax, snake, xOverlap, yOverlap, overlapMethod);
	}

	public static void mosaicCombine(int xmax, int ymax, boolean snake, int xOverlap, int yOverlap, int overlapMethod){
		ImagePlus imp=WindowManager.getCurrentImage();
		int nImages=WindowManager.getImageCount();
		String[] titles=WindowManager.getImageTitles();
		int width=imp.getWidth(), height=imp.getHeight(), chs=imp.getNChannels(), slices=imp.getNSlices(), frames=imp.getNFrames();
		int fwidth=width*xmax-xOverlap*(xmax-1);
		int fheight=height*ymax-yOverlap*(ymax-1);

		ImagePlus finalimp=IJ.createImage("HyperCombine", ""+imp.getBitDepth()+"-bit black composite-mode", fwidth, fheight, chs, slices, frames);
		double total=chs*slices*frames*nImages;
		double progress=0;
		finalimp.show();

		IJ.log("Combining "+nImages+" images into a "+xmax+"x"+ymax+" mosaic of size "+fwidth+"x"+fheight+" with overlap "+xOverlap+"x"+yOverlap+" using method "+(overlapMethod==Blitter.MAX?"Max":(overlapMethod==Blitter.AVERAGE?"Average":(overlapMethod==Blitter.COPY_ZERO_TRANSPARENT?"Transparent Zero":"Copy"))));
		for(int i=0; i<nImages; i++){
			ImagePlus imp1=WindowManager.getImage(titles[i]);
			int xoff=(i%xmax)*(width-xOverlap);
			int yoff=(i/xmax)*(height-yOverlap);
			if(snake && (i/xmax)%2==1) xoff=(xmax-1-(i%xmax))*(width-xOverlap);
			int iwid=width, ihei=height;
			if(overlapMethod==Blitter.COPY){
				if(!((i%xmax)==(xmax-1))) iwid=width-xOverlap;
				if(!((i/xmax)==(ymax-1))) ihei=height-yOverlap;
			}
			for(int c=1; c<=chs; c++){
				for(int z=1; z<=slices; z++){
					for(int t=1; t<=frames; t++){
						ImageProcessor ip1=imp1.getStack().getProcessor(imp1.getStackIndex(c, z, t));
						ImageProcessor ip2=finalimp.getStack().getProcessor(finalimp.getStackIndex(c, z, t));
						for(int y=0; y<ihei; y++){
							for(int x=0; x<iwid; x++){
								int value=ip1.get(x, y);
								if(!(overlapMethod==Blitter.COPY) && x<xOverlap && y<yOverlap && xoff>0 && yoff>0){
									if(overlapMethod==Blitter.MAX) value=Math.max(value, ip2.get(x+xoff, y+yoff));
									else if(overlapMethod==Blitter.AVERAGE) value=(value+ip2.get(x+xoff, y+yoff))/2;
									else if(overlapMethod==Blitter.COPY_ZERO_TRANSPARENT && value==0) value=ip2.get(x+xoff, y+yoff);
								}
								ip2.set(x+xoff, y+yoff, value);
							}
						}
						progress++;
						IJ.showProgress(progress/total);
					}
				}
			}
			if(i==0 && imp.isComposite()){
				ij.process.LUT[] luts=((CompositeImage)imp).getLuts();
				for(int c=0; c<chs; c++){
					((CompositeImage)finalimp).setLuts(luts);
				}
			}
			finalimp.updateAndDraw();
		}
	}

	public static void mosaicOverlapFix(){
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) return;
		int width=imp.getWidth(), height=imp.getHeight();
		GenericDialog gd=new GenericDialog("Mosaic Overlap Fix");
		gd.addNumericField("Number of images in a row?", (int)(width/EWIDTH), 0);
		gd.addNumericField("Number of images in a column?", (int)Math.max((height/EWIDTH),1), 0);
		gd.addNumericField("X Overlap?", 40, 0);
		gd.addNumericField("Y Overlap?", 40, 0);
		gd.showDialog();
		if(gd.wasCanceled()) return;
		int xCount=(int)gd.getNextNumber();
		int yCount=(int)gd.getNextNumber();
		int xOverlap=(int)gd.getNextNumber();
		int yOverlap=(int)gd.getNextNumber();
		mosaicOverlapFix(imp, xOverlap, yOverlap, xCount, yCount);
	}

	public static void mosaicOverlapFix(ImagePlus imp, int xOverlap, int yOverlap, int xCount, int yCount) {
		int width=imp.getWidth(), height=imp.getHeight();
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
					ostarty=(yi+1)*(iheight-yOverlap);
					oendy=ostarty+yOverlap;
				}
				for (int x=0; x<width; x++) {
					if(x==oendx && xi<(xCount-1)){
						xi++;
						ostartx=(xi+1)*(iwidth-xOverlap);
						oendx=ostartx+xOverlap;
					}
					if(x<ostartx && y<ostarty && xi==0 && yi==0){
							x=ostartx-1;
					} else if( (((x>=ostartx && x<oendx) && (x<(xCount*(iwidth-xOverlap))) && xi<(xCount-1)) || 
								((y>=ostarty && y<oendy) && (y<(yCount*(iheight-yOverlap))) && yi<(yCount-1))) && 
								x<(xCount*(iwidth-xOverlap)+xOverlap) && y<(yCount*(iheight-yOverlap)+yOverlap)) {
						int xc=x+(xOverlap*xi), yc=y+(yOverlap*yi);
						int xcf=xc, ycf=yc;
						if(x>=ostartx && x<oendx && xi<(xCount-1)) xcf+=xOverlap;
						if(y>=ostarty && y<oendy && yi<(yCount-1)) ycf+=yOverlap;
						int a=ip.get(xc,yc);
						int b=ip.get(xcf, ycf);
						int value=Math.max(a,b);
						ip.set(x, y, value);
					} else if(x<(xCount*(iwidth-xOverlap)+xOverlap) && y<(yCount*(iheight-yOverlap)+yOverlap)) {
						ip.set(x, y, ip.get(x+xOverlap*xi,y+yOverlap*yi));
					}else{
						ip.set(x, y, 0);
					}
				}
			}
		}
	imp.updateAndDraw();
	}

}
