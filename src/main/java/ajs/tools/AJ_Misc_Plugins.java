package ajs.tools;

import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import ij.*;
import ij.gui.GenericDialog;
import ij.gui.HistogramWindow;
import ij.gui.Overlay;
import ij.gui.Plot;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.CurveFitter;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
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
			hist.show();
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
		TextPanel results=null;
		if(WindowManager.getWindow("Results")!=null) {
			results=IJ.getTextPanel();
			nResults=results.getLineCount();
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
			for(int i=0;i<nResults;i++){
				ResultsTable rt=results.getResultsTable();
				means[i]=rt.getValueAsDouble(rt.getColumnIndex("Mean"), i);
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
			if(!rt.columnExists("X") || !rt.columnExists("Y") || ! rt.columnExists("Frame")){IJ.error("No Results or StackReg Results found");return null;}
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
	
	public static ImagePlus SkullLeveler() {
		return SkullLeveler(null);
	}
	
	public static ImagePlus SkullLeveler(ImagePlus oimp) {
		final String[] METHODS=new String[]{"Mean","Median","Mid","Top 1/3","MaxMean","X-Mean"};
		//Mean - Top 1/3 is calculated on each z-column to generate the threshold
		//MaxMean means take the mean of the z-max of channel 1
		//X-Mean just the mean of the current y-slice not the whole xy max proj of the skull
		//final String[] METHODS_MP=new String[]{"Min","Two-Mac"};
		//final String[] MAPTYPES=new String[]{"1st Channel - Skull", "2nd Channel - Mac bi-layer"};
		final String[] TMETHODS=new String[] {"First Frame", "Ave T-projection", "For Each Frame"};
		final int MINXMEANVAL=50;
		final String MAP_SUFFIX="-SkullLeveler-map";
		//String maptype=MAPTYPES[0];
		String tmethod=TMETHODS[0];
		int rfd=3;
		int red_fac=4;
		int rise=4;
		String method="X-Mean";
		int thresh=-1; //If defined, use this as the thresh instead of calculating
		int FIT=CurveFitter.POLY2;
		//boolean nonflatzero=false;
		
		if(oimp==null)oimp=WindowManager.getCurrentImage();
		if(oimp==null) {IJ.noImage();return null;}
		int h=oimp.getHeight(), w=oimp.getWidth(), chs=oimp.getNChannels(), sls=oimp.getNSlices(), frms=oimp.getNFrames();
		
		String[] imtitles=WindowManager.getImageTitles();
		ArrayList<String> mapnames=new ArrayList<String>();
		mapnames.add("Generate new map");
		for(int i=0;i<imtitles.length;i++) {
			if(imtitles[i].contains(MAP_SUFFIX)) {mapnames.add(imtitles[i]);}
		}
		String mapname="";
		
		String title=oimp.getTitle();
		String maptitle=title+MAP_SUFFIX;
		ImagePlus mapimp=null;
		GenericDialog gd=new GenericDialog("SkullLeveler");
		gd.addMessage("Working on "+title);
		//gd.addChoice("Use Channel and Type:",MAPTYPES,maptype);
		gd.addChoice("Threshold Method:",METHODS,method);
		if(oimp.getNFrames()>1)gd.addChoice("Map generation for frames?", TMETHODS, TMETHODS[2]);
		gd.addNumericField("XY-Smoothing",red_fac,0);
		gd.addNumericField("Z-Smoothing",rfd,0);
		gd.addNumericField("Static rise above skull bottom:",rise,0);
		gd.addNumericField("Use this thresh instead of method:",-1,0);
		//gd.addCheckbox("Nonflat zero?",nonflatzero);
		if(mapnames.size()>1)gd.addChoice("Use Open SkullMap:", (String[])mapnames.toArray(new String[mapnames.size()]), mapnames.get(0));
		gd.showDialog();
		if(gd.wasCanceled())return null;
		//maptype=gd.getNextChoice();
		method=gd.getNextChoice();
		if(oimp.getNFrames()>1)tmethod=gd.getNextChoice();
		red_fac=(int)gd.getNextNumber();
		rfd=(int)gd.getNextNumber();
		rise=(int)gd.getNextNumber();
		thresh=(int)gd.getNextNumber();
		//nonflatzero=gd.getNextBoolean();
		if(mapnames.size()>1)mapname=gd.getNextChoice();
		if(!mapname.contentEquals("Generate new map"))mapimp=WindowManager.getImage(mapname);
		
		ImageStack mapstack=null;
		FloatProcessor fp=null;
		int zmin=65535, zmax=0;
		
		if(mapimp==null) {
			Duplicator dup=new Duplicator();
			int ffrms=tmethod.contentEquals(TMETHODS[0])?1:oimp.getNFrames();
			int mapch=1;
			//if(maptype.contentEquals(MAPTYPES[1])){
			//	mapch=2;
			//	if(!(method.contentEquals("Min")|| method.contentEquals("Two-Mac")))method="CurveFit";
			//}
			ImagePlus dimp=dup.run(oimp,mapch,mapch,1,oimp.getNSlices(),1,ffrms);
			dimp.show();
			ffrms=tmethod.contentEquals(TMETHODS[2])?frms:1;
			dimp=resizer(dimp, dimp.getWidth()/red_fac, dimp.getHeight()/red_fac, sls, (tmethod.contentEquals(TMETHODS[2])?frms:1));
			Calibration cal=dimp.getCalibration();
			cal.fps=1; cal.pixelWidth=1; cal.pixelHeight=1; cal.pixelDepth=1;
			dimp.setCalibration(cal);
			ij.plugin.filter.RankFilters rf=new ij.plugin.filter.RankFilters();
			ImageStack dst=dimp.getStack();
			for(int i=0;i<dst.getSize();i++) {
				ImageProcessor ip=dst.getProcessor(i+1);
				rf.rank(ip, 100/red_fac, ij.plugin.filter.RankFilters.MEDIAN);
			}
			if(method.contentEquals("MaxMean")){
				ImagePlus zimp=ij.plugin.ZProjector.run(dimp,"Max Intensity");
				ImageProcessor ip=zimp.getProcessor();
				int mean=0;
				for(int y=0;y<zimp.getHeight();y++)
					for(int x=0;x<zimp.getWidth();x++)
						mean+=ip.get(x,y);
				mean/=(zimp.getHeight()*zimp.getWidth());
				thresh=mean;
				IJ.log("SkullLeveler using mean: "+mean);
			}
			int rh=dimp.getHeight(), rw=dimp.getWidth(), rfrms=dimp.getNFrames();
	
			mapstack=new ImageStack(rw,rh);
			CurveFitter cf=null;
			double[] xline=new double[sls];
			for(int i=0;i<sls;i++)xline[i]=(double)i;
			
			//IJ.log("rfrms"+rfrms+" dimpf"+dimp.getNFrames());
			for(int fr=0;fr<rfrms;fr++) {
				fp=new FloatProcessor(rw,rh);
				for(int y=0;y<rh;y++){
					if(method.contentEquals("X-Mean")){
						int mean=0;
						for(int x=0;x<rw;x++){
							for(int z=0;z<sls;z++){
								int val=dst.getProcessor(z+fr*sls+1).get(x,y);
								if(val>MINXMEANVAL)mean+=val;
							}
						}
						mean/=(rw*sls);
						thresh=mean;
					}
					for(int x=0;x<rw;x++){
						double[] zline=new double[sls];
						for(int z=0;z<sls;z++){
							zline[z]=(double)dst.getProcessor(z+fr*sls+1).get(x,y);
						}
						int skullz=0;
						if(method.contentEquals("CurveFit")) {
							cf=new CurveFitter(xline,zline);
							cf.doFit(FIT);
							if(cf.getStatus()!=ij.measure.Minimizer.INITIALIZATION_FAILURE){
								double[] params=cf.getParams();
								if(FIT==CurveFitter.POLY2){
									//for ax2+bx+c=y, params are returned [0] is c, [1] is b, [2] is a.
									//and the minimum of a polynomial is -b/2a
									//CurveFitter actually calls the parameters cx2+bx+a instead of ax2+bx+c
									int ytmp=(int)(-params[1]/(2*params[2]));
									if(ytmp>0 && ytmp<(h-1))skullz=ytmp;
								}else if(FIT==CurveFitter.POLY4){
									for(int i=0;i<h;i++){
										//try to find where two peaks are then get the middle?
									}
								}
							}
						}else if(method.contentEquals("Two-Mac")){
							//mac bilayer
						}else {
							int[] zlineThresh=Diameter_Profile.getThresh(zline,rfd,method,false,thresh);
							for(int i=zlineThresh.length-1;i>=0;i--){
								if(zlineThresh[i]>0){skullz=i; break;}
							}
						}
						skullz+=-rise;
						fp.setf(x, y, (float)skullz);
						//xs[x]=x; 
						if(skullz<zmin)zmin=skullz;
						if(skullz>zmax)zmax=skullz;
					}
				}
				mapstack.addSlice(fp);
			}
			mapimp=new ImagePlus(maptitle,mapstack);
			java.awt.image.IndexColorModel cm=ij.plugin.LutLoader.getLut("Thermal");
			if(cm!=null)
				mapimp.setLut(new ij.process.LUT(cm,zmin,zmax));
			mapimp.show();
			dimp.changes=false;
			dimp.close();
			mapimp.setProperty("Info","Z-Range:"+zmin+":"+zmax+"\n"+"Method:"+tmethod);
		}else{
			fp=(FloatProcessor)mapimp.getProcessor();
			mapstack=mapimp.getStack();
			int miw=mapimp.getWidth();
			red_fac=w/miw;
			//String zrange=mapimp.getInfoProperty().split("\n")[0];
			//zmin=Integer.parseInt(zrange.split(":")[1]);
			//zmax=Integer.parseInt(zrange.split(":")[2]);
		}
		
		ImagePlus finalimp=IJ.createImage(oimp.getTitle()+"-SkullLeveled", ""+oimp.getBitDepth()+"-bit"+(oimp.isComposite()?" composite":""), w, h, chs, sls, frms);
		boolean fullstack=mapstack.getSize()==frms;

		for(int fr=0; fr<frms; fr++){
			if(fullstack)fp=(FloatProcessor)(mapstack.getProcessor(fr+1));
			for(int sl=0; sl<sls; sl++){
				IJ.showStatus("SkullLeveling "+" T"+(fr+1)+" Z"+(sl+1));
				for(int ch=0; ch<chs; ch++){
					IJ.showProgress((double)(ch+sl*chs+fr*sls*chs+1)/(double)(chs*sls*frms));
					ImageProcessor ip=finalimp.getStack().getProcessor(finalimp.getStackIndex(ch+1,sl+1,fr+1));
					for(int y=0; y<h; y++){
						for(int x=0; x<w; x++){
							int xfp=Math.min(x/red_fac, fp.getWidth()-1);
							int yfp=Math.min(y/red_fac, fp.getHeight()-1);
							int zskull=(int)fp.getf(xfp,yfp);
							int value=0;
							if(((sl+zskull)<sls) && ((sl+zskull)>=0)){
								value=oimp.getStack().getProcessor(oimp.getStackIndex(ch+1,sl+zskull+1,fr+1)).get(x,y);
							}
							ip.set(x,y,value);
						}
					}
				}
			}
		}
		finalimp.setCalibration(oimp.getCalibration());
		finalimp.show();
		if(finalimp instanceof CompositeImage && oimp instanceof CompositeImage)((CompositeImage)finalimp).setLuts(((CompositeImage)oimp).getLuts());
		finalimp.updateAndDraw();
		IJ.showStatus("Skull-Leveler complete");
		return finalimp;
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
}
