package ajs.tools;

import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import ij.*;
import ij.gui.GenericDialog;
import ij.gui.HistogramWindow;
import ij.gui.Overlay;
import ij.gui.Plot;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.gui.YesNoCancelDialog;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.filter.EDM;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.Blitter;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.StackProcessor;
import ij.text.TextPanel;
import ij.text.TextWindow;

public class AJ_Misc_Plugins implements PlugIn {

	@Override
	public void run(String arg) {
		
		if(arg==null || arg.equals(""))return;
		
		Method[] allMethods = AJ_Misc_Plugins.class.getDeclaredMethods();
		for (Method method : allMethods) {
		    if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())) {
		        //System.out.println(method);
		        if(arg.equals(method.getName()) && method.getAnnotatedParameterTypes().length==0) {
					try {
						method.invoke(null, (Object[])null);
					} catch (Exception e) {
						e.printStackTrace();
					}
		        }
		    }
		}
		
	}
	
	public static void zStepMax() {
		zStepMax(null);
	}
	
	public static void zStepMax(ImagePlus imp) {
		if(imp==null) imp=WindowManager.getCurrentImage();
		ImageStack imst=imp.getStack();
		
		String[] modes=new String[]{"Copy", "Blend", "Average", "Difference", "Transparent-white", "Transparent-zero", "AND", "OR", "XOR", "Add", "Subtract", "Multiply", "Divide", "Min", "Max"};
		int[] modenums=new int[]{0,7,7,8,2,14,9,10,11,3,4,5,6,12,13};

		GenericDialog gd=new GenericDialog("Zstepmax");
		gd.addNumericField("Depth?",3.0,0);
		gd.addCheckbox("Keep original?",false);
		gd.addChoice("Mode",modes,"Max");
		gd.showDialog();
		if(gd.wasCanceled())return;
		int depth=(int)gd.getNextNumber();
		boolean keep=gd.getNextBoolean();
		String mode=gd.getNextChoice();
		
		String zsmtitle=imp.getTitle()+"-zstepmax"+depth;
		int modenum=0;
		for(int i=0;i<modes.length;i++) {if(mode.contentEquals(modes[i]))modenum=modenums[i];}
		
		if(keep){
			imp=(new Duplicator()).run(imp);
			imp.show();
		} else {imp.setTitle(zsmtitle);}
		int frms=imp.getNFrames(), sls=imp.getNSlices(), chs=imp.getNChannels();
		for(int fr=0;fr<frms;fr++){
			for(int sl=0;sl<sls;sl++){
				for(int ch=0;ch<chs;ch++){
					for(int n=1;n<depth;n++){
						if(sl+n<sls){
							imst.getProcessor(1+ch+sl*chs+fr*chs*sls).copyBits(imst.getProcessor(1+ch+(sl+n)*chs+fr*chs*sls),0,0,modenum);
							IJ.showProgress((double)(1+ch+sl*chs+fr*chs*sls)/(double)(frms*chs*sls));
						}
					}
				}
			}
		}
		IJ.showProgress(1.0);
		imp.updateAndDraw();
	}
	
	public static ImagePlus TProjector() {
		return TProjector(null);
	}
	
	public static ImagePlus TProjector(ImagePlus imp) {
		if(imp==null) imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage(); return null;}
		if(imp.getNFrames()<2) {IJ.error("Stack does not have multiple frames"); return null;}
		boolean doallsls=true;
		int method=1;
		int stfr=1, endfr=imp.getNFrames();
		String[] METHODS=ij.plugin.ZProjector.METHODS;
		GenericDialog gd=new GenericDialog("TProjector");
		gd.addNumericField("Start Frame:", stfr);
		gd.addNumericField("End Frame:", endfr);
		gd.addChoice("Mode",METHODS,METHODS[method]);
		gd.addCheckbox("Do all Slices?", true);
		gd.showDialog();
		if(gd.wasCanceled())return null;
		stfr=(int)gd.getNextNumber();
		endfr=(int)gd.getNextNumber();
		method=gd.getNextChoiceIndex();
		doallsls=gd.getNextBoolean();
		return TProjector(imp, method, doallsls, stfr, endfr);
	}
	
	public static ImagePlus TProjector(ImagePlus imp, int method, boolean doallsls, int stfr, int endfr) {
		if(imp==null) {IJ.noImage(); return null;}
		int frms=imp.getNFrames(), chs=imp.getNChannels(),ufrms=endfr-stfr+1, frchs=ufrms*chs;
		if(frms<2) {IJ.error("Stack does not have multiple frames"); return null;}
		
		ImageStack oldis=imp.getImageStack();
		ImageStack newis=new ImageStack(imp.getWidth(), imp.getHeight(), imp.getNChannels()*(doallsls?imp.getNSlices():1));
		int stsl=doallsls?0:(imp.getZ()-1), endsl=doallsls?imp.getNSlices():imp.getZ();
		for(int z=stsl; z<endsl; z++) {
			ImageStack imst=new ImageStack(imp.getWidth(), imp.getHeight(), frchs);
			imst.update(imp.getProcessor());
			for(int fr=(stfr-1);fr<endfr; fr++){
				for(int ch=0;ch<chs;ch++)
					imst.setPixels(oldis.getProcessor(imp.getStackIndex(ch+1,z+1,fr+1)).getPixels(), (fr-stfr+1)*chs+ch+1);
			}
			ImagePlus newimp=new ImagePlus("temp",imst);
			newimp.setDimensions(imp.getNChannels(),1,ufrms);
			if(imp.isComposite()) {
				CompositeImage newcimp=new CompositeImage(newimp,ij.CompositeImage.COMPOSITE);
				newcimp.setLuts(imp.getLuts());
				newimp=newcimp;
			}
			ij.plugin.ZProjector zprojector=new ij.plugin.ZProjector(newimp);
			zprojector.setMethod(method);
			zprojector.setStartSlice(1);
			zprojector.setStopSlice(ufrms);
			zprojector.doHyperStackProjection(true);
			ImagePlus projImage=zprojector.getProjection();
			if(z==stsl)newis.update(projImage.getImageStack().getProcessor(1));
			for(int ch=0;ch<chs;ch++){
				newis.setPixels(projImage.getImageStack().getProcessor(ch+1).getPixels(),z*chs+ch+1);
			}
			projImage.changes=false; projImage.close();
			newimp.changes=false; newimp.close();
		}
		String[] SMETHODS=new String[] {"AVG","MAX","MIN","SUM","SD","MEDIAN"};
		ImagePlus endImage=new ImagePlus(imp.getTitle()+"-TProj"+SMETHODS[method],newis);
		endImage.setDimensions(chs, doallsls?imp.getNSlices():1, 1);
		if(imp.isComposite()) {
			CompositeImage newcimp=new CompositeImage(endImage,ij.CompositeImage.COMPOSITE);
			newcimp.setLuts(imp.getLuts());
			endImage=newcimp;
		}
		endImage.setCalibration(imp.getCalibration());
		ajs.tools.Slicelabel_Transfer.transferSliceLabels(imp, endImage);
		endImage.show();
		return endImage;
	}
	
	public static void colorAssociationPlot() {
		colorAssociationPlot(null);
	}
	
	public static void colorAssociationPlot(ImagePlus imp) {
		boolean showplot=false;
		boolean showhisto=true;
		double notBelow=0;//2000.0;
		
		if(imp==null)imp=WindowManager.getCurrentImage();
		Roi roi=imp.getRoi();
		if(roi==null) {IJ.run("Select All"); roi=imp.getRoi();}
		Point[] ps=roi.getContainedPoints();
		if(ps==null || ps.length==0) {IJ.log("Could not get points within selection"); return;}
		Rectangle b=roi.getBounds();
		double[] xs=new double[ps.length], ys=new double[ps.length];
		float[][] rg=new float[b.height][b.width];
		ImageStack st=imp.getStack();
		int sl=imp.getZ(), fr=imp.getT();
		ImageProcessor ip2=st.getProcessor(imp.getStackIndex(2,sl,fr)), ip3=st.getProcessor(imp.getStackIndex(3,sl,fr));
		for(int i=0; i<ps.length; i++){
			xs[i]=ip2.getPixelValue(ps[i].x,ps[i].y);
			ys[i]=ip3.getPixelValue(ps[i].x,ps[i].y);
			if(xs[i]>notBelow) rg[ps[i].y-b.y][ps[i].x-b.x]=(float)(ys[i]/xs[i]);
		}

		if(showplot){
			Plot plot=new Plot("Color Assoc Plot", "Green", "Red/Green");
			plot.addPoints(xs, ys, Plot.DOT);
			plot.show();
		}
		if(showhisto){
			ImagePlus histimp=new ImagePlus("temp");
			histimp.setProcessor(new FloatProcessor(rg));
			HistogramWindow hist=new HistogramWindow("Color Assoc Hist", histimp, 256);
			hist.setVisible(true);
		}
	}
	
	public static void normalizeBrightness() {
		normalizeBrightness(null);
	}
	
	public static void normalizeBrightness(ImagePlus imp) {
		String defaultNormalizationChs="Normalize to this channel";
		String defaultNormalizationZ="Change all based on Z-proj";

		if(imp==null) imp=WindowManager.getCurrentImage();
		int sls=imp.getNSlices(), frms=imp.getNFrames(), chs=imp.getNChannels();
		boolean dosls=(frms==1 && sls>1);

		boolean aballzproj=false, abeachsls=false;
		int ch=imp.getC(), frm=imp.getT(), sl=imp.getZ();
		int stsl=sl-1,endsl=sl;
		int stc=ch-1,endc=ch, stcn=stc, endcn=endc; //n's for normalizing
		if(dosls){stsl=frm-1;endsl=frm;}
		String[] zchoices=new String[] {"Average Intensity","Max Intensity", "Min Intensity","Sum Slices","Standard Deviation","Median"};
		String zchoice="Max Intensity";
		boolean useResults=false;
		int nResults=0;
		ResultsTable rt=ResultsTable.getResultsTable("Results");
		if(rt!=null) {
			nResults=rt.getCounter();
		}
		int[] whichsls=new int[] {1,1};
		String abchopts=defaultNormalizationChs;
		int threshold=-1;
		
		if(chs>1 || (sls>1 &&  frms>1)){
			GenericDialog gd=new GenericDialog("Auto Brightness");
			gd.addMessage("Ch: "+(ch)+" Sl: "+sl+" Fr: "+frm+" on stack: "+imp.getTitle());
			if(chs>1) {
				gd.addNumericField("Ch:", ch, 0);
				gd.addChoice("Multiple ch options:",new String[] {"Do only one channel","Do all channels separately","Normalize to this channel"},defaultNormalizationChs);
			}
			if(nResults==frms || (dosls && nResults==sls))gd.addCheckbox("Use Means from Results?",false);
			if(sls>1 && frms>1) {
				gd.addChoice("Adjust brightness over slices or frames?",new String[] {"Frames","Slices"},"Frames");
				gd.addChoice("Do whole stack?",new String[] {"Only current fr/sl","Repeat individually for all frs/sls","Change all based on Z-proj"},defaultNormalizationZ);
				zchoice=zchoices[(int)ij.Prefs.get("zproject.method", 1)];
				gd.addChoice("If z-proj, how?",zchoices,zchoice);
				gd.addStringField("If z-proj, which sls:?", "1-"+sls);
			}
			gd.addNumericField("Only measure below pixel value: (-1=ignore)", threshold);
			gd.showDialog();
			if(gd.wasCanceled())return;
			if(chs>1) {
				ch=(int)gd.getNextNumber();
				imp.setPosition(ch, sl, frm);
				stc=ch-1; endc=ch; stcn=stc; endcn=endc; //n's for normalizing
				abchopts=gd.getNextChoice();
				if(abchopts=="Do all channels separately"){stc=0; endc=chs;}
				if(abchopts=="Normalize to this channel"){stcn=0; endcn=chs;}
			}
			if(nResults==frms || (dosls && nResults==sls))useResults=gd.getNextBoolean();
			String aballslschoice="";
			if(sls>1 && frms>1) {
				dosls=(gd.getNextChoice()=="Slices");
				aballslschoice=gd.getNextChoice();
				zchoice=gd.getNextChoice();
				whichsls=AJ_Utils.parseRange(gd.getNextString());
				abeachsls=(aballslschoice=="Repeat individually for all frs/sls");
				aballzproj=(aballslschoice=="Change all based on Z-proj");
				if(dosls){stsl=frm-1; endsl=frm;}
				if(abeachsls){stsl=0;if(dosls){endsl=frms;}else{endsl=sls;}}
			}
			threshold=(int)gd.getNextNumber();
		}
		int endfrms=dosls?sls:frms;
		double[] means=new double[endfrms];
		double maxmean=0;
		int alldim=1;if(aballzproj){if(dosls){alldim=frms;}else{alldim=sls;}}
		if(useResults){
			int colind=rt.getColumnIndex("Mean");
			for(int i=0;i<nResults;i++){
				means[i]=rt.getValueAsDouble(colind, i);
				maxmean=Math.max(means[i],maxmean);
			}
		}
		Roi roi=imp.getRoi();
		boolean selec=roi!=null;
		if(selec) {IJ.run("Select None");}
		if(!dosls && aballzproj && !useResults){
			IJ.run("Z Project...", "start="+whichsls[0]+" stop="+whichsls[1]+" projection=["+zchoice+"] all");
			ImagePlus zimp=WindowManager.getCurrentImage();
			if(chs>0)zimp.setPosition(stc+1,imp.getZ(),imp.getT());
			if(selec) IJ.run("Restore Selection");
			for(int j=0;j<endfrms;j++){
				zimp.setPosition(ch,1,j+1);
				ImageProcessor zip=zimp.getProcessor();
				if(threshold>-1) {
					zip.setThreshold(0, threshold);
					ij.plugin.filter.ThresholdToSelection tts = new ij.plugin.filter.ThresholdToSelection();
					Roi trroi=tts.convert(zip);
					if(trroi==null)IJ.log("Threshold failed");
					zimp.setRoi(trroi);
					zip.setRoi(trroi);
				}
				ImageStatistics imgstat=ImageStatistics.getStatistics(zip, 127, zimp.getCalibration());
				double area=imgstat.area;
				IJ.showStatus("Area f"+(j+1)+" "+(int)area);
				double mean=imgstat.mean;
				maxmean=Math.max(mean,maxmean);
				means[j]=mean;
				IJ.showStatus("Measuring means...");
				IJ.showProgress((double)(j+1)/(double)endfrms);
			}
			zimp.close();
		}
		double n=0;
		for(int chi=stc;chi<endc;chi++){
			for(int i=stsl;i<endsl;i++) {
				if(dosls || !aballzproj && !useResults){
					double mean;
					if(selec) IJ.run("Restore Selection");
					for(int j=0;j<endfrms;j++){
						int sli=i, frmj=j;
						if(dosls) {sli=j; frmj=i;}
						if(aballzproj){
							Duplicator dup=new Duplicator();
							ImagePlus did;
							if(dosls)did=dup.run(imp, chi+1, chi+1, sli+1, sli+1, 1, frms);
							else did=dup.run(imp, chi+1, chi+1, 1, sls, frmj+1, frmj+1);
							IJ.run("Z Project...", "projection=["+zchoice+"]");
							ImagePlus zd=WindowManager.getCurrentImage();
							ImageStatistics imgstat=ImageStatistics.getStatistics(zd.getProcessor(), 127, zd.getCalibration());
							mean=imgstat.mean;
							zd.close();
							did.close();
						} else {
							imp.setPosition(chi+1,sli+1,frmj+1);
							ImageStatistics imgstat=ImageStatistics.getStatistics(imp.getProcessor(), 127, imp.getCalibration());
							mean=imgstat.mean;
						}
						//print("ch:"+chi+" sl:"+sli+" frm:"+frmj);
						maxmean=Math.max(mean,maxmean);
						means[j]=mean;
						IJ.showStatus("Measuring means...");
						IJ.showProgress(n++/(double)(endfrms*(endsl-stsl)*(endc-stc)*(endcn-stcn)));
					}
				}
				WindowManager.setCurrentWindow(imp.getWindow());
				if(selec) IJ.run("Select None");
				double nn=0;
				ImageStack imst=imp.getStack();
				for(int j=0;j<endfrms;j++){
					for(int k=0;k<alldim;k++){
						int newi=i;
						if(aballzproj){newi=k;}
						int sli=newi, frmj=j;
						if(dosls) {sli=j; frmj=newi;}
						for(int chin=stcn;chin<endcn;chin++){
							int chk=chi;
							if(abchopts=="Normalize to this channel") chk=chin;
							imst.getProcessor(1+chk+sli*chs+frmj*chs*sls).multiply(maxmean/means[j]);
							IJ.showStatus("Normalizing stack (ch"+(chk+1)+"/"+(endcn-stcn)+" sl"+ IJ.pad(k+1,2) +"/"+alldim+" fr"+ IJ.pad(j+1,2) +"/"+endfrms+")...");
							IJ.showProgress(nn++/(double)(endfrms*alldim*(endcn-stcn)));
						}
					}
				}
				IJ.showStatus("Normalization complete.");
				IJ.showProgress(1.0);
			}
		}
		if(selec) IJ.run("Restore Selection");
		imp.updateAndDraw();
	}
	
	public static int[][] parseStackRegResults(ImagePlus imp, boolean isYtoZ){
		TextWindow tw=null;
		int frms=imp.getNFrames();
		double pw=imp.getCalibration().pixelWidth,ph=imp.getCalibration().pixelHeight;
		Frame[] textWindows=WindowManager.getNonImageWindows();
		for(int i=0;i<textWindows.length;i++) {
			if(textWindows[i] instanceof TextWindow) {
				TextPanel transformTp=((TextWindow) textWindows[i]).getTextPanel();
				if(transformTp.getColumnHeadings().contains("SourceX") && transformTp.getColumnHeadings().contains("Frame")) {
					tw=((TextWindow)textWindows[i]);
				}
			}
		}

		int[] xsi= new int[frms],ysi=new int[frms],zsi=new int[frms];
		double[] xs,ys,zs,frs;
		if(tw==null) {
			ResultsTable rt=ResultsTable.getResultsTable();
			//if(rt.size()!=imp.getNFrames()){IJ.error("need to measure all frames");return;}
			if(rt==null || !rt.columnExists("X") || !rt.columnExists("Y") || ! rt.columnExists("Frame")){IJ.error("No Results or StackReg Results found");return null;}
			rt.sort("Frame");
			xs=rt.getColumnAsDoubles(rt.getColumnIndex("X"));
			ys=rt.getColumnAsDoubles(rt.getColumnIndex("Y"));
			zs=new double[ys.length];
			if(rt.columnExists("Slice"))zs=rt.getColumnAsDoubles(rt.getColumnIndex("Slice"));
			frs=rt.getColumnAsDoubles(rt.getColumnIndex("Frame"));
			for(int i=0;i<xs.length;i++) {xs[i]/=pw; ys[i]/=ph;}
			if(xs.length<frms) {
				double[] nxs=new double[frms];
				double[] nys=new double[frms];
				double[] nzs=new double[frms];
				int ind=0, nfr=(int)frs[0]-1, prfr=0;
				double prx=xs[0], pry=ys[0], prz=zs[0], nx=prx, ny=pry, nz=prz;
				for(int fr=0;fr<nfr;fr++) {
					nxs[fr]=xs[0]; nys[fr]=ys[0]; nzs[fr]=zs[0];
				}
				for(int fr=nfr;fr<frms;fr++) {
					if(((int)frs[ind]-1)==fr) {
						ind++;
						prx=nx; pry=ny; prz=nz; prfr=nfr;
						if(ind>=frs.length){ind--; nfr=frms;}
						else {nfr=(int)frs[ind]-1;}
						nx=xs[ind]; ny=ys[ind]; nz=zs[ind];
					}
					nys[fr]=((ny*(fr-prfr))+(pry*(nfr-fr)))/(nfr-prfr);
					nxs[fr]=((nx*(fr-prfr))+(prx*(nfr-fr)))/(nfr-prfr);
					nzs[fr]=((nz*(fr-prfr))+(prz*(nfr-fr)))/(nfr-prfr);
				}
				xs=nxs; ys=nys; zs=nzs;
			}
			for(int i=0;i<xs.length;i++){
				xsi[i]=(int)((xs[i]-xs[0]));
				ysi[i]=(int)((ys[i]-ys[0]));
				zsi[i]=(int)((zs[i]-zs[0]));
			}
		}else {
			String[] text=tw.getTextPanel().getText().split("\n");
			if(text[0].split("\t")[0].trim().isEmpty()) {
				for(int i=0;i<text.length;i++)text[i]=text[i].substring(text[i].indexOf("\t")+1);
			}
			xs=new double[frms];
			ys=new double[frms];
			int starti=0;
			for(int i=0;i<frms;i++) {
				boolean found=false;
				for(int j=1;j<text.length;j++) {
					int rowfr=Integer.parseInt(text[j].split("\t")[0]);
					if(rowfr==(i+1)) {found=true;break;}
				}
				if(!found) {starti=i;break;}
			}
			double xprev=0.0,yprev=0.0;
			for(int j=1;j<text.length;j++) {
				String[] row=text[j].split("\t");
				int rowfr=Integer.parseInt(row[0]);
				xs[rowfr-1]=xprev+Double.parseDouble(row[1]);
				ys[rowfr-1]=yprev+Double.parseDouble(row[2]);
				if(j==1) {
					xs[starti]=Double.parseDouble(row[3]);
					ys[starti]=Double.parseDouble(row[4]);
				}
				xprev=xs[rowfr-1]-xs[starti]; yprev=ys[rowfr-1]-ys[starti];
				if(rowfr==1) {xprev=0; yprev=0;}
			}
		}
		double yc=1.0;
		if(isYtoZ)yc=imp.getCalibration().pixelDepth/pw;
		for(int i=0;i<xs.length;i++){
			xsi[i]=(int)((xs[i]-xs[0]));
			ysi[i]=(int)((ys[i]-ys[0])/yc);
		}
		return new int[][] {xsi,ysi,zsi};
	}
	
	public static boolean isZAdjusted(String imageTitle) {
		int[][] xys=parseStackRegResults(WindowManager.getImage(imageTitle), true);
		if(xys==null)return false;
		int[] zs=xys[1];
		if(zs==null)return false;
		int zmin=65535, zmax=-65535;
		for(int i=0;i<zs.length;i++) {
			zmin=Math.min(zmin, zs[i]);
			zmax=Math.max(zmax, zs[i]);
		}
		if(zmin==zmax) {return false;}
		return true;
	}
	
	public static void ptTranslator() {
		ptTranslator(null);
	}
	
	enum AlignType{
		FIRSTSLICE,
		MIN;
	}
	
	public static void ptTranslator(ImagePlus imp) {
		
		AlignType alignType=AlignType.FIRSTSLICE;
		if(imp==null) imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage(); return;}
		GenericDialog gd=new GenericDialog("Pt-Translate");
		gd.addMessage("OK to do: "+imp.getTitle());
		String[] choices=new String[] {"First slice","Minimums for x and y"};
		gd.addChoice("Align to:",choices, choices[0]);
		gd.addCheckbox("Adjust XY to XY data", true);
		gd.addCheckbox("Adjust Z to slice data", false);
		gd.addCheckbox("StackReg on Reslice (Y is Z)", false);
		gd.showDialog();
		if(gd.wasCanceled())return;
		if(gd.getNextChoice().equals(choices[1]))alignType=AlignType.MIN;
		boolean doXY=gd.getNextBoolean();
		boolean doSliceZ=gd.getNextBoolean();
		boolean isYtoZ=gd.getNextBoolean();
		int[][] xys=parseStackRegResults(imp, isYtoZ);
		if(xys==null) {
			IJ.error("First measure XYZ for each frame or run StackReg and show results");
			return;
		}
		
		int xmin=0,ymin=0;
		if(alignType==AlignType.MIN){
			xmin=65535; ymin=65535;//,xmax=0,ymax=0;
			for(int i=0;i<xys[0].length;i++){
				xmin=Math.min(xmin, xys[0][i]);
				//xmax=(int)Math.max(xmax, xs[i]/pw);
				ymin=Math.min(ymin, xys[1][i]);
				//ymax=(int)Math.max(ymax, ys[i]/ph);
			}
			for(int i=0;i<xys[0].length;i++){
				xys[0][i]=xys[0][i]-xmin;
				xys[1][i]=xys[1][i]-ymin;
			}
		}
		if(isYtoZ) {
			ptTranslator(imp,null,null,xys[1]);
		}else
			ptTranslator(imp, doXY?xys[0]:null, doXY?xys[1]:null, doSliceZ?xys[2]:null);
	}
	
	/**
	 * Translates each image in frame by double[] xs and ys in micron coordinate diffs
	 * Needs to be length frms
	 * @param imp
	 * @param xs
	 * @param ys
	 * @param alignType
	 */
	public static void ptTranslator(ImagePlus imp, double[] xs, double[] ys) {
		double pw=imp.getCalibration().pixelWidth,ph=imp.getCalibration().pixelHeight;
		int[] xsi= new int[xs.length],ysi=new int[ys.length];
		for(int i=0;i<xs.length;i++){
			xsi[i]=(int)(xs[i]/pw);
			ysi[i]=(int)(ys[i]/ph);
		}
		ptTranslator(imp, xsi, ysi, null);
	}
	
	/**
	 * Translates each image in frame by int[] xs and ys[] in pixels which should be length frms
	 * @param imp
	 * @param xs
	 * @param ys
	 * @param alignType
	 */
	public static void ptTranslator(ImagePlus imp, int[] xs, int[] ys, int[] zs) {
		if(imp==null) {IJ.noImage(); return;}
		int frms=imp.getNFrames(),sls=imp.getNSlices(),chs=imp.getNChannels(), w=imp.getWidth(),h=imp.getHeight();
		if(zs==null && ys==null && xs==null) {IJ.error("ptTrtanslator requires some input"); return;}
		ImageStack imst=imp.getStack();
		double tp=frms*sls*chs;
		String zinfo="",xinfo="",yinfo="";
		String oinfo=imp.getInfoProperty();
		if(oinfo==null || oinfo.contentEquals(""))oinfo="";
		else if(!oinfo.endsWith("\n")) oinfo=oinfo+"\n";
		if(zs!=null) {
			if(zs.length!=frms) {IJ.error("ptTranslator Error - Zs must equal frms");return;}
			int slmax=sls, slmin=1, zmin=65535, zmax=-65535;
			for(int i=0;i<zs.length;i++) {
				zmin=Math.min(zmin, zs[i]);
				zmax=Math.max(zmax, zs[i]);
			}
			if(zmin==zmax) {IJ.log("ptTranslator: No z-shifting needed.");}
			else {
				if(zmin<0) {
					if(zmax<0)slmax=sls+zmin;
					else slmax=sls+zmin-zmax;
					slmin=1-zmin;
				}else {
					slmax=sls-zmax;
				}
				int n=0;
				zinfo="PTzs: ";
				for(int fr=0;fr<frms;fr++) {
					zinfo=zinfo+zs[fr]+(fr==(frms-1)?"":",");
					for(int sl=0;sl<sls;sl++) {
						for(int ch=0;ch<chs;ch++) {
							if( ((sl+1)<(slmin+zs[fr])) || ((sl+1)>(slmin+zs[fr]+slmax-1)) ) {
								imst.deleteSlice(n+1+ch+(sl*chs)+(fr*sls*chs));
								n--;
							}
						}
					}
				}
				imp.setStack(imst,chs,slmax,frms);
			}
			zinfo=zinfo+"\n";
			IJ.log("Slices before: "+sls);
			sls=sls-(zmax-zmin);
			IJ.log("Slices after: "+sls);
		}
		
		if(xs!=null && ys!=null) {
			if(xs.length!=frms || ys.length!=frms) {IJ.error("ptTranslator Error - xs or ys not length frms");return;}
			xinfo="PTxs: ";
			yinfo="PTys: ";
			int xmin=65535, xmax=-65535,ymin=65535, ymax=-65535;
			for(int i=0;i<frms;i++) {
				xmin=Math.min(xmin, xs[i]);
				xmax=Math.max(xmax, xs[i]);
				ymin=Math.min(ymin, ys[i]);
				ymax=Math.max(ymax, ys[i]);
			}
			if(xmin==xmax && ymin==ymax) {IJ.log("ptTranslator: No xy-shifting needed."); return;}
			for(int fr=0;fr<frms;fr++){
				IJ.log("xsi"+fr+": "+xs[fr]+","+ys[fr]);
				xinfo=xinfo+xs[fr]+((fr==(frms-1))?"":",");
				yinfo=yinfo+ys[fr]+((fr==(frms-1))?"":",");
				if(xs[fr]==0 && ys[fr]==0)continue;
				for(int sl=0;sl<sls;sl++){
					for(int ch=0;ch<chs;ch++){
						ImageProcessor ip=imst.getProcessor(fr*sls*chs+sl*chs+ch+1);
						for(int y=0;y<h;y++){
							int yc=y;
							if(ys[fr]<0)yc=h-y-1;
							int yt=yc+ys[fr];
							for(int x=0;x<w;x++){
								int xc=x;
								if(xs[fr]<0)xc=w-x-1;
								int xt=xc+xs[fr];
								if(xt<w && xt>=0 && yt>=0 && yt<h)ip.set(xc,yc,ip.get(xt,yt));
								else ip.set(xc,yc,0);
							}
						}
						IJ.showProgress((double)(fr*sls*chs+sl*chs+ch)/(double)tp);
					}
				}
			}
			IJ.showProgress(1.0);
			xinfo=xinfo+"\n";
			yinfo=yinfo+"\n";
		}

		oinfo=oinfo+xinfo+yinfo+zinfo;
		imp.setProperty("Info", oinfo);
	}
	
	public static ImagePlus resizer(ImagePlus imp, int newWidth, int newHeight, int newDepth, int newFrames) {
		int origWidth=imp.getWidth(), origHeight=imp.getHeight(), chs=imp.getNChannels(), origSlices=imp.getNSlices(), origFrames=imp.getNFrames();
		ImageProcessor ip=imp.getProcessor();
		double min=ip.getMin(), max=ip.getMax();
		ImagePlus output=imp;
		if(newWidth!=origWidth || newHeight != origHeight) {
			try {
				StackProcessor sp = new StackProcessor(imp.getStack(), ip);
				ImageStack s2 = sp.resize(newWidth, newHeight, true);
				int newSize = s2.getSize();
				if (s2.getWidth()>0 && newSize>0) {
					Calibration cal = imp.getCalibration();
					if (cal.scaled()) {
						cal.pixelWidth *= origWidth/newWidth;
						cal.pixelHeight *= origHeight/newHeight;
					}
					//imp.setStack(null, s2);
					output=new ImagePlus(imp.getTitle(),s2);
					Overlay overlay = imp.getOverlay();
					if (overlay!=null && !imp.getHideOverlay())
						output.setOverlay(overlay.scale(newWidth/origWidth,newHeight/origHeight));
					else
						output.setOverlay(null);
				}
			} catch(OutOfMemoryError o) {
				IJ.outOfMemory("Resize");
			}
		}
		ij.plugin.Resizer resizer=new ij.plugin.Resizer();
		if(newDepth!=origSlices) {
			ImagePlus newimp=resizer.zScale(output, newDepth, ImageProcessor.BILINEAR);
			output.changes=false; output.close();
			output=newimp;
		}
		if(newFrames!=origFrames) {
			ImagePlus newimp=resizer.zScale(output, newFrames, ImageProcessor.BILINEAR+ij.plugin.Resizer.SCALE_T);
			output.changes=false; output.close();
			output=newimp;
		}
		output.setDimensions(chs, newDepth, newFrames);
		output.setDisplayRange(min,max);
		output.updateAndDraw();
		if(output!=imp) {imp.changes=false; imp.close();}
		return output;
	}
	
	public static void distanceFromRoiToLabelMap() {
		distanceToRoiFromLabelMap(WindowManager.getCurrentImage());
	}
	
	public static void distanceToRoiFromLabelMap(ImagePlus imp) {
		if(imp==null) {IJ.noImage();return;}
		int width=imp.getWidth(), height=imp.getHeight(), frms=imp.getNFrames();
		
		Roi compareRoi=imp.getRoi();
		if(compareRoi==null || (compareRoi.getType()==Roi.RECTANGLE && compareRoi.getBounds().equals(new Rectangle(0,0,width,height)))) {
			RoiManager roiManager=RoiManager.getRoiManager();
			if(roiManager.getCount()<1) {IJ.error("Need ROI on image or in RoiManager"); return;}
			compareRoi=roiManager.getRoi(0);
			imp.setRoi(compareRoi);
		}
		
		String headings="Cell\tFrame\tAxonDistance";
		
		TextWindow tw=new TextWindow(imp.getTitle()+"-axonDist.csv",headings,"",800,400);
		java.awt.MenuItem mi=tw.getMenuBar().getMenu(0).getItem(0);
		mi.removeActionListener(tw);
		mi.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(ActionEvent e) {
				textWindowCsvSaveAs(tw);
			}
		});
		double px=imp.getCalibration().pixelWidth;
		if(px==1.0) {
			px=IJ.getNumber("Pixel Width is 1, change?", 0.495);
			if(px==IJ.CANCELED)px=1.0;
		}
		
		for(int fr=0; fr<frms; fr++) {
			imp.setPosition(imp.getC(), imp.getZ(), fr+1);
			ImageProcessor ip= imp.getProcessor();
			int maxcell=0;
			for(int y=0;y<height;y++)
				for(int x=0;x<width;x++)
					if(ip.get(x,y)>maxcell)maxcell=ip.get(x,y);
			ThresholdToSelection tts=new ThresholdToSelection();
			for(int i=0; i<maxcell; i++) {
				ip.setThreshold(i+1, i+1);
				Roi croi=tts.convert(ip);
				if(croi==null) {
					IJ.log(IJ.pad((i+1), 3)+" \tNA");
					continue;
				}
				String min="NA";
				Double distance=distanceFromRoiToRoi(compareRoi, croi);
				if(distance!=null) min=""+(distance.doubleValue()*px);
				tw.append(""+(i+1)+"\t"+(fr+1)+"\t"+min);
			}
		}
	}
	
	public static Double distanceFromRoiToRoi(Roi compareRoi, Roi roi) {
		if(roi == null || compareRoi == null) return null;
		Rectangle cb=compareRoi.getBounds();
		Rectangle rb=roi.getBounds();
		ImageProcessor rip= new ByteProcessor(Math.max(cb.width+cb.x,rb.width+rb.x),Math.max(cb.height+cb.y,rb.height+rb.y));
		rip.setColor(255);
		rip.fill(roi);
		rip.invert();
		FloatProcessor edm = new EDM().makeFloatEDM(rip, 0, false);
		edm.setRoi(compareRoi);
		ImageStatistics imstat=ImageStatistics.getStatistics(edm,ImageStatistics.MIN_MAX,null);
		if(imstat==null)return null;
		return imstat.min;
	}
	
	public static void textWindowCsvSave(TextWindow rtw, String path) {
		if(path==null) {
			textWindowCsvSaveAs(rtw);
			return;
		}
		String text=rtw.getTextPanel().getText();
		text=text.replaceAll("\t", ",");
		try{
			FileWriter fw=new FileWriter(path);
			fw.append(text);
			fw.close();
		} catch (Exception e) {e.printStackTrace();}
	}
	
	public static void textWindowCsvSaveAs(TextWindow rtw) {
		javax.swing.JFileChooser fileChooser = new javax.swing.JFileChooser();
        fileChooser.setDialogTitle("Save As");
        fileChooser.setSelectedFile(new File(ij.io.OpenDialog.getDefaultDirectory()+rtw.getTitle()));

        int userSelection = fileChooser.showSaveDialog(null);

        if (userSelection == javax.swing.JFileChooser.APPROVE_OPTION) {
        	if(fileChooser.getSelectedFile().exists()) {
        		YesNoCancelDialog ync=new YesNoCancelDialog(null, "Overwrite file?","Ok to overwrite?");
        		if(!ync.yesPressed()) {IJ.showStatus("Write cancelled"); return;}
        	}
            String path = fileChooser.getSelectedFile().getAbsolutePath();
            textWindowCsvSave(rtw,path);
        }else {IJ.log("File not saved");}
	}

	public static String[] readFile(String path){
		ArrayList<String> lines=new ArrayList<String>();
		try {
			BufferedReader br=new BufferedReader(new FileReader(path));
			String line;
			while((line=br.readLine())!=null) {
				lines.add(line);
			}
			br.close();
		} catch (Exception e) {
			IJ.error("Failed to read file: " + e.getMessage());
			return null;
		}
		return lines.toArray(new String[lines.size()]);
	}

	public static double[][] getPointsFromCSV(String[] lines, String labelFilter, int frame, ImagePlus imp) {
		if(imp==null) imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage(); return null;}
		int ch=2;
		if(imp.getNChannels()<ch) {
			ch=1;
		}
		double pixelWidth=imp.getCalibration().pixelWidth;
		if(lines==null || lines.length==0) {
			IJ.error("No lines read from file");
			return null;
		}
		ArrayList<double[]> points=new ArrayList<double[]>();
		for(String line: lines) {
			if(line.startsWith("Label")) continue;
			if(labelFilter!=null && !line.startsWith(labelFilter))continue;
			String[] parts=line.split("[,\t ]+");
			if(parts.length<Thresh_Cell_Transfer.HEADINGS.FRAME.getIndex())continue;
			int frm=Integer.parseInt(parts[Thresh_Cell_Transfer.HEADINGS.FRAME.getIndex()]);
			if(frame>0 && frm!=frame)continue;
			int x=(int)(Double.parseDouble(parts[Thresh_Cell_Transfer.HEADINGS.X.getIndex()].trim())/pixelWidth);
			int y=(int)(Double.parseDouble(parts[Thresh_Cell_Transfer.HEADINGS.Y.getIndex()].trim())/pixelWidth);
			int z=Integer.parseInt(parts[Thresh_Cell_Transfer.HEADINGS.SLICE.getIndex()]);
			points.add(new double[] {x,y,z});
		}
		return points.toArray(new double[points.size()][]);
	}

    public static void ptsToPointRoi(double[][] pts, ImagePlus imp) {
        PointRoi proi=new PointRoi();
        for(double[] pt : pts) {
            int x=(int)pt[0];
            int y=(int)pt[1];
            int z=(int)pt[2];
            proi.addPoint(x, y, imp.getStackIndex(imp.getC(), z, imp.getT()));
        }
        imp.setRoi(proi);
    }

	public static ImagePlus resliceProject(ImagePlus imp, String axis) {
		if(imp==null) imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage(); return null;}
		return resliceProject(imp,null,axis,ij.plugin.ZProjector.AVG_METHOD,false,1.0,1,imp.getNChannels(),1,imp.getNFrames());
	}

	public static ImagePlus resliceProject(ImagePlus imp, Roi roi, String axis, int method, boolean avoidInterpolation, double outputSpacing, int stCh, int endCh, int stFrame, int endFrame) {
		if(imp==null) imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage(); return null;}
		String projTitle=imp.getTitle()+"-ResliceProj";
		int n=1;
		while(WindowManager.getImage(projTitle)!=null) {
			projTitle=projTitle+"_"+IJ.pad(n,2);
			n++;
		}
		if(roi==null)roi=imp.getRoi();
		if(roi==null)roi=new Roi(0,0,imp.getWidth(),imp.getHeight());

		Rectangle b=roi.getBounds();
		if(b.x<0)b.x=0; if(b.y<0)b.y=0; 
		if(b.x>imp.getWidth())b.x=imp.getWidth();
		if(b.y>imp.getHeight()-1)b.y=imp.getHeight()-1;
		if(b.x+b.width>imp.getWidth())b.width=imp.getWidth()-b.x;
		if(b.y+b.height>imp.getHeight())b.height=imp.getHeight()-b.y;
		int sls=imp.getNSlices();
		Calibration cal=imp.getCalibration();
		int width=b.width, height=(int)(sls*cal.pixelDepth/cal.pixelWidth), depth=b.height;
		boolean left=false, noproj=false;
		if(axis.toLowerCase().contentEquals("y") || axis.toLowerCase().contentEquals("left")) {
			width=b.height; depth=b.width;
			left=true;
		}
		if(method < 0) {
			noproj=true;
		}
		if(method > 3){
			IJ.error("Cannot handle method: "+method);
			return null;
		}
		if(avoidInterpolation) height=sls;
		ImagePlus proj=IJ.createHyperStack(projTitle, width, height, endCh-stCh+1, noproj?depth:1, endFrame-stFrame+1, imp.getBitDepth());

		double all=(endFrame-stFrame+1)*(endCh-stCh+1);
		for(int frm=stFrame; frm<=endFrame; frm++) {
			for(int ch=stCh; ch<=endCh; ch++){
				IJ.showProgress((double)(((frm-stFrame)*(endCh-stCh+1))+(ch-stCh)+1)/all);
				int cch=ch-stCh+1, cfr=frm-stFrame+1;
				for(int d1=0; d1<width; d1++) {
					if(!noproj){
						double[] zs=new double[sls];
						ImageProcessor pip=proj.getStack().getProcessor(proj.getStackIndex(cch, 1, cfr));
						for(int sl=1; sl<=sls; sl++) {
							ImageProcessor ip=imp.getStack().getProcessor(imp.getStackIndex(ch, sl, frm));
							double endval=0;
							for(int d2=0; d2<depth; d2++) {
								int val=left?ip.get(b.x+d2, b.y+d1):ip.get(b.x+d1, b.y+d2);
								if(method==ij.plugin.ZProjector.MAX_METHOD) {
									endval=Math.max(val, endval);
								}else if(method==ij.plugin.ZProjector.MIN_METHOD) {
									if(d2==0)endval=val;
									else endval=Math.min(val, endval);
								}else {
									endval+=val;
								}
							}
							if(method==ij.plugin.ZProjector.AVG_METHOD)endval=endval/b.height;
							zs[sl-1]=endval;
							if(avoidInterpolation)pip.set(d1, sl-1, (int)endval);
						}
						if(!avoidInterpolation) {
							int[] interp=interpolate(zs, height);
							for(int sl=0; sl<height; sl++) {
								pip.set(d1, sl, interp[sl]);
							}
						}
					}else{
						for(int d2=0; d2<depth; d2++) {
							double[] zs=new double[sls];
							ImageProcessor pip=proj.getStack().getProcessor(proj.getStackIndex(cch, d2+1, cfr));
							for(int sl=1; sl<=sls; sl++) {
								ImageProcessor ip=imp.getStack().getProcessor(imp.getStackIndex(ch, sl, frm));
								zs[sl-1]=left?ip.get(b.x+d2, b.y+d1):ip.get(b.x+d1, b.y+d2);
								if(avoidInterpolation)pip.set(d1, sl-1, (int)zs[sl-1]);
							}
							if(!avoidInterpolation) {
								int[] interp=interpolate(zs, height);
								for(int sl=0; sl<height; sl++) {
									pip.set(d1, sl, interp[sl]);
								}
							}
						}
					}
				}
			}
		}
		if(proj instanceof CompositeImage && imp instanceof CompositeImage) {
			((CompositeImage)proj).copyLuts(imp);
			((CompositeImage)proj).setMode(imp.getCompositeMode());
		}
		return proj;
	}

	static private int[] interpolate(double[] zs, int newLength) {
		int[] out=new int[newLength];
		double jump=(double)newLength/(double)(zs.length);
		int next=0, ni=0;
		double bval=0, nval=zs[0];
		for(int i=0; i<newLength; i++) {
			if(i==next) {
				ni++;
				if(ni>=zs.length)ni=zs.length-1;
				next=(int)Math.ceil(jump*(double)ni);
				bval=nval; nval=zs[ni];
			}
			double ndist=((double)i-((double)(ni-1)*jump))/jump, bdist=1.0-ndist;
			out[i]=(int)(bval*bdist+nval*ndist);
		}
		return out;
	}

	static public void concatenateChannels() {
		String[] titles=WindowManager.getImageTitles().length>1?WindowManager.getImageTitles():null;
		if(titles==null || titles.length<2) {IJ.error("Need at least two images"); return;}
		String curImageTitle=WindowManager.getCurrentImage()!=null?WindowManager.getCurrentImage().getTitle():"";
		int chmax=0;
		for(String title: titles) {
			ImagePlus imp=WindowManager.getImage(title);
			if(imp!=null)chmax=Math.max(chmax, imp.getNChannels());
		}
		String chStr="Ch 1";
		for(int i=2; i<=chmax; i++) {
			chStr=chStr+", Ch "+i;
		}
		String[] labels=chStr.split(", ");
		boolean[] trues=new boolean[chmax];
		for(int i=0; i<chmax; i++)trues[i]=true;
		GenericDialog gd=new GenericDialog("Concatenate Channels");
		gd.addMessage("Select two images to concatenate channels");
		gd.addChoice("Image 1", titles, curImageTitle);
		gd.addCheckboxGroup(chmax, 1, labels, trues);
		gd.addChoice("Image 2", titles, (titles[0].contentEquals(curImageTitle))?titles[1]:titles[0]);
		gd.addCheckboxGroup(chmax, 1, labels, trues);
		gd.showDialog();
		if(gd.wasCanceled()) return;
		ImagePlus imp1=WindowManager.getImage(gd.getNextChoice());
		boolean[] keepChs1=new boolean[imp1.getNChannels()];
		for(int i=0; i<chmax; i++) {
			if(i<imp1.getNChannels()) keepChs1[i]=gd.getNextBoolean();
		}
		ImagePlus imp2=WindowManager.getImage(gd.getNextChoice());
		boolean[] keepChs2=new boolean[imp2.getNChannels()];
		for(int i=0; i<chmax; i++) {
			if(i<imp2.getNChannels()) keepChs2[i]=gd.getNextBoolean();
		}
		concatenateChannels(imp1, imp2, keepChs1, keepChs2);
	}

	static public void concatenateChannels(ImagePlus imp1, ImagePlus imp2) {
		concatenateChannels(imp1, imp2, null, null);
	}

	static public ImagePlus concatenateChannels(ImagePlus imp1, ImagePlus imp2, boolean[] keepChs1, boolean[] keepChs2) {
		if(imp1==null || imp2==null) {IJ.error("Need two images"); return null;}
		if(imp1.getNFrames()!=imp2.getNFrames() || imp1.getNSlices()!=imp2.getNSlices()) {IJ.error("Images must have same number of frames and slices"); return null;}
		int chs1=imp1.getNChannels(), chs2=imp2.getNChannels(), sls=imp1.getNSlices(), frms=imp1.getNFrames();
		if(keepChs1==null){
			keepChs1=new boolean[chs1];
			for(int i=0; i<chs1; i++)keepChs1[i]=true;
		}
		if(keepChs2==null){
			keepChs2=new boolean[chs2];
			for(int i=0; i<chs2; i++)keepChs2[i]=true;
		}
		if(keepChs1.length!=chs1 || keepChs2.length!=chs2) {IJ.error("keepChs arrays must match number of channels in images"); return null;}
		ImageStack imst1=imp1.getStack(), imst2=imp2.getStack();
		ImageStack outst=new ImageStack(imp1.getWidth(), imp1.getHeight());
		for(int fr=0; fr<frms; fr++) {
			for(int sl=0; sl<sls; sl++) {
				for(int ch=0; ch<chs1; ch++) {
					if(keepChs1[ch]) {
						outst.addSlice(imst1.getSliceLabel(fr*sls*chs1+sl*chs1+ch+1), imst1.getProcessor(fr*sls*chs1+sl*chs1+ch+1));
					}
				}
				for(int ch=0; ch<chs2; ch++) {
					if(keepChs2[ch]) {
						outst.addSlice(imst2.getSliceLabel(fr*sls*chs2+sl*chs2+ch+1), imst2.getProcessor(fr*sls*chs2+sl*chs2+ch+1));
					}
				}
			}
		}
		int newChs=0;
		for(int ch=0; ch<chs1; ch++) {
			if(keepChs1[ch])newChs++;
		}
		for(int ch=0; ch<chs2; ch++) {
			if(keepChs2[ch])newChs++;
		}
		ImagePlus out=new ImagePlus(imp1.getTitle()+"_concat", outst);
		out.setOpenAsHyperStack(imp1.isHyperStack());
		out.setDimensions(newChs, sls, frms);
		out.setCalibration(imp1.getCalibration());
		CompositeImage out2=new CompositeImage(out);
		ij.process.LUT[] luts=new ij.process.LUT[newChs];
		int i=0;
		for(int ch=0; ch<chs1; ch++) {
			if(ch<imp1.getLuts().length && keepChs1[ch]) {
				luts[i]=imp1.getLuts()[ch];
				i++;
			}
		}
		for(int ch=0; ch<chs2; ch++) {
			if(ch<imp2.getLuts().length && keepChs2[ch]) {
				luts[i]=imp2.getLuts()[ch];
				i++;
			}
		}
		out2.setLuts(luts);
		if(imp1 instanceof CompositeImage) out2.setMode(((CompositeImage)imp1).getMode());
		String info1=imp1.getInfoProperty(), info2=imp2.getInfoProperty();
		String info="Channel concatenation of:\n"+imp1.getTitle()+" and "+imp2.getTitle()+"\n"+info1+"\n"+info2;
		out2.setProperty("Info",info);
		out2.show();
		return out2;
	}

	static public void CCR2ConcatChs(){
		int[] imids=WindowManager.getIDList();
		if(imids==null || imids.length<2) {IJ.error("Need at least two images"); return;}
		boolean[] keepChs1=new boolean[3], keepChs2=new boolean[3];
		GenericDialog gd=new GenericDialog("CCR2ConcatChs");
		gd.addMessage("Higher wavelength (~890 nm) channels to keep:");
		gd.addCheckboxGroup(1, 3, new String[]{"1", "2", "3"}, new boolean[]{true, true, true});
		gd.addMessage("Lower wavelength (~775 nm) channels to keep:");
		gd.addCheckboxGroup(1, 3, new String[]{"1", "2", "3"}, new boolean[]{false, false, true});
		gd.showDialog();
		if(gd.wasCanceled()) return;
		for(int i=0; i<3; i++) {
			keepChs1[i]=gd.getNextBoolean();
		}
		for(int i=0; i<3; i++) {
			keepChs2[i]=gd.getNextBoolean();
		}
		ArrayList<Integer> doneIds=new ArrayList<Integer>();
		for(int id: imids) {
			if(doneIds.contains(id))continue;
			ImagePlus imp=WindowManager.getImage(id);
			String[] locs=get2pLocation(imp);
			if(locs==null)continue;
			int wv1=get2pWaveLength(imp);
			IJ.log("CCR2Concat: "+imp.getTitle()+"("+wv1+")"+" "+locs[0]+" "+locs[1]);
			for(int id2: imids) {
				if(id==id2 || doneIds.contains(id2))continue;
				ImagePlus imp2=WindowManager.getImage(id2);
				String[] locs2=get2pLocation(imp2);
				if(locs2==null || locs2.length<3)continue;
				int locx1=Math.round(Float.parseFloat(locs[0]));
				int locx2=Math.round(Float.parseFloat(locs2[0]));
				int locy1=Math.round(Float.parseFloat(locs[1]));
				int locy2=Math.round(Float.parseFloat(locs2[1]));
				if((Math.abs(locx1 - locx2) < 2) && (Math.abs(locy1 - locy2) < 2)) {
					int wv2=get2pWaveLength(imp2);
					if(wv1!=wv2){
						if(wv1<790 && wv2>820) {
							ImagePlus temp=imp;
							imp=imp2;
							imp2=temp;
						}
						Roi roi1=imp.getRoi(), roi2=imp2.getRoi();
						int xShift=0, yShift=0, zShift=0;
						if(roi1!=null && roi2!=null && roi1.getType()==Roi.POINT && roi2.getType()==Roi.POINT) {
							java.awt.Polygon poly1=roi1.getPolygon(), poly2=roi2.getPolygon();
							int xave1=0, yave1=0, xave2=0, yave2=0, z1=0, z2=0;
							for(int i=0; i<poly1.npoints; i++) {
								xave1+=poly1.xpoints[i];
								yave1+=poly1.ypoints[i];
							}
							xave1/=poly1.npoints;
							yave1/=poly1.npoints;
							for(int i=0; i<poly2.npoints; i++) {
								xave2+=poly2.xpoints[i];
								yave2+=poly2.ypoints[i];
							}
							xave2/=poly2.npoints;
							yave2/=poly2.npoints;
							z1=((PointRoi)roi1).getPointPosition(0);
							while(z1>(imp.getNSlices()*imp.getNChannels())) {
								z1-=imp.getNSlices()*imp.getNChannels();
							}
							z1=(int)Math.floor(z1/imp.getNChannels())+1;
							z2=((PointRoi)roi2).getPointPosition(0);
							while(z2>(imp2.getNSlices()*imp2.getNChannels())) {
								z2-=imp2.getNSlices()*imp2.getNChannels();
							}
							z2=(int)Math.floor(z2/imp2.getNChannels())+1;
							xShift=xave1-xave2;
							yShift=yave1-yave2;
							zShift=z1-z2;
							IJ.log("Imp1: x"+xave1+" y"+yave1+" z"+z1);
							IJ.log("Imp2: x"+xave2+" y"+yave2+" z"+z2);
							IJ.log("Shift: x"+xShift+" y"+yShift+" z"+zShift);
						}
						if(xShift!=0 || yShift!=0 || zShift!=0) {
							IJ.log("CCR2Concat: "+imp.getTitle()+"("+wv1+")"+" "+locs[0]+" "+locs[1]+" matched "+imp2.getTitle()+"("+wv2+")"+" "+locs2[0]+" "+locs2[1]+" with shift "+xShift+","+yShift+","+zShift);
						}else {
							IJ.log("CCR2Concat: "+imp.getTitle()+"("+wv1+")"+" "+locs[0]+" "+locs[1]+" matched "+imp2.getTitle()+"("+wv2+")"+" "+locs2[0]+" "+locs2[1]);
						}
						ImagePlus out=concatenateChannels(imp, imp2, keepChs1, keepChs2);
						if(xShift!=0 || yShift!=0 || zShift!=0) {
							XYZShiftChannel(out, 4, xShift, yShift, zShift, true);
						}
						doneIds.add(id);
						doneIds.add(id2);
						imp.close();
						imp2.close();
					}
				}
			}

		}
	}

	static public String[] get2pLocation(ImagePlus imp) {
		String info=imp.getInfoProperty();
		if(info==null || !info.contains("Location"))return null;
		String[] infolines=info.split("\n");
		int loclinei=-1;
		for(int i=0; i<infolines.length; i++) {
			if(infolines[i].contains("Location")) {
				loclinei=i;
				break;
			}
		}
		if(loclinei==-1)return null;
		String locline=infolines[loclinei];
		String loc=locline.split(":")[1].trim();
		String[] parts=loc.split("\\s+");
		if(parts.length<3) return null;
		String[] temp=new String[3];
		for(int i=0; i<3; i++) {
			temp[i]=parts[i];
		}
		return temp;
	}

	static public int get2pWaveLength(ImagePlus imp){
		String info=imp.getInfoProperty();
		if(info==null || !info.contains("Wv:"))return -1;
		String[] infolines=info.split("\n");
		int wvlinei=-1;
		for(int i=0; i<infolines.length; i++) {
			if(infolines[i].contains("Wv:")) {
				wvlinei=i;
				break;
			}
		}
		if(wvlinei==-1)return -1;
		String wvline=infolines[wvlinei];
		String wv=wvline.split("Wv:")[1].trim();
		return Integer.parseInt(wv);
	}

	static public void XYZShiftChannel(){
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage(); return;}
		Roi roi=imp.getRoi();
		//boolean manual=false;
		int xShift=0, yShift=0, zShift=0;
		if(roi!=null){
			java.awt.Polygon poly=roi.getPolygon();
			if(poly.npoints==2) {
				int[] xs=poly.xpoints, ys=poly.ypoints;
				xShift=xs[1]-xs[0];
				yShift=ys[1]-ys[0];
			}
			//manual=true;
		}
		int chs=imp.getNChannels(), frms=imp.getNFrames(), chToShift=imp.getChannel();
		//if(chs<2) {IJ.error("Need at least 2 channels"); return;}
		GenericDialog gd=new GenericDialog("Shift single channel in xy");
		gd.addMessage("Shift channel in xy to align to other channels");
		if(frms>1)gd.addCheckbox("Shift all frames?", true);
		String[] chchoices=new String[chs];
		for(int i=0; i<chs; i++) {
			chchoices[i]="Channel "+(i+1);
		}
		gd.addChoice("Channel to shift:", chchoices, chchoices[chToShift-1]);
		//gd.addCheckbox("manual enter shifts in pixels", manual);
		gd.addNumericField("X shift (px)", xShift, 0);
		gd.addNumericField("Y shift (px)", yShift, 0);
		gd.addNumericField("Z shift (px)", zShift, 0);
		gd.showDialog();
		if(gd.wasCanceled()) return;
		chToShift=gd.getNextChoiceIndex()+1;
		boolean shiftAll=frms>1?gd.getNextBoolean():false;
		//manual=gd.getNextBoolean();
		xShift=(int)gd.getNextNumber();
		yShift=(int)gd.getNextNumber();
		zShift=(int)gd.getNextNumber();
		//if(!manual) {
		//	IJ.error("Not coded yet");
		//	return;
		//}
		XYZShiftChannel(imp, chToShift, xShift, yShift, zShift, shiftAll);
	}

	public static void XYZShiftChannel(ImagePlus imp, int chToShift, int xShift, int yShift, int zShift, boolean shiftAll) {
		ImageStack imst=imp.getStack();
		int chs=imp.getNChannels(), sls=imp.getNSlices(), frms=imp.getNFrames();
		if(chToShift<1 || chToShift>chs) {IJ.error("Invalid channel to shift"); return;}
		int stfr=0, endfr=frms;
		if(shiftAll) {
			endfr=imp.getT();
			stfr=endfr-1;
			if(stfr<0)stfr=0;
		}
		int nfrms=endfr-stfr;
		IJ.log("Shifting channel "+chToShift+" by "+xShift+"px in x, "+yShift+"px in y, and "+zShift+"px in z across "+nfrms+" frame(s)");
		int sldir=1, slst=0, slend=sls;
		if(zShift>0) {
			sldir=-1;
			slst=sls-1;
			slend=-1;
		}
		for(int fr=stfr; fr<endfr; fr++) {
			for(int sl=slst; sl!=slend; sl+=sldir) {
				int targetSlice=sl-zShift;
				if(targetSlice<0 || targetSlice>=sls){
					imst.setProcessor(imst.getProcessor(imp.getStackIndex(chToShift, sl+1, fr+1)).createProcessor(imst.getWidth(), imst.getHeight()), imp.getStackIndex(chToShift, sl+1, fr+1));
					continue;
				}
				ImageProcessor ip=imst.getProcessor(imp.getStackIndex(chToShift, sl+1, fr+1));
				ImageProcessor targetip=null;
				if(targetSlice==sl) targetip=ip.duplicate();
				else{
					targetip=imst.getProcessor(imp.getStackIndex(chToShift, targetSlice+1, fr+1));
				}
				for(int y=0; y<ip.getHeight(); y++) {
					int yc=y+yShift;
					for(int x=0; x<ip.getWidth(); x++) {
						int xc=x+xShift;
						if(xc<0 || xc>=ip.getWidth() || yc<0 || yc>=ip.getHeight()) {
							int xs=xc<0?(ip.getWidth()+xc):xc;
							if(xs>=ip.getWidth())xs=xs-ip.getWidth();
							int ys=yc<0?(ip.getHeight()+yc):yc;
							if(ys>=ip.getHeight())ys=ys-ip.getHeight();
							ip.set(xs,ys,0);
						} else
							ip.set(xc,yc,targetip.get(x,y));
					}
				}
				IJ.showProgress((double)(fr*sls+sl+1)/(double)(nfrms*sls));
			}
		}
		String info=imp.getInfoProperty();
		info=(info!=null?info+"\n":"")+"Shifted channel "+chToShift+" by "+xShift+"x "+yShift+"y "+zShift+"z";
		imp.setProperty("Info", info);
		imp.updateAndDraw();
	}

	public static void StackCombiner() {
		ImagePlus imp1=WindowManager.getCurrentImage();
		if(imp1==null) {IJ.noImage(); return;}
		String[] titles=WindowManager.getImageTitles();
		if(titles==null || titles.length<2) {IJ.error("Need at least two images"); return;}
		String curImageTitle=imp1.getTitle();
		String[] blitters=new String[] {"COPY_ZERO_TRANSPARENT", "COPY", "AVERAGE", "MIN", "MAX", "OR"};
		int[] blitterVals=new int[] {Blitter.COPY_ZERO_TRANSPARENT, Blitter.COPY, Blitter.AVERAGE, Blitter.MIN, Blitter.MAX, Blitter.OR};
		GenericDialog gd=new GenericDialog("Combine Stacks");
		gd.addMessage("Select two images with point ROIs to combine into a single stack");
		gd.addChoice("Image 1", titles, curImageTitle);
		gd.addChoice("Image 2", titles, (titles[0].contentEquals(curImageTitle))?titles[1]:titles[0]);
		gd.addChoice("Blitter", blitters, blitters[0]);
		gd.showDialog();
		if(gd.wasCanceled()) return;
		imp1=WindowManager.getImage(gd.getNextChoice());
		ImagePlus imp2=WindowManager.getImage(gd.getNextChoice());
		StackCombiner(imp1, imp2, blitterVals[gd.getNextChoiceIndex()]);
	}

	public static void StackCombiner(ImagePlus imp1, ImagePlus imp2, int blitter){
		if(imp1==null || imp2==null) {IJ.error("Need two images"); return;}
		int chs1=imp1.getNChannels(), chs2=imp2.getNChannels(), sls1=imp1.getNSlices(), sls2=imp2.getNSlices(), frms1=imp1.getNFrames(), frms2=imp2.getNFrames();
		int sl1=imp1.getSlice(), sl2=imp2.getSlice();
		if(frms1!=frms2 || chs1!=chs2) {IJ.error("Images must have same number of frames and channels"); return;}
		Roi roi1=imp1.getRoi(), roi2=imp2.getRoi();
		if(roi1==null || roi2==null || roi1.getType()!=Roi.POINT || roi2.getType()!=Roi.POINT) {IJ.error("Both images must have point ROIs marking a common structure"); return;}
		ImageStack imst1=imp1.getStack(), imst2=imp2.getStack();
		int x1=roi1.getPolygon().xpoints[0], y1=roi1.getPolygon().ypoints[0], x2=roi2.getPolygon().xpoints[0], y2=roi2.getPolygon().ypoints[0];
		int xleftmax=Math.max(x1, x2), xrightmax=Math.max(imp1.getWidth()-x1, imp2.getWidth()-x2), ytopmax=Math.max(y1, y2), ybottommax=Math.max(imp1.getHeight()-y1, imp2.getHeight()-y2);
		ImageStack imstout=new ImageStack(xleftmax+xrightmax, ytopmax+ybottommax);
		int zup=Math.max(sl1, sl2), zdown=Math.max(sls1-sl1, sls2-sl2), zshift1=sl1-zup, zshift2=sl2-zup;
		for(int fr=0; fr<frms1; fr++) {
			for(int sl=0; sl<(zup+zdown); sl++){
				for(int ch=0; ch<chs1; ch++) {
					ImageProcessor ip=imp1.getProcessor().createProcessor(xleftmax+xrightmax, ytopmax+ybottommax);
					if((sl+zshift1)>=0 && (sl+zshift1)<sls1) {
						ImageProcessor ip1=imst1.getProcessor(imp1.getStackIndex(ch+1, sl+zshift1+1, fr+1));
						ip.copyBits(ip1, xleftmax-x1, ytopmax-y1, Blitter.COPY);
					}
					if((sl+zshift2)>=0 && (sl+zshift2)<sls2) {
						ImageProcessor ip2=imst2.getProcessor(imp2.getStackIndex(ch+1, sl+zshift2+1, fr+1));
						ip.copyBits(ip2, xleftmax-x2, ytopmax-y2, blitter);
					}
					imstout.addSlice("Fr"+(fr+1)+"_Sl"+(sl+1)+"_Ch"+(ch+1), ip);
				}
				IJ.showProgress((double)(fr*(zup+zdown)+sl+1)/(double)(frms1*(zup+zdown)));
			}
		}
		ImagePlus out=new ImagePlus(imp1.getTitle()+"_"+imp2.getTitle()+"_Combined", imstout);
		out.setOpenAsHyperStack(imp1.isHyperStack());
		out.setDimensions(chs1, zup+zdown, frms1);
		out.setCalibration(imp1.getCalibration());
		if(imp1 instanceof CompositeImage) {
			CompositeImage outc=new CompositeImage(out);
			outc.copyLuts(imp1);
			outc.setMode(((CompositeImage)imp1).getMode());
			out=outc;
		}
		out.show();

	}

	public static void runExternalCellpose(){
		String pythonPath=Prefs.get("AJ.cellposePythonPath", "");
		String tempdir=IJ.getDirectory("temp");
		String scriptPath=tempdir + "getCellposeAJTCT.py";
		if(!(new File(pythonPath)).exists()){
			IJ.showMessage("Please find cellpose3 python executable (the python.exe in your cellpose environment)");
			pythonPath=IJ.getFilePath("Find cellpose3 python executable (the python.exe in your cellpose environment)");
			if(pythonPath==null || pythonPath.isEmpty()) return;
			Prefs.set("AJ.cellposePythonPath", pythonPath);
		}
		try {
			PrintStream ps=new PrintStream(scriptPath);
			BufferedReader reader = new BufferedReader(new InputStreamReader(AJ_Misc_Plugins.class.getClassLoader().getResource("getCellposeAJTCT.py").openStream()));
			String contents="";
			String adder=reader.readLine();
			while(adder!=null) {
				contents+=adder+"\n";
				adder=reader.readLine();
			}
			ps.print(contents);
			ps.close();
		}catch(Exception e) {
				IJ.error("Error: Could not write getCellposeAJTCT.py file "+e.getMessage());
				return;
		}
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) return;
		String title=imp.getTitle();
		String dir=imp.getOriginalFileInfo().directory;
		int ch=0, sl=0;
		GenericDialog gd=new GenericDialog("Cellpose Segment");
		gd.addMessage("Cellpose Segment this file?:\n"+title);
		if(imp.getNChannels()>1){
			String[] chs=new String[imp.getNChannels()];
			for(int i=0; i<imp.getNChannels(); i++)chs[i]=""+(i+1);
			gd.addChoice("Cellpose channel to segment:", chs, chs[1]);
		}
		if(imp.getNSlices()>1){
			String[] sls=new String[imp.getNSlices()];
			for(int i=0; i<imp.getNSlices(); i++)sls[i]=""+(i+1);
			gd.addChoice("Cellpose slice to segment:", sls, sls[imp.getZ()-1]);
		}
		gd.showDialog();
		if(imp.getNChannels()>1)
			ch = gd.getNextChoiceIndex() + 1;
		if(imp.getNSlices()>1)
			sl = gd.getNextChoiceIndex() + 1;
		if(gd.wasCanceled())return;
		if(!title.endsWith(".tif")) title=title+".tif";
		if(dir==null || dir.isEmpty()){
			IJ.log("Dir empty trying macro");
			IJ.runMacro("getInfo(\"image.directory\")");
			String log=IJ.getLog();
			if(log!=null){
				String[] lines=log.split("\n");
				dir=lines[lines.length-1].trim();
				if(dir.startsWith("Dir empty"))dir=null;
			}
		}
		boolean tempSave=false;
		if(dir==null || dir.isEmpty()){
			dir=IJ.getDirectory("temp")+"ijcellpose"+File.separator;
			(new File(dir)).mkdir();
			IJ.saveAs("tiff", dir+title);
			IJ.log("Saved image to "+dir+title);
			tempSave=true;
		}
		String inputPath=(dir+title).replace("\\", "/");
		IJ.log("Running command: "+pythonPath+" "+scriptPath+" on input:");
		IJ.log(inputPath);
		ProcessBuilder pb = new ProcessBuilder(pythonPath,scriptPath,inputPath, ""+ch, ""+sl);
		try {
			Process p = pb.start();
			BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String line = "";
			while ((line = b.readLine()) != null) {
				IJ.log(line);
			}
			b.close();
			p.waitFor();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		String outputPath=inputPath.substring(0,inputPath.length()-4)+"-AJTCTcp.tif";
		outputPath=outputPath.replace("/","\\");
		IJ.wait(500);
		if((new File(outputPath)).exists()){ 
			IJ.open(outputPath);
			IJ.run("glasbey inverted");
			IJ.log("Cellpose output completed.");
			if(tempSave) WindowManager.getCurrentImage().changes=true;
			else IJ.save(outputPath);
		}else IJ.log("Error could not find AJTCTcp output file: "+outputPath);
	}
	
}
