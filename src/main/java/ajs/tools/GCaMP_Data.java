package ajs.tools;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.util.ArrayList;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.gui.WaitForUserDialog;
import ij.gui.YesNoCancelDialog;
import ij.io.FileInfo;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import sc.fiji.skeletonize3D.Skeletonize3D_;
import sc.fiji.analyzeSkeleton.*;

public class GCaMP_Data implements PlugIn {

	public static final double STDEVMULT=3.0;
	public static final int BOXW=200;
	public static final int BOXH=200;
	public static final int GAP=4;
	
	ImagePlus imp=null;
	String spath="";
	ResultsTable table=null;
	int myroi=0; //1-index
	int baselinefr=-1;
	int chs,frms, fra, frb;
	double[] times=null;
	
	/**
	 * First copies the mean brightness data from the current selection or myroi ROI number from RoiManager
	 * then waits for the user to copy to Excel, then creates a simple figure with the left being the least 
	 * bright baseline frame and the right side being the brightest post-event frame. It saves the rois and
	 * the figure tif in the save folder of the ImagePlus.
	 * 
	 * @param arg Can include the RoiManager roi to work with
	 */
	public void run(String arg) {
		if(arg!=null && !arg.equals("") && AJ_Utils.parseIntTP(arg)>0)myroi=AJ_Utils.parseIntTP(arg);
		boolean makeFigures=true;
		boolean autoSave=true;
		boolean onlySelected=false;
		boolean chooseFrame=false;
		
		if(imp==null) imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage();return;}

		String ititle=imp.getTitle();
		chs=imp.getNChannels();
		frms=imp.getNFrames();
		int gch=Math.max(0,chs-2)+1;

		String ttitle="GCaMP_Data-"+ititle+".csv";
		table=ResultsTable.getResultsTable(ttitle);
		ResultsTable oldtable=table;
		
		FileInfo fi = imp.getOriginalFileInfo();
		if (fi!=null && fi.directory!=null) spath= fi.directory;
		if(spath=="")spath=IJ.getDirectory("");
		if(spath=="") {IJ.error("Please save image in correct directory");return;}

		RoiManager roiManager=RoiManager.getRoiManager();
		boolean redefDiameter=false;
		if(roiManager.getSelectedIndexes().length==1) {
			YesNoCancelDialog ynd=new YesNoCancelDialog(null, "Selected ROI", "An ROI is selected:", "Redo Diameter", "Redo ROI");
			ynd.show();
			if(ynd.cancelPressed())return;
			if(ynd.yesPressed())redefDiameter=true;
			myroi=roiManager.getSelectedIndex()+1;
		}
		if(redefDiameter) {
			if(table==null) {IJ.error("To redefine Diameter, you need a populated table");return;}
			if(myroi==0){IJ.error("Error with selected Roi in RoiManager");return;}
			Roi roi=imp.getRoi();
			Roi lineroi=getSkeletonWideLine(imp);
			imp.setRoi(lineroi);
			WaitForUserDialog wfu=new WaitForUserDialog("Diameter","Draw Line Roi with width over axon. Also set ch,sl,fr.");
			wfu.show();
			lineroi=imp.getRoi();
			if(lineroi==null) {IJ.log("No change to diameter");return;}
			double diameter=Diameter_Profile.getDiameter(imp, false);
			table.setValue(""+myroi, 0, diameter);
			table.show(ttitle);
			IJ.log("Diameter "+myroi+" changed to "+diameter);
			imp.setRoi(roi);
			return;
		}
		//Select Roi if myroi argument passed
		if(myroi>0)roiManager.select(myroi-1);
		//Also need to see if there is already an Roi selected
		Roi roi=imp.getRoi();
		if(roi==null && roiManager.getCount()>0 && table==null) {
			GenericDialog gd=new GenericDialog("Fill Table");
			ArrayList<String> choices=new ArrayList<String>();
			choices.add("All rois in RoiManager");
			if(roiManager.getSelectedIndexes().length>0)choices.add("Selected Roi(s)");
			choices.add("No");
			gd.addChoice("Recalculate?", choices.toArray(new String[choices.size()]),choices.get(0));
			String[] chstr=new String[chs];
			for(int i=0;i<chs;i++)chstr[i]=""+(i+1);
			gd.addChoice("Get Diameter from Channel:",chstr,""+gch);
			gd.addCheckbox("Choose ch,sl,fr for each ROI diameter?", chooseFrame);
			gd.addCheckbox("Redo figures?", false);
			gd.addCheckbox("Save Figures?", false);
			gd.showDialog();
			if(gd.wasCanceled())return;
			String choice=gd.getNextChoice();
			if(choice.contentEquals("No"))return;
			if(choice.contentEquals("Selected Rois"))onlySelected=true;
			gch=AJ_Utils.parseIntTP(gd.getNextChoice());
			chooseFrame=gd.getNextBoolean();
			makeFigures=gd.getNextBoolean();
			autoSave=gd.getNextBoolean();
			myroi=roiManager.getCount();
		}else if(roi==null) {IJ.error("Need Selection");return;}
		baselinefr=Time_Extractor.getEventFrameFromInfo(imp,true);
		if(baselinefr<1) {IJ.error("Please define CSD or event frame"); return;}
		times=Time_Extractor.extractTimes(imp, false, Time_Extractor.SubTime.EVENT_NO_SET);
		boolean updateShowAll=false;
		if(myroi<1){
			roi.setPosition(0, imp.getZ(), 0);
			roiManager.add(imp.getRoi(), -1);
			roiManager.runCommand("save", spath+"RoiSet.zip");
			myroi=roiManager.getCount();
			updateShowAll=true;
		}

