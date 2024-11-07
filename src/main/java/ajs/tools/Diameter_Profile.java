package ajs.tools;
import ij.plugin.PlugIn;
import ij.text.TextPanel;
import ij.text.TextWindow;
import ij.*;
import ij.gui.*;
import ij.process.*;
import java.util.*;

import java.awt.Point;
import java.awt.Polygon;


public class Diameter_Profile implements PlugIn {

	final String[] THRESHLEVELS=new String[] {"Mean","Median","Mid","Top 1/3"};
	final static int ianglemax=5;
	
	
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
		ij.measure.Calibration cal=imp.getCalibration();
		double pw=cal.pixelWidth;
		int sl=imp.getSlice(), fr=imp.getFrame(), frms=imp.getNFrames(),chs=imp.getNChannels(), ch=imp.getC();
		String title=imp.getTitle();
		Roi roi=imp.getRoi();
		int stype=roi.getType();
		double strokeWidth = roi.getStrokeWidth();
		int ianglejump=1;
		String tool=IJ.getToolName();
		
		GenericDialog gd=new GenericDialog("Diameter Profile");
		gd.addChoice("Thresh level:", THRESHLEVELS, Prefs.get("AJ.Diameter_Profile.threshlevel", "Mean"));
		gd.addCheckbox("Vessel is dark?", false);
		if(stype==Roi.RECTANGLE)gd.addCheckbox("Vertical?",false);
		if(stype==Roi.LINE ||stype==Roi.POLYLINE || stype==Roi.FREELINE)gd.addNumericField("Widen line (averaging)", strokeWidth, 0);
		if(stype==Roi.POLYLINE || stype==Roi.FREELINE) {
			gd.addNumericField("Skip every n along line", ianglejump, 0);
		}
		if(chs>1)gd.addStringField("Channels:",""+imp.getChannel());
		gd.addNumericField("Diameter running ave:",rfd,0);
		gd.addCheckbox("CSD Summary?",false);
		gd.showDialog();
		
		if(gd.wasCanceled())return;
	  
		String threshLevel=gd.getNextChoice();
		Prefs.set("AJ.Diameter_Profile.threshlevel", threshLevel);
		boolean invert=gd.getNextBoolean();
		boolean vertical=false;
		if(stype==Roi.RECTANGLE)vertical=gd.getNextBoolean();
		if(stype==Roi.LINE||stype==Roi.POLYLINE || stype==Roi.FREELINE) {
			strokeWidth=gd.getNextNumber();
			roi.setStrokeWidth(strokeWidth);
			roi.updateWideLine((int)strokeWidth);
			if(stype==Roi.POLYLINE || stype==Roi.FREELINE)ianglejump=(int)gd.getNextNumber();
		}
		if(chs>1){
			ch=AJ_Utils.parseIntTP(gd.getNextString());
			imp.setPosition(ch,sl,fr);
		}
		int myrfd=(int)gd.getNextNumber();
		boolean csdSummary=gd.getNextBoolean();
		Prefs.savePreferences();
	  
		double[] times=new double[frms];
		double frint=0;
		frint=imp.getCalibration().frameInterval;
		String headings="";
		if(frms>1){
			headings="Frame\t";
			if(frint>0){
				String timeunit=imp.getCalibration().getTimeUnit();
				headings+="Frame ("+timeunit+")\t";
				times=Time_Extractor.extractTimes(imp, false, Time_Extractor.SubTime.EVENT_SET);
			}
			
		}
		
		headings+="Diameter";
	
		double[][] tp=new double[frms][];
		int[][] diameterResult=new int[frms][];
		diameterResult[0]=new int[] {-1,-1};
		
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
			imp.setPosition(ch,sl,k+1);
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

		TextWindow table=new TextWindow(title+"-diameters",headings, "",500,300);
		table.setVisible(true);
		int[] thresh=new int[xAveMax];
		for(int k=0;k<frms;k++){
			String printstr="";
			int start=-1,end=-1,diameter=0;
			for(int x=0;x<xAveMax;x++) {
				thresh[x]=ip.get(x,k);
				if(thresh[x]==255)diameter++;
				if(start==-1 && x>(myrfd+1) && (thresh[x-1]+thresh[x])/2==255)start=x-1-myrfd;
			}
			diameter+=2*myrfd;
			end=start+diameter;
			diameterResult[k]=new int[] {start,end};
			
			if(frms>1){
				printstr=""+(k+1)+"\t";
				if(frint>0) {
					printstr+=""+times[k]+"\t";
				}
			}
			printstr+=(""+diameter*pw);
			table.append(printstr);
		}
		
		if(frms>1) {
			Plot plot=new Plot("Diameter over time","Time","Diameter");
			//plot.setLimits(0, xMax, min, max);
			TextPanel txtp=table.getTextPanel();
			String[] hds=headings.split("\t");
			int n=0;
			for(n=0;n<hds.length;n++) {
				if(hds[n].equals("Diameter"))break;
			}
			double[] diameters=new double[txtp.getLineCount()];
			for(int i=0;i<txtp.getLineCount();i++) diameters[i]=Double.parseDouble(txtp.getLine(i).split("\t")[n]);

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
				double starttime=times[dstart];
				for(int i=0;i<times.length;i++)times[i]-=starttime;
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
				txtp.updateColumnHeadings(headings+"\tCSDTime\tNorm Diameter");
				for(int i=0;i<txtp.getLineCount();i++) txtp.setLine(i,txtp.getLine(i)+"\t"+times[i]+"\t"+(diameters[i]/aveBase));
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