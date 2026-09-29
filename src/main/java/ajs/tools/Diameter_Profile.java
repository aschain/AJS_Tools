package ajs.tools;
import ij.plugin.PlugIn;
import ij.text.TextWindow;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.*;
import ij.gui.*;
import ij.process.*;
import java.util.*;

import java.awt.Point;
import java.awt.Polygon;


public class Diameter_Profile implements PlugIn {

	final String[] THRESHLEVELS=new String[] {"Mean","Median","Mid","Top 1/3"};
	final static int ianglemax=5;
	final static int AVE_IS_DARK=3;
	
	
	/**
	 * This method gets called by ImageJ / Fiji.
	 *
	 * @param arg can be specified in plugins.config
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	@Override
	public void run(String arg){
		String plottype="line";
		int rfd=3;
		
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) {
			IJ.noImage();
			return;
		}
		Roi roi=imp.getRoi();
		if(roi==null) {
			IJ.error("Please select an ROI first, like a line across a blood vessel");
			return;
		}
		int sl=imp.getSlice(), sls=imp.getNSlices(), fr=imp.getFrame(), frms=imp.getNFrames(),chs=imp.getNChannels(), ch=imp.getC();
		boolean slicestack=false;
		if(frms==1 && sls>1) {
			slicestack=true;
			frms=sls;
		}
		String title=imp.getTitle();
		int stype=roi.getType();
		if(stype!=Roi.LINE && stype!=Roi.POLYLINE && stype!=Roi.FREELINE && stype!=Roi.RECTANGLE) {
			IJ.error("Diameter Profile requires a line (at 90 degrees), polyline (along the vessel), or rectangle ROI");
			return;
		}
		boolean isDark=false;
		if(stype==Roi.LINE){
			ProfilePlot ptpp=new ProfilePlot(imp);
			double[] ptp=ptpp.getProfile();
			if(ptp.length>(AVE_IS_DARK*3)) {
				double edge=0, center=0;
				for(int i=0; i<AVE_IS_DARK;i++) {
					edge+=ptp[i]+ptp[ptp.length-1-i];
					center+=ptp[ptp.length/2-1+i];
				}
				edge/=(AVE_IS_DARK*2);
				center/=AVE_IS_DARK;
				if(edge>center)isDark=true;
			}
		}
		double strokeWidth = roi.getStrokeWidth();
		int ianglejump=1;
		String tool=IJ.getToolName();

		int[] ints=AJ_Utils.getInfoLineInts(imp.getInfoProperty(), new String[]{"Event at", "CSD at"});
		int eventFrame=0;
		if(ints!=null && ints.length>0)eventFrame=ints[0];
		
		GenericDialog gd=new GenericDialog("Diameter Profile");
		gd.addChoice("Thresh level:", THRESHLEVELS, Prefs.get("AJ.Diameter_Profile.threshlevel", "Mean"));
		gd.addCheckbox("Vessel is dark?", isDark);
		if(stype==Roi.RECTANGLE)gd.addCheckbox("Vertical?",false);
		if(stype==Roi.LINE ||stype==Roi.POLYLINE || stype==Roi.FREELINE)gd.addNumericField("Widen line (averaging)", strokeWidth, 0);
		if(stype==Roi.POLYLINE || stype==Roi.FREELINE) {
			gd.addNumericField("Skip every n along line", ianglejump, 0);
		}
		if(chs>1)gd.addStringField("Channel:",""+imp.getChannel());
		gd.addNumericField("Diameter running ave:",rfd,0);
		if(eventFrame==0) gd.addNumericField("Set Event frame?", 0, 0);
		gd.addCheckbox("Summarize?",false);
		gd.showDialog();
		
		if(gd.wasCanceled())return;
	  
		String threshLevel=gd.getNextChoice();
		Prefs.set("AJ.Diameter_Profile.threshlevel", threshLevel);
		boolean invert=gd.getNextBoolean();
		boolean vertical=false;
		if(stype==Roi.RECTANGLE)vertical=gd.getNextBoolean();
		if(stype==Roi.LINE||stype==Roi.POLYLINE || stype==Roi.FREELINE) {
			strokeWidth=gd.getNextNumber();
			if(strokeWidth<1)strokeWidth=1;
			roi.setStrokeWidth(strokeWidth);
			roi.updateWideLine((int)strokeWidth);
			if(stype==Roi.POLYLINE || stype==Roi.FREELINE)ianglejump=(int)gd.getNextNumber();
		}
		if(chs>1){
			ch=AJ_Utils.parseIntTP(gd.getNextString());
			imp.setPosition(ch,sl,fr);
		}
		int myrfd=(int)gd.getNextNumber();
		if(eventFrame==0) {
			eventFrame=(int)gd.getNextNumber();
			if(eventFrame>0) {
				AJ_Utils.setInfoLineInts(imp, new String[]{"Event at"}, new int[]{eventFrame});
			}
		}
		boolean csdSummary=gd.getNextBoolean();
		Prefs.savePreferences();
	  
		double[] times=new double[frms];
		double frint=0;
		double px=1;
		Calibration cal=imp.getCalibration();
		String[] headings=null;
		if(cal!=null && cal.frameInterval>0) {
			frint=cal.frameInterval;
			headings=new String[3];
			String timeunit=cal.getTimeUnit();
			headings[1]="Frame ("+timeunit+")";
			headings[2]="Diameter";
			times=Time_Extractor.extractTimes(imp, slicestack, Time_Extractor.SubTime.EVENT_NO_SET);
		}else {
			headings=new String[2];
			headings[1]="Diameter";
		}
		headings[0]="Frame";

		if(cal!=null && cal.pixelWidth>0){
			px=cal.pixelWidth;
			headings[headings.length-1]+=" ("+cal.getUnit()+")";
		}
		
		double[][] tp=new double[frms][];
		ProfilePlot tpp;
		int xMax=0,xAveMax=0;
		
		ImageProcessor ip=null;
		ImagePlus dimp=null;
		
		Polygon pline=roi.getPolygon();
		double[] angle=new double[pline.npoints];
		if(stype==Roi.POLYLINE || stype==Roi.FREELINE) {
			for(int il=ianglemax;il<pline.npoints-ianglemax;il+=ianglejump) {
				for(int ila=0;ila<ianglemax;ila++) angle[il]+=Math.atan2(pline.ypoints[il+ila]-pline.ypoints[il-ila], pline.xpoints[il+ila]-pline.xpoints[il-ila]);
				angle[il]/=ianglemax;
			}
		}
		
		
		
		for(int k=0;k<frms;k++){
			if(slicestack) imp.setPosition(ch,k+1,fr);
			else imp.setPosition(ch,sl,k+1);
			while(IJ.spaceBarDown()) IJ.wait(300);
			
			if(stype==Roi.RECTANGLE) {
				tpp=new ProfilePlot(imp,vertical);
				tp[k]=tpp.getProfile();
			} else if(stype==Roi.LINE) {
				tpp=new ProfilePlot(imp);
				tp[k]=tpp.getProfile();
			} else if(stype==Roi.POLYLINE || stype==Roi.FREELINE) {
				double[] temptp;
				tp[k]=new double[(int)strokeWidth];
				int ianglelen=0;
				for(int il=ianglemax;il<pline.npoints-ianglemax;il+=ianglejump) {
					int x=pline.xpoints[il], y=pline.ypoints[il];
					Line tline=new Line(x+strokeWidth/2*Math.cos(angle[il]-Math.PI/2), y+strokeWidth/2*Math.sin(angle[il]-Math.PI/2), x+strokeWidth/2*Math.cos(angle[il]+Math.PI/2), y+strokeWidth/2*Math.sin(angle[il]+Math.PI/2));
					tline.setStrokeWidth(rfd);
					imp.setRoi(tline);
					tpp=new ProfilePlot(imp);
					temptp=tpp.getProfile();
					for(int iln=0;iln<strokeWidth;iln++)tp[k][iln]+=iln<temptp.length?temptp[iln]:0;
					ianglelen++;
				}
				for(int iln=0;iln<strokeWidth;iln++)tp[k][iln]/=ianglelen;
			}
			
			if(k==0) {
				xMax=tp[0].length;
				xAveMax=xMax-myrfd+1;
				dimp=IJ.createImage(imp.getTitle()+"-diameters", xAveMax, frms, 1, 8);
				dimp.show();
				ip=dimp.getProcessor();
			}
			
			int[] thresh=getThresh(tp[k],myrfd,threshLevel, invert);
			for(int x=0;x<thresh.length;x++) ip.set(x,k,thresh[x]);
		}
		
		if(stype==Roi.POLYLINE || stype==Roi.FREELINE) imp.setRoi(roi);
		
		dimp.updateAndRepaintWindow();
		IJ.setTool(3); //drawSelection tool
		WindowManager.setCurrentWindow(dimp.getWindow());
		dimp.getWindow().setLocation(new Point(300,300));
		WaitForUserDialog.setNextLocation(300+dimp.getWindow().getWidth()+20, 300);
		WaitForUserDialog wfu=new WaitForUserDialog("Thresh Fixer","Fix threshes then hit OK");
		wfu.show();
		if(wfu.escPressed())return;

		String tabletitle=title+"-diameters";
		ResultsTable drt=new ResultsTable();
		int[] thresh=new int[xAveMax];
		int di=frint>0?2:1;
		for(int k=0;k<frms;k++){
			int diameter=0;
			for(int x=0;x<xAveMax;x++) {
				thresh[x]=ip.get(x,k);
				if(thresh[x]==255)diameter++;
			}
			diameter+=2*myrfd;
			int n=drt.getCounter();
			drt.setValue(headings[0], n, k+1);
			if(frint>0) {
				drt.setValue(headings[1], n, times[k]);
			}
			drt.setValue(headings[headings.length-1], n, diameter*px);
		}
		drt.show(tabletitle);
		
		if(frms>1) {
			Plot plot=new Plot("Diameter over time","Time","Diameter");
			//plot.setLimits(0, xMax, min, max);
			double[] diameters=drt.getColumnAsDoubles(di);

			int dmin=-1,dmax=-1,dstart=-1, dstartConstr=-1, dendConstr=-1, dendDilation=-1;
			double dminVal=65535, dmaxVal=0, aveBase=0, aveBaseFinal=0;
			if(csdSummary) {
				for(int i=0;i<diameters.length;i++) {
					if(i<50) {aveBaseFinal+=diameters[i]; aveBase+=diameters[i];}
					if(i>diameters.length-50)aveBaseFinal+=diameters[i];
					if(diameters[i]<dminVal) {dminVal=diameters[i]; dmin=i;}
				}
				aveBase/=50; aveBaseFinal/=100;
				for(int i=dmin;i>0;i--) {
					if(diameters[i]>aveBaseFinal) {dstartConstr=i+1; break;}
				}
				for(int i=dstartConstr;i>3;i--) {
					if(( (diameters[i-3]+diameters[i-2]+diameters[i-1])/3 < (diameters[i]+diameters[i+1]+diameters[i+2])/3 ) && (Math.abs(diameters[i]-aveBase)<(0.05*aveBase))) {dstart=i; break;}
				}
				for(int i=dmin;i<diameters.length;i++) {
					if((dendConstr==-1) && (diameters[i]>aveBaseFinal)) {dendConstr=i-1;}
					if(diameters[i]>dmaxVal) {dmaxVal=diameters[i]; dmax=i;}
				}
				for(int i=dmax;i<diameters.length;i++) {
					if(diameters[i]<aveBaseFinal) {dendDilation=i;break;}
				}
				if(dstart>=0){
					double starttime=times[dstart];
					for(int i=0;i<times.length;i++)times[i]-=starttime;
				}
			}
			plot.setColor("black");
			plot.add(plottype,times,diameters);
			if(csdSummary) {
				plot.setColor("magenta");
				plot.drawLine(times[dstartConstr], dminVal, times[dstartConstr], dmaxVal);
				plot.drawLine(times[dendConstr], dminVal, times[dendConstr], dmaxVal);
				plot.drawLine(times[dendDilation], dminVal, times[dendDilation], dmaxVal);

				TextWindow summaryTable=new TextWindow(title+"-diametersSummary","Baseline Diameter\tBef-After Ave\tStart Constriction\tEnd Constriction\tEnd Dilation\tConstriction time\tDilation Time\tMin %\tMax %","",500,300);
				summaryTable.append(""+rnd(aveBase,3)+"\t"+rnd(aveBaseFinal,3)+"\t"+rnd(times[dstartConstr],3)+"\t"+rnd(times[dendConstr],3)+"\t"+rnd(times[dendDilation],3)+
						"\t"+rnd(times[dendConstr]-times[dstartConstr],3)+"\t"+rnd(times[dendDilation]-times[dendConstr],3)+
						"\t"+rnd(dminVal/aveBase,3)+"\t"+rnd(dmaxVal/aveBase,3));
				summaryTable.setVisible(true);
				//txtp.updateColumnHeadings(headings+"\tCSDTime\tNorm Diameter");
				for(int i=0;i<drt.getCounter();i++) {
					drt.setValue("CSDTime", i, times[i]);
					drt.setValue("Norm Diameter", i, diameters[i]/aveBase);
				}
				drt.show(tabletitle);
			}
			plot.show();
			
		}
		
		IJ.showStatus("Diameter Profile Completed");
		IJ.setTool(tool);
	}
	
	public static int[] getThresh(double[] profile, int rfd, String threshLevel, boolean invert) {
		return getThresh(profile, rfd, threshLevel, invert, -1);
	}
	
	public static int[] getThresh(double[] profile, int rfd, String threshLevel, boolean invert, int directThresh) {
		
		if(profile==null)return null;
		if(profile.length<=rfd)return null;
		
		double[] aveprof=new double[profile.length-rfd+1];
		for(int i=0;i<aveprof.length;i++) {
			double ave=0;
			for(int j=0;j<rfd;j++) ave+=profile[i+j];
			aveprof[i]=ave/rfd;
		}
		double mean=aveprof[0],min=mean,max=min;
		for(int i=1;i<aveprof.length;i++) {
			mean+=aveprof[i];
			if(aveprof[i]>max)max=aveprof[i];
			if(aveprof[i]<min)min=aveprof[i];
		}
		mean/=(double)aveprof.length;
		
		if(directThresh>-1) threshLevel="Direct";
		double thresh=mean;
		if(threshLevel=="Median") {
			double[] formedian=Arrays.copyOf(aveprof, aveprof.length);
			Arrays.sort(formedian);
			double median=(formedian.length%2==0)?((formedian[formedian.length/2]+formedian[formedian.length/2-1])/2):(formedian[formedian.length/2]);
			thresh=median;
		}
		else if(threshLevel=="Mid")thresh=(max+min)/2;
		else if(threshLevel=="Top 1/3")thresh=(max-min)*2/3+min;
		if(directThresh>-1)thresh=directThresh;
		int[] result=new int[aveprof.length];
		for(int i=0;i<aveprof.length;i++) {
			result[i]=(profile[i]>thresh)?255:0;
		}
		//despeckle
		int hapl=aveprof.length/2;
		for(int i=hapl+1;i<aveprof.length-1;i++) {
			if(result[i-1]==result[i+1])result[i]=result[i+1];
			if(result[i-hapl-1]==result[i-hapl+1])result[i-hapl]=result[i-hapl+1];
		}
		//if(result.length>4 && (result[0]+result[1]+result[2]+result[3]+result[4])/5==255) {
		if(invert) {
			for(int i=0;i<result.length;i++)result[i]=((result[i]==0)?255:0);
		}
		return result;
	}
	
	private static String rnd(double a, int num) {
		return Double.toString( ((double)Math.round(a*Math.pow(10,(double)num)))/Math.pow(10,(double)num));
	}
	
	public static double getDiameter(ImagePlus imp, boolean invert) {
		double pw=imp.getCalibration().pixelWidth;
		Roi roi=imp.getRoi();
		int stype=roi.getType();
		double strokeWidth = roi.getStrokeWidth();
		int rfd=3, ianglejump=1;
		ProfilePlot tpp;
		Polygon pline=roi.getPolygon();
		double[] angle=new double[pline.npoints];
		if(stype==Roi.POLYLINE || stype==Roi.FREELINE) {
			for(int il=ianglemax;il<pline.npoints-ianglemax;il+=ianglejump) {
				for(int ila=0;ila<ianglemax;ila++) angle[il]+=Math.atan2(pline.ypoints[il+ila]-pline.ypoints[il-ila], pline.xpoints[il+ila]-pline.xpoints[il-ila]);
				angle[il]/=ianglemax;
			}
		}
		double[] tp=null;
		if(stype==Roi.LINE) {
			tpp=new ProfilePlot(imp);
			tp=tpp.getProfile();
		} else if(stype==Roi.POLYLINE || stype==Roi.FREELINE) {
			double[] temptp;
			tp=new double[(int)strokeWidth];
			int ianglelen=0;
			for(int il=ianglemax;il<pline.npoints-ianglemax;il+=ianglejump) {
				int x=pline.xpoints[il], y=pline.ypoints[il];
				Line tline=new Line(x+strokeWidth/2*Math.cos(angle[il]-Math.PI/2), y+strokeWidth/2*Math.sin(angle[il]-Math.PI/2), x+strokeWidth/2*Math.cos(angle[il]+Math.PI/2), y+strokeWidth/2*Math.sin(angle[il]+Math.PI/2));
				tline.setStrokeWidth(rfd);
				imp.setRoi(tline);
				tpp=new ProfilePlot(imp);
				temptp=tpp.getProfile();
				for(int iln=0;iln<strokeWidth;iln++)tp[iln]+=iln<temptp.length?temptp[iln]:0;
				ianglelen++;
			}
			for(int iln=0;iln<strokeWidth;iln++)tp[iln]/=ianglelen;
			imp.setRoi(roi);
		}else {
			IJ.error("Need line roi");
			return -1.0;
		}
		int[] thresh=getThresh(tp,rfd,"Mean", invert);
		if(thresh==null)return -1.0;
		int idiameter=0;
		for(int i=0;i<thresh.length;i++) {
			if(thresh[i]==255)idiameter++;
		}
		return (pw*(double)idiameter);
	}
}