		if(table==null || !"Diameter".equals(table.getLabel(0))) {
			table=new ResultsTable(frms*chs+2);
			for(int i=0;i<times.length; i++) {
				if(i==0) {
					table.setLabel("Diameter", 0);
					table.setLabel("Onset", 1);
				}
				table.setLabel(""+times[i],i+2);
			}
			if(oldtable!=null) {
				for(int c=0;c<=oldtable.getLastColumn();c++) {
				if(!oldtable.getColumnHeading(c).contentEquals("Label")) {
					for(int r=0;r<oldtable.size();r++)
						table.setValue(oldtable.getColumnHeading(c), r, oldtable.getValueAsDouble(c, r));
					}
				}
				table.show(ttitle);
			}
		}

		int rois=Math.min(myroi-1,table.getLastColumn()+1);
		int curroi=myroi;
		int[] sis=roiManager.getSelectedIndexes();
		for(int i=rois; i<curroi;i++) {
			myroi=i+1;
			if(onlySelected) {
				boolean found=false;
				for(int j=0;j<sis.length;j++) {if((myroi-1)==sis[j])found=true;}
				if(!found)continue;
			}
			WindowManager.setCurrentWindow(imp.getWindow());
			imp.deleteRoi();
			roiManager.select(myroi-1);
			double[] means=getStackRoiMeans(imp, new boolean[] {true, false, true});
			for(int j=0; j<means.length;j++) {
				table.setValue(""+myroi, j+2, means[j]);
			}
			double onset=getOnsetAndSetMinMaxFrms(means);
			table.setValue(""+myroi, 1, onset);
			imp.setPosition(gch,imp.getZ(),frb);
			if(chooseFrame) {
				WaitForUserDialog wfu=new WaitForUserDialog("Diameter","Choose the best ch,sl,fr for ROI "+ myroi+".");
				wfu.show();
				if(wfu.escPressed())return;
			}
			double diameter=getDiameter(imp);
			table.setValue(""+myroi, 0, diameter);
			table.show(ttitle);
			String printer="";
			for(int j=0;j<means.length; j++) {
				printer+=means[j];
				if(j<(means.length-1))printer+="\n";
			}
			if(makeFigures) {
				ImagePlus figimp=genFigureImage(imp,printer,myroi,fra,frb);
				if(autoSave) {
					IJ.save(figimp, spath+figimp.getTitle());
					IJ.saveAs(figimp,"jpeg", spath+figimp.getTitle()+".jpg");
				}
				roiManager.runCommand("deselect");
			}
		}
		if(updateShowAll) {
			WindowManager.setCurrentWindow(imp.getWindow());
			imp.deleteRoi();
			roiManager.runCommand("show none");
			roiManager.runCommand("show all with labels");
		}
	}
	
	private double getOnsetAndSetMinMaxFrms(double[] means) {
		double min=65535, max=0, stdev=0, sum=0, sum2=0, mean=0;
		int onsetfr=-1;
		for(int i=0;i<frms;i++){
			double m=(means[i*chs+(Math.max(0,chs-2))]);
			// above div by (chs==3?means[i*chs]:1)
			if(i<baselinefr) {
				if(min>m){min=m; fra=i+1;}
				sum+=m; sum2+=m*m;
			}
			if(max<m){max=m; frb=i+1;}
			if(i==baselinefr) {
				double n=(double)baselinefr;
				stdev=Math.sqrt(((n*sum2-sum*sum)/n)/(n-1.0));
				mean=sum/n;
				stdev=stdev/mean;
			}
			if(i>=baselinefr) {
				if(onsetfr==-1 && (m/mean)>(1+stdev*STDEVMULT)) onsetfr=(i+1);
			}
		}
		return (onsetfr==-1?-1.0:(times[onsetfr]/60.0));
	}
	
	public static double getDiameter(ImagePlus imp) {
		double diameter=0.0;
		Roi roi=imp.getRoi();
    	int area=roi.getContainedPoints().length;
		PolygonRoi lineroi=getSkeletonWideLine(imp);
        imp.setRoi(lineroi);
        diameter=Diameter_Profile.getDiameter(imp, false);
        if(diameter<=0.0) {
        	diameter=(double) lineroi.getStrokeWidth();
        	double d=Math.sqrt((double)area/Math.PI)*2.0;
        	if(diameter>d)diameter=d;
        	diameter*=imp.getCalibration().pixelWidth;
        }
        imp.setRoi(roi);
        return diameter;
	}
	
	public static PolygonRoi getSkeletonWideLine(ImagePlus imp) {
		Roi roi=imp.getRoi();
		int type=roi.getType();
		if(type==Roi.LINE || type==Roi.POLYLINE || type==Roi.FREELINE) {
			return new PolygonRoi(roi.getFloatPolygon().xpoints, roi.getFloatPolygon().ypoints, roi.getFloatPolygon().npoints);
		}
		int area=roi.getContainedPoints().length;
        Rectangle rb=roi.getBounds();
        ImageStack st=new ImageStack();
        ImageProcessor ip=imp.getMask();
		st.addSlice(ip);
		ImagePlus mask=new ImagePlus("mask",st);
		Skeletonize3D_ sk=new Skeletonize3D_();
		sk.setup("", mask);
		sk.run(mask.getStack().getProcessor(1));
		AnalyzeSkeleton_ ansk=new AnalyzeSkeleton_();
		ansk.setup("",mask);
		ansk.run(AnalyzeSkeleton_.NONE, false, true, null, true, false);
		mask.close();
		ArrayList<sc.fiji.analyzeSkeleton.Point> pts=ansk.getShortestPathPoints()[0];
		int n=pts.size();
	    int[] xpoints=new int[n],ypoints=new int[n];
        for(int i=0;i<pts.size();i++){
        	xpoints[i]=pts.get(i).x;
        	ypoints[i]=pts.get(i).y;
        }
        PolygonRoi lineroi=new PolygonRoi(xpoints,ypoints,n, Roi.FREELINE);
        Rectangle lb=lineroi.getBounds();
        int stwidth=(area/n);
        //IJ.log("area "+area+" n"+n+" w"+stwidth);
        lineroi.setLocation(rb.x+lb.x, rb.y+lb.y);
        lineroi.updateWideLine(Math.max(3, stwidth));
        return lineroi;
	}
	
	public static ImagePlus genFigureImage(ImagePlus imp, String printer, int myroi, int fra, int frb) {
		Roi roi=(Roi)imp.getRoi().clone();
		int w=imp.getWidth(), h=imp.getHeight(), chs=imp.getNChannels();
		String ititle=imp.getTitle();
		Rectangle sb=roi.getBounds();
		int x=sb.x, y=sb.y, selw=sb.width, selh=sb.height;
		int xc=x+selw/2, yc=y+selh/2;
		//print(""+xc+" "+yc);
		int dx=BOXW/2-selw/2, dy=BOXH/2-selh/2;
		//print(""+x+"  "+y);
		//print("Before: "+dx+"  "+dy);
		if((x+selw)<(BOXW/2+selw/2))dx=x;
		if((y+selh)<(BOXH/2+selh/2))dy=y;
		//print("AFter: "+dx+"  "+dy);
		if(x>(w-BOXW/2-selw/2))dx=x-(w-BOXW);
		if(y>(h-BOXH/2-selh/2))dy=y-(h-BOXH);
		
		Rectangle rect=new Rectangle(Math.min(w-BOXW, Math.max(xc-BOXW/2,0)),Math.min(h-BOXH,Math.max(yc-BOXH/2,0)), BOXW, BOXH);
		Roi rectRoi=new Roi(rect);
		imp.setRoi(rectRoi);
		
		imp.setT(fra);
		WindowManager.setTempCurrentImage(imp);
		IJ.run("Duplicate...", " ");
		IJ.run("Canvas Size...", "width="+(BOXW*2+GAP)+" height="+BOXH+" position=Top-Left zero");
		ImagePlus figimp=WindowManager.getCurrentImage();
		String title=""+myroi+"ab-"+ititle;
		if(!title.endsWith(".tif"))title=title+".tif";
		figimp.setTitle(title);
		imp.setT(frb);
		for(int ch=0;ch<chs;ch++) {
			imp.setC(ch+1);
			figimp.setC(ch+1);
			ImageProcessor ip=imp.getProcessor(), fip=figimp.getProcessor();
			for(int ix=0; ix<BOXW; ix++) {
				for(int iy=0;iy<BOXH;iy++) {
					fip.set(BOXW+GAP+ix,iy,ip.get(ix+rect.x,iy+rect.y));
				}
			}
		}
		figimp.setColor(new java.awt.Color(255,255,255));
		if(sb.getHeight()>BOXH || sb.getWidth()>BOXW)roi=(new ShapeRoi(new Roi(dx+BOXW+GAP,0,BOXW,BOXH))).and(new ShapeRoi(roi));
		figimp.setRoi(roi);
		roi.setLocation(dx, dy);
		ImageProcessor ip2=figimp.getStack().getProcessor(Math.max(1, chs-1));
		ImageProcessor ip3=figimp.getStack().getProcessor(chs);
		ip2.setValue(65535.0);
		ip3.setValue(65535.0);
		roi.drawPixels(ip2);
		roi.drawPixels(ip3);
		roi.setLocation(dx+BOXW+GAP, dy);
		figimp.setRoi(roi);
		roi.drawPixels(ip2);
		roi.drawPixels(ip3);
		figimp.updateAndDraw();
		
		
		String info=figimp.getInfoProperty();
		if(info==null || info.contentEquals(""))info=printer;
		else {
			if(info.endsWith("\n"))info+=printer;
			else info+="\n"+printer;
		}
		figimp.setProperty("Info", info);
		figimp.deleteRoi();
		return figimp;
	}
	
	public static void copyStackRoiMeans() {
		double[] out=getStackRoiMeans(null, null);
		copyDoubleArray(out);
	}
	
	public static double[] getStackRoiMeans(ImagePlus imp, boolean[] csf) {
		if(imp==null)imp=WindowManager.getCurrentImage();
		ij.measure.Calibration cal=imp.getCalibration();
		Roi roi=imp.getRoi();
		int sls=imp.getNSlices(), frms=imp.getNFrames(),chs=imp.getNChannels();
		int slst=1,slend=sls, frst=1,frend=frms, chst=1,chend=chs;

		if(csf==null || csf.length!=3){
			GenericDialog gd= new GenericDialog("");
			gd.addCheckbox("Channels ("+chs+")", true);
			gd.addCheckbox("Slices ("+sls+")", false);
			gd.addCheckbox("Frames ("+frms+")", true);
			gd.showDialog();
			if(gd.wasCanceled())return null;
			csf=new boolean[3];
			csf[0]=gd.getNextBoolean();
			csf[1]=gd.getNextBoolean();
			csf[2]=gd.getNextBoolean();
			
		}
		if(!csf[0]){
			chend=imp.getC(); chst=chend;
		}
		if(!csf[1]){
			slend=imp.getZ(); slst=slend;
		}
		if(!csf[2]){
			frend=imp.getT(); frst=frend;
		}
		
		frms=frend-frst+1; sls=slend-slst+1; chs=chend-chst+1;
		double totn=chs*sls*frms;
		double[] out=new double[chs*sls*frms];
		for(int fr=frst; fr<=frend;fr++) {
			for(int sl=slst; sl<=slend; sl++) {
				for(int ch=chst; ch<=chend; ch++) {
					ImageProcessor ip=imp.getStack().getProcessor(imp.getStackIndex(ch, sl, fr));
					ip.setRoi(roi);
					ImageStatistics imgstat=ImageStatistics.getStatistics(ip, 127, cal);
					out[(chs*sls*(fr-frst))+(chs*(sl-slst))+ch-chst]=imgstat.mean;
					IJ.showProgress((double)(((fr-frst+1)*chs*sls)+((sl-slst+1)*chs)+ch-chst+1)/totn);
				}
			}
		}
		IJ.showStatus("Stack Measured!");
		return out;
	}
	
	public static void copyDoubleArray(double[] out) {
		String printer="";
		for(int i=0;i<out.length;i++)printer+=""+out[i]+"\n";
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(printer), null);
		IJ.showStatus("Stack Measured and Copied!");
	}
	
	
	public static void altTabPaste() {
		
	    try {
	        Robot r = new Robot();
	        r.keyPress(KeyEvent.VK_ALT);
	        r.keyPress(KeyEvent.VK_TAB); //Windows button is still pressed at this moment
	        r.keyRelease(KeyEvent.VK_TAB);
	        r.keyRelease(KeyEvent.VK_ALT);    
	        IJ.wait(500);
	        r.keyPress(KeyEvent.VK_CONTROL);
	        r.keyPress(KeyEvent.VK_V); //Windows button is still pressed at this moment
	        r.keyRelease(KeyEvent.VK_V);
	        r.keyRelease(KeyEvent.VK_CONTROL);
	        IJ.wait(2500);
	        r.keyPress(KeyEvent.VK_ALT);
	        r.keyPress(KeyEvent.VK_TAB); //Windows button is still pressed at this moment
	        r.keyRelease(KeyEvent.VK_TAB);
	        r.keyRelease(KeyEvent.VK_ALT);   
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	}
}
