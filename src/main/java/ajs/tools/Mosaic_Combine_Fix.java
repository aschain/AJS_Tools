package ajs.tools;
import ij.plugin.PlugIn;

import java.awt.AWTEvent;
import java.util.ArrayList;

import ij.*;
import ij.gui.*;
import ij.process.ImageProcessor;
import ij.process.Blitter;
import ij.process.FloatProcessor;
import ij.measure.ResultsTable;

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
		if(arg.contentEquals("mosaicOverlapFix")) {
			mosaicOverlapFix();
			return;
		}
		if(arg.contentEquals("mosaicBrightnessFix")) {
			mosaicBrightnessAdjust();
			return;
		}
		if(arg.contentEquals("generateMosaicOverlaps")) {
			generateMosaicOverlaps();
			return;
		}
		mosaicCombine();
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
		boolean hasMosaicOverlapInfo=false;
		String info=imp.getInfoProperty();
		if(info!=null && !info.isEmpty()){
			if(info.contains("MosaicOverlap: ")){
				hasMosaicOverlapInfo=true;
			}
		}
		if(!hasMosaicOverlapInfo){
			ResultsTable rt=ResultsTable.getResultsTable("Mosaic_Overlaps.csv");
			if(rt!=null && rt.getCounter()>0){
				hasMosaicOverlapInfo=true;
			}
		}
		GenericDialog gd=new GenericDialog("Mosaic Combine or fix");
		gd.addMessage("There are "+nImages+" stacks open.");
		gd.addNumericField("Number of images in a row?", xmax, 0);
		gd.addNumericField("Number of images in a column?", ymax, 0);
		gd.addCheckbox("Are the rows each left-to-right? (or else they snake)", !snake);
		gd.addNumericField("X Overlap?", 40, 0);
		gd.addNumericField("Y Overlap?", 40, 0);
		gd.addChoice("Overlap method?", new String[]{"Max", "Average", "Transparent Zero", "Copy"}, "Copy");
		if(hasMosaicOverlapInfo) gd.addCheckbox("Use existing mosaic overlap info?", true);
		gd.showDialog();
		if(gd.wasCanceled()) return;
		xmax=(int)gd.getNextNumber();
		ymax=(int)gd.getNextNumber();
		snake=!gd.getNextBoolean();
		int xOverlap=(int)gd.getNextNumber();
		int yOverlap=(int)gd.getNextNumber();
		int overlapMethod=gd.getNextChoiceIndex();
		boolean useExistingInfo=false;
		if(hasMosaicOverlapInfo) useExistingInfo=gd.getNextBoolean();
		overlapMethod=(overlapMethod==0?Blitter.MAX:(overlapMethod==1?Blitter.AVERAGE:(overlapMethod==2?Blitter.COPY_ZERO_TRANSPARENT:Blitter.COPY)));
		mosaicCombine(xmax, ymax, snake, xOverlap, yOverlap, overlapMethod, useExistingInfo);
	}

	public static void mosaicCombine(int xmax, int ymax, boolean snake, int xOverlap, int yOverlap, int overlapMethod, boolean useExistingInfo){
		ImagePlus imp=WindowManager.getCurrentImage();
		int nImages=WindowManager.getImageCount();
		String[] titles=WindowManager.getImageTitles();
		int width=imp.getWidth(), height=imp.getHeight(), chs=imp.getNChannels(), slices=imp.getNSlices(), frames=imp.getNFrames();

		//per-image placement, taken from recorded MosaicOverlap info instead of the uniform xOverlap/yOverlap grid when useExistingInfo is set
		int[] xPos=new int[nImages], yPos=new int[nImages];
		if(useExistingInfo){
			Integer[][] indexAt=new Integer[xmax][ymax];
			for(int i=0; i<nImages; i++){
				int xi=i%xmax, yi=i/xmax;
				if(yi>=ymax) continue;
				int col=(snake && (yi%2==1))?(xmax-1-xi):xi;
				indexAt[col][yi]=i;
			}
			for(int r=0; r<ymax; r++){
				for(int c=0; c<xmax; c++){
					Integer idx=indexAt[c][r];
					if(idx==null) continue;
					//ImagePlus curImp=WindowManager.getImage(titles[idx]);
					if(c==0 && r==0){
						xPos[idx]=0; yPos[idx]=0;
					}else if(c==0){
						Integer aboveIdx=indexAt[c][r-1];
						int[] off=null;
						if(aboveIdx!=null){
							off=getMosaicOverlapInfo(titles[aboveIdx], "bottom");
							//if(off==null){
							//	int[] inv=getMosaicOverlapInfo(titles[idx], "top");
							//	if(inv!=null) off=new int[]{-inv[0], -inv[1]};
							//}
						}
						if(off==null) off=new int[]{0, height-yOverlap};
						xPos[idx]=(aboveIdx!=null?xPos[aboveIdx]:0)+off[0];
						yPos[idx]=(aboveIdx!=null?yPos[aboveIdx]:0)+off[1];
					}else{
						Integer leftIdx=indexAt[c-1][r];
						int[] off=null;
						if(leftIdx!=null){
							off=getMosaicOverlapInfo(titles[leftIdx], "right");
							//if(off==null){
							//	int[] inv=getMosaicOverlapInfo(titles[idx], "left");
							//	if(inv!=null) off=new int[]{-inv[0], -inv[1]};
							//}
						}
						if(off==null) off=new int[]{width-xOverlap, 0};
						xPos[idx]=(leftIdx!=null?xPos[leftIdx]:0)+off[0];
						yPos[idx]=(leftIdx!=null?yPos[leftIdx]:0)+off[1];
					}
				}
			}
			int minX=0, minY=0;
			for(int i=0; i<nImages; i++){ minX=Math.min(minX, xPos[i]); minY=Math.min(minY, yPos[i]); }
			if(minX<0 || minY<0){
				for(int i=0; i<nImages; i++){ xPos[i]-=minX; yPos[i]-=minY; }
			}
		}

		int fwidth, fheight;
		if(useExistingInfo){
			int maxX=0, maxY=0;
			for(int i=0; i<nImages; i++){
				maxX=Math.max(maxX, xPos[i]+width);
				maxY=Math.max(maxY, yPos[i]+height);
			}
			fwidth=maxX; fheight=maxY;
		}else{
			fwidth=width*xmax-xOverlap*(xmax-1);
			fheight=height*ymax-yOverlap*(ymax-1);
		}

		ImagePlus finalimp=IJ.createImage("HyperCombine", ""+imp.getBitDepth()+"-bit black"+(chs>1?" composite-mode":""), fwidth, fheight, chs, slices, frames);
		double total=chs*slices*frames*nImages;
		double progress=0;
		finalimp.setCalibration(imp.getCalibration());
		finalimp.show();
		String finalinfo="";
		
		if(imp.isComposite()){
			ij.process.LUT[] luts=((CompositeImage)imp).getLuts();
			for(int c=0; c<chs; c++){
				((CompositeImage)finalimp).setLuts(luts);
			}
		}
		String infostring="Combining "+nImages+" images into a "+xmax+"x"+ymax+" mosaic of size "+fwidth+"x"+fheight;
		if(useExistingInfo) infostring+=" using existing mosaic overlap info and";
		else infostring+=" with overlap "+xOverlap+"x"+yOverlap;
		infostring+=" using method "+(overlapMethod==Blitter.MAX?"Max":(overlapMethod==Blitter.AVERAGE?"Average":(overlapMethod==Blitter.COPY_ZERO_TRANSPARENT?"Transparent Zero":"Copy")));
		IJ.log(infostring);
		int xoff=0, yoff=0;
		for(int i=0; i<nImages; i++){
			ImagePlus imp1=WindowManager.getImage(titles[i]);
			if(useExistingInfo){
				xoff=xPos[i];
				yoff=yPos[i];
				IJ.log("Adding image "+titles[i]+" at "+xoff+", "+yoff);
			}else{
				xoff=(i%xmax)*(width-xOverlap);
				yoff=(i/xmax)*(height-yOverlap);
				if(snake && (i/xmax)%2==1) xoff=(xmax-1-(i%xmax))*(width-xOverlap);
			}
			int iwid=width, ihei=height;
			if(overlapMethod==Blitter.COPY && !useExistingInfo){
				if(!((i%xmax)==(xmax-1))) iwid=width-xOverlap;
				if(!((i/xmax)==(ymax-1))) ihei=height-yOverlap;
			}
			for(int c=1; c<=chs; c++){
				for(int z=1; z<=slices; z++){
					for(int t=1; t<=frames; t++){
						int index=imp1.getStackIndex(c, z, t);
						ImageProcessor ip1=imp1.getStack().getProcessor(index);
						ImageProcessor ip2=finalimp.getStack().getProcessor(index);
						for(int y=0; y<ihei; y++){
							for(int x=0; x<iwid; x++){
								int value=ip1.get(x, y);
								if(!(overlapMethod==Blitter.COPY) ){
									int oldval=ip2.get(x+xoff, y+yoff);
									if(overlapMethod==Blitter.MAX) value=Math.max(value, oldval);
									else if(overlapMethod==Blitter.AVERAGE && oldval>0) value=(value+oldval)/2;
									else if(overlapMethod==Blitter.COPY_ZERO_TRANSPARENT && oldval>0) value=oldval;
								}
								ip2.set(x+xoff, y+yoff, value);
							}
						}
						progress++;
						IJ.showProgress(progress/total);
						if(i==0) finalimp.getStack().setSliceLabel(imp1.getStack().getSliceLabel(index), index);
						if(i==(nImages-1)){
							String slinfo=imp1.getStack().getSliceLabel(index);
							String fslinfo=finalimp.getStack().getSliceLabel(index);
							if(slinfo!=null && !slinfo.isEmpty() && fslinfo!=null && !fslinfo.isEmpty()){
								if(!fslinfo.endsWith("\n")) fslinfo+="\n";
								if(slinfo.contains("Starttime: ")) {
									int[] endst=AJ_Utils.getInfoLineInts(slinfo, new String[]{"Starttime: "});
									if(endst!=null && endst.length>0) fslinfo+="Final image Starttime: "+endst[0]+"\n";
								}
								finalimp.getStack().setSliceLabel(fslinfo+slinfo, index);
							}
						}
					}
				}
			}
			String info=imp1.getInfoProperty();
			if(info!=null && !info.isEmpty()){
				if(!info.endsWith("\n")) info+="\n";
				finalinfo+=info;
			}
			finalimp.updateAndDraw();
		}
		finalimp.setProperty("Info", finalinfo);
	}

	public static void generateMosaicOverlaps(){
		ImagePlus imp=WindowManager.getCurrentImage();
		int nImages=WindowManager.getImageCount();
		String[] titles=WindowManager.getImageTitles();
		int width=imp.getWidth();
		boolean snake=false;
		int xmax=Math.sqrt(nImages)>0?((int)Math.sqrt(nImages)):1;
		int ymax=(int)Math.ceil((double)nImages/xmax);
		boolean writeToInfo=false;
		boolean findBrightestSlice=false;
		GenericDialog gd=new GenericDialog("Mosaic Overlap Generator");
		gd.addMessage("There are "+nImages+" stacks open.");
		gd.addNumericField("Number of images in a row?", xmax, 0);
		gd.addNumericField("Number of images in a column?", ymax, 0);
		gd.addCheckbox("Are the rows each left-to-right? (or else they snake)", !snake);
		gd.addNumericField("XY Overlap?", (int)(width*0.05), 0);
		gd.addCheckbox("Write to Image Info?", writeToInfo);
		if(imp.getNSlices()>1)gd.addCheckbox("Find Brightest Slice?", findBrightestSlice);
		gd.showDialog();
		if(gd.wasCanceled()) return;
		xmax=(int)gd.getNextNumber();
		ymax=(int)gd.getNextNumber();
		snake=!gd.getNextBoolean();
		int xyOverlap=(int)gd.getNextNumber();
		writeToInfo=gd.getNextBoolean();
		if(imp.getNSlices()>1)findBrightestSlice=gd.getNextBoolean();

		//map each image index to its physical grid column/row, accounting for snake ordering
		Integer[][] indexAt=new Integer[xmax][ymax];
		int[] physCol=new int[nImages], physRow=new int[nImages];
		for(int i=0; i<nImages; i++){
			int xi=i%xmax, yi=i/xmax;
			if(yi>=ymax) continue;
			int col=(snake && (yi%2==1))?(xmax-1-xi):xi;
			physCol[i]=col; physRow[i]=yi;
			indexAt[col][yi]=i;
		}

		ResultsTable rt=new ResultsTable();
		java.util.Set<String> compared=new java.util.HashSet<String>();
		for(int i=0; i<nImages; i++){
			int col=physCol[i], row=physRow[i];
			int[][] dirs={{col+1,row},{col-1,row},{col,row+1},{col,row-1}};
			String[] dirNames={"right","left","bottom","top"};
			for(int d=0; d<dirs.length; d++){
				int nc=dirs[d][0], nr=dirs[d][1];
				if(nc<0 || nc>=xmax || nr<0 || nr>=ymax) continue;
				Integer n=indexAt[nc][nr];
				if(n==null) continue;
				String pairKey=Math.min(i,n)+"_"+Math.max(i,n);
				if(compared.contains(pairKey)) continue;
				compared.add(pairKey);
				ImagePlus imp1=WindowManager.getImage(titles[i]);
				ImagePlus imp2=WindowManager.getImage(titles[n]);
				if(findBrightestSlice){
					Roi[] rois=getOverlapCropRegions(imp1, imp2, dirNames[d], xyOverlap);
					if(rois==null) continue;
					int maxSlice=getBrightestSlice(imp1, rois[0]);
					imp1.setZ(maxSlice);
					imp1.updateAndDraw();
					imp2.setZ(maxSlice);
					imp2.updateAndDraw();
				}
				resolveMosaicOverlap(imp1, imp2, dirNames[d], xyOverlap, rt, writeToInfo);
			}
		}
		rt.show("Mosaic_Overlaps.csv");
	}

	private static int getBrightestSlice(ImagePlus imp, Roi roi){
		if(roi!=null) imp.setRoi(roi);
		else imp.resetRoi();
		double maxMean=0; int maxSlice=0;
		for(int z=1; z<=imp.getNSlices(); z++){
			ImageProcessor ip=imp.getStack().getProcessor(imp.getStackIndex(imp.getC(), z, imp.getT()));
			double mean=ij.process.ImageStatistics.getStatistics(ip, ij.process.ImageStatistics.MEAN, null).mean;
			if(mean>maxMean){ maxMean=mean; maxSlice=z; }
		}
		return maxSlice;
	}

	private static ImageProcessor cropRegion(ImagePlus src, int x0, int y0, int w, int h){
		int chs=src.getNChannels();
		ImageStack stack=null;
		for(int c=1; c<=chs; c++){
			if(!src.isComposite()) c=src.getC();
			ImageProcessor srcip=src.getStack().getProcessor(src.getStackIndex(c, src.getZ(), src.getT()));
			ImageProcessor dstip=srcip.createProcessor(w, h);
			int sw=srcip.getWidth(), sh=srcip.getHeight();
			for(int y=0; y<h; y++){
				int sy=y0+y;
				if(sy<0 || sy>=sh) continue;
				for(int x=0; x<w; x++){
					int sx=x0+x;
					if(sx<0 || sx>=sw) continue;
					dstip.set(x, y, srcip.get(sx, sy));
				}
			}
			if(!src.isComposite() || chs==1) return dstip;
			if(c==1) stack=new ImageStack(w, h);
			stack.addSlice("Channel " + c, dstip);
		}
		ImagePlus dst=new ImagePlus("Cropped Region", stack);
		dst.setDimensions(chs, 1, 1);
		CompositeImage dstc=new CompositeImage(dst, CompositeImage.COMPOSITE);
		//getImage() renders the current composite (all channels combined via their LUTs) as a flat RGB image
		return new ij.process.ColorProcessor(dstc.getImage());
	}

	private static Roi[] getOverlapCropRegions(ImagePlus imp1, ImagePlus imp2, String direction, int overlap){
		int margin=Math.max(50, overlap/2);
		int w1=imp1.getWidth(), h1=imp1.getHeight();
		int w2=imp2.getWidth(), h2=imp2.getHeight();
		int canvasW, canvasH, o1x, o1y, o2x, o2y;
		switch(direction){
			case "right":
				canvasW=overlap+2*margin; canvasH=h1+2*margin;
				o1x=w1-overlap-margin; o1y=-margin;
				o2x=-margin; o2y=-margin;
				break;
			case "left":
				canvasW=overlap+2*margin; canvasH=h1+2*margin;
				o1x=-margin; o1y=-margin;
				o2x=(w2-overlap)-margin; o2y=-margin;
				break;
			case "bottom":
				canvasW=w1+2*margin; canvasH=overlap+2*margin;
				o1x=-margin; o1y=h1-overlap-margin;
				o2x=-margin; o2y=-margin;
				break;
			case "top":
				canvasW=w1+2*margin; canvasH=overlap+2*margin;
				o1x=-margin; o1y=-margin;
				o2x=-margin; o2y=(h2-overlap)-margin;
				break;
			default: return null;
		}
		Roi[] rois=new Roi[2];
		rois[0]=new Roi(o1x, o1y, canvasW, canvasH);
		rois[1]=new Roi(o2x, o2y, canvasW, canvasH);
		return rois;
	}

	private static void resolveMosaicOverlap(ImagePlus imp1, ImagePlus imp2, String direction, int overlap, ResultsTable rt, boolean writeToInfo){
		Roi[] rois=getOverlapCropRegions(imp1, imp2, direction, overlap);
		if(rois==null) return;

		int o1x=(int)rois[0].getBounds().getX();
		int o1y=(int)rois[0].getBounds().getY();
		int canvasW=(int)rois[0].getBounds().getWidth();
		int canvasH=(int)rois[0].getBounds().getHeight();

		int o2x=(int)rois[1].getBounds().getX();
		int o2y=(int)rois[1].getBounds().getY();

		final ImageProcessor[] ips=
		{
			cropRegion(imp1, o1x, o1y, canvasW, canvasH),
			cropRegion(imp2, o2x, o2y, canvasW, canvasH)
		};

		final int[] shift={0,0};
		if(rt.getCounter()>0){
			int n=0;
			for(int i=0;i<rt.getCounter();i++){
				String dir=rt.getStringValue("Direction", i);
				if(dir.equals(direction)){
					shift[0]+=(int)rt.getValue("X", i);
					shift[1]+=(int)rt.getValue("Y", i);
					n++;
				}
			}
			shift[0]/=n; shift[1]/=n;
			IJ.showStatus("Using previous shift: "+shift[0]+", "+shift[1]);
		}
		ImageStack stack=new ImageStack(canvasW, canvasH);
		stack.addSlice(imp1.getTitle(), ips[0]);
		ImageProcessor shifted=ips[1].duplicate();
		shifted.translate(shift[0], shift[1]);
		stack.addSlice(imp2.getTitle(), shifted);
		final ImagePlus tempImp=new ImagePlus("Overlap: "+imp1.getTitle()+" <-> "+imp2.getTitle()+" ("+direction+")", stack);
		tempImp.show();
		java.awt.Rectangle screen1=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0].getDefaultConfiguration().getBounds();
		java.awt.Window tempWin=tempImp.getWindow();
		tempWin.setLocation(screen1.x+(screen1.width-tempWin.getWidth())/2, screen1.y+(screen1.height-tempWin.getHeight())/2);
		
		final ImageCanvas canvas=tempImp.getWindow().getCanvas();
		canvas.requestFocus();
		IJ.log("Beginning flipping...");

		final boolean[] locked={false};
		final javax.swing.Timer flipTimer=new javax.swing.Timer(600, e -> {
			java.awt.EventQueue.invokeLater(() -> {
				int cur=tempImp.getCurrentSlice();
				tempImp.setSlice(cur==1?2:1);
				tempImp.updateAndDraw();
			});
		});
		flipTimer.start();

		java.awt.event.KeyAdapter listener=new java.awt.event.KeyAdapter(){
			@Override
			public void keyPressed(java.awt.event.KeyEvent e){
				int code=e.getKeyCode();
				boolean changed=false;
				if(code==java.awt.event.KeyEvent.VK_LEFT){ shift[0]--; changed=true; }
				else if(code==java.awt.event.KeyEvent.VK_RIGHT){ shift[0]++; changed=true; }
				else if(code==java.awt.event.KeyEvent.VK_UP){ shift[1]--; changed=true; }
				else if(code==java.awt.event.KeyEvent.VK_DOWN){ shift[1]++; changed=true; }
				else if(code==java.awt.event.KeyEvent.VK_ENTER){ locked[0]=true; }
				else if(e.getKeyChar()==',' || e.getKeyChar()=='.'){
					int z=imp1.getZ();
					if(e.getKeyChar()==','){ z--; }
					else{ z++; }
					if(z>=1 && z<=imp1.getNSlices()){
						imp1.setZ(z); imp2.setZ(z);
						ips[0]=cropRegion(imp1, o1x, o1y, canvasW, canvasH);
						ips[1]=cropRegion(imp2, o2x, o2y, canvasW, canvasH);
						tempImp.getStack().setPixels(ips[0].getPixels(), 1);
						ImageProcessor shifted=ips[1].duplicate();
						if(shift[0]!=0 || shift[1]!=0){
							shifted.translate(shift[0], shift[1]);
						}
						tempImp.getStack().setPixels(shifted.getPixels(), 2);
						tempImp.updateAndDraw();
					}
				}
				if(changed){
					ImageProcessor shifted=ips[1].duplicate();
					shifted.translate(shift[0], shift[1]);
					tempImp.getStack().setPixels(shifted.getPixels(), 2);
					tempImp.updateAndDraw();
					java.awt.EventQueue.invokeLater(() -> {
						IJ.log("\\Update: "+imp1.getTitle()+" <-> "+imp2.getTitle()+" ("+direction+")  Shift: "+shift[0]+", "+shift[1]);
					});
				}
				e.consume();
			}
		};
		canvas.addKeyListener(listener);

		while(!locked[0]){
			try{ Thread.sleep(50); }catch(InterruptedException ie){ Thread.currentThread().interrupt(); break; }
		}
		flipTimer.stop();
		canvas.removeKeyListener(listener);
		tempImp.changes=false;
		tempImp.close();

		int dx=shift[0], dy=shift[1];
		int x, y, w1=imp1.getWidth(), h1=imp1.getHeight(), w2=imp2.getWidth(), h2=imp2.getHeight();
		switch(direction){
			case "right": x=w1-overlap+dx; y=dy; break;
			case "left": x=overlap-w2+dx; y=dy; break;
			case "bottom": x=dx; y=h1-overlap+dy; break;
			case "top": x=dx; y=overlap-h2+dy; break;
			default: x=dx; y=dy;
		}

		rt.incrementCounter();
		rt.addValue("Image1", imp1.getTitle());
		rt.addValue("Image2", imp2.getTitle());
		rt.addValue("Direction", direction);
		rt.addValue("X", x);
		rt.addValue("Y", y);

		if(writeToInfo) addMosaicOverlapInfo(imp1, direction, x, y);
	}

	private static void addMosaicOverlapInfo(ImagePlus imp, String direction, int x, int y){
		String info=imp.getInfoProperty();
		if(info==null) info="";
		String key="MosaicOverlap: "+direction+" ";
		String newline=key+x+", "+y;
		String[] lines=info.split("\n");
		StringBuilder sb=new StringBuilder();
		boolean replaced=false;
		for(String line: lines){
			if(line.isEmpty()) continue;
			if(line.startsWith(key)){
				sb.append(newline).append("\n");
				replaced=true;
			}else{
				sb.append(line).append("\n");
			}
		}
		if(!replaced) sb.append(newline).append("\n");
		imp.setProperty("Info", sb.toString());
	}

	public static int[] getMosaicOverlapInfo(String imageTitle, String direction){
		ImagePlus imp=WindowManager.getImage(imageTitle);
		if(imp==null) return null;
		int[] overlap=getMosaicOverlapInfo(imp, direction);
		if(overlap!=null) return overlap;
		ResultsTable rt=ResultsTable.getResultsTable("Mosaic_Overlaps.csv");
		if(rt==null) return null;
		for(int i=0; i<rt.getCounter(); i++){
			String im1=rt.getStringValue("Image1", i);
			String dir=rt.getStringValue("Direction", i);
			if(dir.equals(direction) && (im1.equals(imageTitle))){
				int x=(int)rt.getValue("X", i);
				int y=(int)rt.getValue("Y", i);
				return new int[]{x, y};
			}
		}
		return null;
	}

	/* 
	private static int[] getOppositeMosaicInfo(String direction, int x, int y, int imageWidth, int imageHeight){
		switch(direction){
			case "right": return new int[]{-x, -y};
			case "left": return new int[]{-x, -y};
			case "bottom": return new int[]{-x, -y};
			case "top": return new int[]{-x, -y};
			default: return null;
		}
	}

	private static String getOppositeDirection(String direction){
		switch(direction){
			case "right": return "left";
			case "left": return "right";
			case "bottom": return "top";
			case "top": return "bottom";
			default: return null;
		}
	}
*/

	public static int[] getMosaicOverlapInfo(ImagePlus imp, String direction){
		String info=imp.getInfoProperty();
		if(info==null) return null;
		String key="MosaicOverlap: "+direction+" ";
		String[] lines=info.split("\n");
		for(String line: lines){
			if(line.startsWith(key)){
				String[] parts=line.substring(key.length()).split(",");
				if(parts.length!=2) return null;
				try{
					int x=Integer.parseInt(parts[0].trim());
					int y=Integer.parseInt(parts[1].trim());
					return new int[]{x, y};
				}catch(NumberFormatException nfe){ return null; }
			}
		}
		return null;
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

	public static void mosaicBrightnessAdjust(){
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage(); return;}
		ArrayList<String> bmaps=new ArrayList<String>();
		String[] titles=WindowManager.getImageTitles();
		bmaps.add("None");
		for(int i=0; i<titles.length; i++){
			if(titles[i].contains("MosaicCondensed") || titles[i].contains("MosaicBrightnessMap")){
				bmaps.add(titles[i]);
			}
		}
		boolean isSingleImageMosaic=imp.getWidth()>2048;
		
		int xRepeatNumber=(int)Math.round((double)imp.getWidth()/EWIDTH);
		int yRepeatNumber=(int)Math.round((double)imp.getHeight()/EWIDTH);
		int oImageWidth=EWIDTH;
		int oImageHeight=EWIDTH;
		if(!isSingleImageMosaic){
			oImageWidth=imp.getWidth();
			oImageHeight=imp.getHeight();
		}
		String[] choices=new String[]{"Current Image is a Mosaic", "All Open Images will be the Mosaic"};
		GenericDialog gd=new GenericDialog("Mosaic Brightness Adjust");
		gd.addMessage("Current image: "+imp.getTitle()+" ("+imp.getWidth()+"x"+imp.getHeight()+")");
		gd.addChoice("Image(s) to adjust?", choices, choices[isSingleImageMosaic?0:1]);
		gd.addNumericField("Number of images in a row (x)?", xRepeatNumber, 0);
		gd.addNumericField("Number of images in a column (y)?", yRepeatNumber, 0);
		gd.addNumericField("Width of each image in pixels:", oImageWidth, 0);
		gd.addNumericField("Height of each image in pixels:", oImageHeight, 0);
		if(imp.getNChannels()>1) gd.addCheckbox("Calculate from all channels?", true);
		if(imp.getNSlices()>1) gd.addCheckbox("Calculate from all slices?", true);
		if(imp.getNFrames()>1) gd.addCheckbox("Calculate from all frames?", true);
		if(imp.getNChannels()>1) gd.addCheckbox("Apply to all channels?", true);
		if(imp.getNSlices()>1) gd.addCheckbox("Apply to all slices?", true);
		if(imp.getNFrames()>1) gd.addCheckbox("Apply to all frames?", true);
		if(bmaps.size()>1) gd.addChoice("Use Brightness Map Image?", bmaps.toArray(new String[bmaps.size()]), bmaps.get(0));
		gd.addDialogListener(new DialogListener() {
			public boolean dialogItemChanged(GenericDialog gdl, AWTEvent e){
				boolean isSingleImageMosaic=(gdl.getNextChoiceIndex()==0);
					int xRepeatNumber=(int)gdl.getNextNumber();
					int yRepeatNumber=(int)gdl.getNextNumber();
				if(isSingleImageMosaic){
					((java.awt.TextField)gdl.getNumericFields().get(0)).setEnabled(true);
					((java.awt.TextField)gdl.getNumericFields().get(1)).setEnabled(true);
					if(e.getSource()==gdl.getNumericFields().get(0) || e.getSource()==gdl.getNumericFields().get(1)){
						int owidth=2;
						for(int i=0; i<13; i++){
							if(owidth==1024) owidth=800;
							else if(owidth==1600) owidth=1024;
							if(owidth*xRepeatNumber>imp.getWidth()) break;
							owidth*=2;
						}
						((java.awt.TextField)gdl.getNumericFields().get(2)).setText(""+owidth);
						int oheight=2;
						for(int i=0; i<13; i++){
							if(oheight==1024) oheight=800;
							else if(oheight==1600) oheight=1024;
							if(oheight*yRepeatNumber>imp.getHeight()) break;
							oheight*=2;
						}
						((java.awt.TextField)gdl.getNumericFields().get(3)).setText(""+oheight);
					}
				}else{
					((java.awt.TextField)gdl.getNumericFields().get(0)).setEnabled(false);
					((java.awt.TextField)gdl.getNumericFields().get(1)).setEnabled(false);
				}
				gdl.resetCounters();
				return true;
			}
		});
		
		if(isSingleImageMosaic){
			((java.awt.TextField)gd.getNumericFields().get(0)).setEnabled(true);
			((java.awt.TextField)gd.getNumericFields().get(1)).setEnabled(true);
		}else{
			((java.awt.TextField)gd.getNumericFields().get(0)).setEnabled(false);
			((java.awt.TextField)gd.getNumericFields().get(1)).setEnabled(false);
		}
		gd.showDialog();
		if(gd.wasCanceled()) return;
		isSingleImageMosaic=(gd.getNextChoiceIndex()==0);
		xRepeatNumber=(int)gd.getNextNumber();
		yRepeatNumber=(int)gd.getNextNumber();
		oImageWidth=(int)gd.getNextNumber();
		oImageHeight=(int)gd.getNextNumber();
		boolean[] calcAll=new boolean[6];
		if(imp.getNChannels()>1) calcAll[0]=gd.getNextBoolean();
		if(imp.getNSlices()>1) calcAll[1]=gd.getNextBoolean();
		if(imp.getNFrames()>1) calcAll[2]=gd.getNextBoolean();
		if(imp.getNChannels()>1) calcAll[3]=gd.getNextBoolean();
		if(imp.getNSlices()>1) calcAll[4]=gd.getNextBoolean();
		if(imp.getNFrames()>1) calcAll[5]=gd.getNextBoolean();
		ImagePlus bmap=null;
		if(bmaps.size()>1) {
			String title=gd.getNextChoice();
			if(!title.equals("None"))
				bmap=WindowManager.getImage(title);
		}
		if(isSingleImageMosaic)
			mosaicBrightnessFix(imp, xRepeatNumber, yRepeatNumber, oImageWidth, oImageHeight, calcAll, bmap);
		else
			mosaicBrightnessFixImages(oImageWidth, oImageHeight, calcAll, bmap);
	}

	static final int CALC_CH=0, CALC_SL=1, CALC_FR=2, APPLY_CH=3, APPLY_SL=4, APPLY_FR=5;

	public static void mosaicBrightnessFix(ImagePlus imp, int xRepeatNumber, int yRepeatNumber, int oImageWidth, int oImageHeight, boolean[] calcAll, ImagePlus bmap){
		if(imp==null) {IJ.noImage(); return;}
		Roi roi = imp.getRoi();
		if(roi==null) roi=new Roi(0,0,imp.getWidth(),imp.getHeight());
		int w=imp.getWidth();
		int h=imp.getHeight();
		int xoverlap=((oImageWidth*xRepeatNumber)-w)/(xRepeatNumber-1);
		int yoverlap=((oImageHeight*yRepeatNumber)-h)/(yRepeatNumber-1);
		int wov=oImageWidth-xoverlap;
		int hov=oImageHeight-yoverlap;
		IJ.log("MBA Start with "+oImageWidth+"x"+oImageHeight+" images for "+xRepeatNumber+" repeats, overlap: "+xoverlap+", "+wov+" pixels per image");

		ImageProcessor bmapip=null;
		ImagePlus calcimp=imp;

		if(bmap==null){
			
			bmapip=new FloatProcessor(oImageWidth, oImageHeight);

			if(calcAll[CALC_SL]){
				calcimp=AJ_Misc_Plugins.ZProjector(calcimp, ij.plugin.ZProjector.MAX_METHOD, true, 1, imp.getNSlices());
				if(!calcAll[CALC_FR]) calcimp.setT(imp.getT());
				if(!calcAll[CALC_CH]) calcimp.setC(imp.getC());
				IJ.log("MBA ZProjector for calcuation");
			}
			if(calcAll[CALC_FR]){
				calcimp=AJ_Misc_Plugins.TProjector(calcimp, ij.plugin.ZProjector.AVG_METHOD, true, 1, imp.getNFrames());
				if(!calcAll[CALC_SL]) calcimp.setZ(imp.getZ());
				if(!calcAll[CALC_CH]) calcimp.setC(imp.getC());
				IJ.log("MBA TProjector for calcuation");
			}
			if(calcAll[CALC_CH]){
				calcimp=AJ_Misc_Plugins.CProjector(calcimp, ij.plugin.ZProjector.SUM_METHOD, true, true, 1, imp.getNChannels());
				if(!calcAll[CALC_SL]) calcimp.setZ(imp.getZ());
				if(!calcAll[CALC_FR]) calcimp.setT(imp.getT());
				IJ.log("MBA CProjector for calcuation");
			}

			ImageProcessor ip=calcimp.getProcessor();
			boolean is32bit=calcimp.getBitDepth()==32;
			double max=0;
			for(int y=0; y<hov;y++){
				for(int x=0; x<wov; x++){
					double a=0;
					int n=0;
					for(int xr=0;xr<xRepeatNumber; xr++){
						int x0=x+(xr*wov);
						for(int yr=0;yr<yRepeatNumber; yr++){
							int y0=y+(yr*hov);
							if(is32bit){
								a+=ip.getf(x0,y0);
							}else{
								a+=(double)ip.get(x0,y0);
							}
							n++;
						}
					}
					a/=n;
					max=Math.max(max, a);
					bmapip.setf(x,y, (float)a);
				}
			}

			for(int y=0; y<hov;y++){
				for(int x=0; x<wov; x++){
					float a=bmapip.getf(x,y);
					if(a>0) bmapip.setf(x,y, (float)(a/max));
				}
			}

			//smooth the brightness map to reduce noise before it's used as a divisor
			new ij.plugin.filter.GaussianBlur().blurGaussian(bmapip, 20);
			new ij.plugin.filter.GaussianBlur().blurGaussian(bmapip, 20);
			new ij.plugin.filter.GaussianBlur().blurGaussian(bmapip, 20);
			
			bmap= new ImagePlus("MosaicBrightnessMap", bmapip);
			bmap.show();
			if(calcimp!=imp)calcimp.close();
		}else bmapip=bmap.getProcessor();
		
		IJ.log("MBA Adjusting...");
		int slend=calcAll[APPLY_SL]?imp.getNSlices():imp.getZ(), slst=calcAll[APPLY_SL]?1:imp.getZ();
		int frend=calcAll[APPLY_FR]?imp.getNFrames():imp.getT(), frst=calcAll[APPLY_FR]?1:imp.getT();
		int chend=calcAll[APPLY_CH]?imp.getNChannels():imp.getC(), chst=calcAll[APPLY_CH]?1:imp.getC();
		double progress=0, total=(double)(slend-slst+1)*(frend-frst+1)*(chend-chst+1);
		IJ.log("MBA Adjusting "+(slend-slst+1)+" slices, "+(frend-frst+1)+" frames, "+(chend-chst+1)+" channels");
		boolean is32bit=imp.getBitDepth()==32;
		for(int fr=frst; fr<=frend; fr++){
			for(int sl=slst; sl<=slend; sl++){
				for(int ch=chst; ch<=chend; ch++){
					ImageProcessor sip=imp.getStack().getProcessor(imp.getStackIndex(ch,sl,fr));
					if(is32bit){
						for(int x=0; x<(wov*xRepeatNumber); x++){
							for(int y=0; y<imp.getHeight(); y++){
								float value=sip.getf(x,y);
								value=(float)(value/(bmapip.getf(x%wov,y%hov)));
								sip.setf(x,y, value);
							}
						}
					}else{
						for(int x=0; x<(wov*xRepeatNumber); x++){
							for(int y=0; y<imp.getHeight(); y++){
								int value=sip.get(x,y);
								value=(int)(value/(bmapip.getf(x%wov,y%hov)));
								sip.set(x,y, value);
							}
						}
					}
					progress++;
					IJ.showProgress(progress/total);
				}
			}
		}
		imp.updateAndDraw();
		IJ.log("MBA Complete");
	}

	public static double[] getLineAverage(double[] values, int lineAveN){
		for(int i=0; i<values.length; i++){
			double val=values[i];
			for(int j=1; j<lineAveN; j++){
				int idx=i+j;
				if(idx>=values.length) idx=values.length-1;
				val+=values[idx];
			}
			values[i]=val/(double)lineAveN;
		}
		return values;
	}

	public static void mosaicBrightnessFixImages(int imageWidth, int imageHeight, boolean[] calcAll, ImagePlus bmap){
		int nImages=WindowManager.getImageCount();
		String[] titles=WindowManager.getImageTitles();
		ImageProcessor bmapip=null;
		double max=0;
		if(bmap==null) {
			bmapip=new FloatProcessor(imageWidth, imageHeight);
			for(int i=0; i<nImages; i++){
				ImagePlus imp=WindowManager.getImage(titles[i]);
				if(imp==null) continue;
				ImagePlus calcimp=imp;
				if(calcAll[CALC_SL]){
					calcimp=AJ_Misc_Plugins.ZProjector(calcimp, ij.plugin.ZProjector.MAX_METHOD, true, 1, calcimp.getNSlices());
					if(!calcAll[CALC_FR]) calcimp.setT(imp.getT());
					if(!calcAll[CALC_CH]) calcimp.setC(imp.getC());
					IJ.log("MBA ZProjector for calcuation");
				}
				if(calcAll[CALC_FR]){
					calcimp=AJ_Misc_Plugins.TProjector(calcimp, ij.plugin.ZProjector.AVG_METHOD, true, 1, calcimp.getNFrames());
					if(!calcAll[CALC_SL]) calcimp.setZ(imp.getZ());
					if(!calcAll[CALC_CH]) calcimp.setC(imp.getC());
					IJ.log("MBA TProjector for calcuation");
				}
				if(calcAll[CALC_CH]){
					calcimp=AJ_Misc_Plugins.CProjector(calcimp, ij.plugin.ZProjector.SUM_METHOD, true, true, 1, calcimp.getNChannels());
					if(!calcAll[CALC_SL]) calcimp.setZ(imp.getZ());
					if(!calcAll[CALC_FR]) calcimp.setT(imp.getT());
					IJ.log("MBA CProjector for calcuation");
				}
				int width=calcimp.getWidth(), height=calcimp.getHeight();
				boolean is32bit=calcimp.getBitDepth()==32;
				for(int y=0; y<height;y++){
					for(int x=0; x<width; x++){
						double a=0;
						if(is32bit){
							a=calcimp.getProcessor().getf(x,y);
						}else{
							a=(double)calcimp.getProcessor().get(x,y);
						}
						bmapip.setf(x,y, (float)(bmapip.getf(x,y)+a));
						if(i==(nImages-1)) {
							bmapip.setf(x,y, (float)(bmapip.getf(x,y)/(double)nImages));
							max=Math.max(max, a);
						}
					}
				}
				if(calcimp!=imp)calcimp.close();
			}
			for(int y=0; y<imageHeight;y++){
				for(int x=0; x<imageWidth; x++){
					float a=bmapip.getf(x,y);
					bmapip.setf(x,y, (float)(a/max));
				}
			}

		}else bmapip=bmap.getProcessor();
		
		for(int i=0; i<nImages; i++){
			ImagePlus imp=WindowManager.getImage(titles[i]);
			if(imp==bmap) continue;
			if(imp==null) continue;
			int width=imp.getWidth(), height=imp.getHeight();
			boolean is32bit=imp.getBitDepth()==32;
			double factorX=1.0, factorY=1.0;
			factorX=(double)width/(double)bmapip.getWidth();
			factorY=(double)height/(double)bmapip.getHeight();
			int slend=calcAll[APPLY_SL]?imp.getNSlices():imp.getZ(), slst=calcAll[APPLY_SL]?1:imp.getZ();
			int frend=calcAll[APPLY_FR]?imp.getNFrames():imp.getT(), frst=calcAll[APPLY_FR]?1:imp.getT();
			int chend=calcAll[APPLY_CH]?imp.getNChannels():imp.getC(), chst=calcAll[APPLY_CH]?1:imp.getC();
			double progress=0, total=(double)(slend-slst+1)*(frend-frst+1)*(chend-chst+1);
			for(int t=frst; t<=frend; t++){
				for(int z=slst; z<=slend; z++){
					for(int c=chst; c<=chend; c++){
						ImageProcessor ip=imp.getStack().getProcessor(imp.getStackIndex(c,z,t));
						for(int x=0; x<width; x++){
							for(int y=0; y<height; y++){
								float value;
								if(is32bit){
									value=ip.getf(x,y);
								}else{
									value=(float)ip.get(x,y);
								}
								float bval=bmapip.getf((int)(x*factorX),(int)(y*factorY));
								value/=bval;
								if(is32bit){
									ip.setf(x,y, value);
								}else{
									ip.set(x,y, (int)value);
								}
							}
						}
						progress++;
						IJ.showProgress(progress/total);
					}
				}
			}
			imp.updateAndDraw();
		}
		IJ.log("MBA Apply Complete");
	}

}
