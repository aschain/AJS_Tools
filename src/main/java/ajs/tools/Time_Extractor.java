package ajs.tools;
import ij.plugin.PlugIn;
import ij.*;
import ij.gui.*;
import ij.process.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.awt.event.ActionEvent;

public class Time_Extractor implements PlugIn {

	
	/**
	 * This method gets called by ImageJ / Fiji.
	 *
	 * @param arg can be specified in plugins.config
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	@Override
	public void run(String arg){
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage();return;}
		if(arg.equals("printTimes")) {
			printTimes(imp);
		}else {
			copyTimes(imp);
		}
	}
	
	public static void printTimes(ImagePlus imp) {
		IJ.setForegroundColor(255,255,255);
		Font font=new Font(TextRoi.getDefaultFontName(), TextRoi.getDefaultFontStyle(), TextRoi.getDefaultFontSize());
		imp.getProcessor().setFont(font);
		int fontheight=imp.getProcessor().getFontMetrics().getHeight();
		int chs=imp.getNChannels(),sls=imp.getNSlices(),frms=imp.getNFrames(),frm=imp.getFrame(),sl=imp.getSlice(),ch=imp.getChannel();
		int w=imp.getWidth();
		
		if(frms==1) {IJ.error("Needs frames");return;}
		double frint=imp.getCalibration().frameInterval*1000;
		double[] frmtimesd=extractTimes(imp,true,SubTime.NONE);
		copyTimes(frmtimesd,sls);
		long[] frmtimes=new long[frmtimesd.length];
		for(int i=0;i<frmtimes.length;i++)frmtimes[i]=(long)(frmtimesd[i]*1000);
		if((frmtimes[1]-frmtimes[0])==0) {IJ.error("Bad times, maybe add Frame Interval?");return;}
		long zerotime=frmtimes[0];
		String zerotimestr=msecToTimeString(frmtimes[0],"msec");

		String precdef="sec";
		GenericDialog gd=new GenericDialog("Time printer");
		gd.addMessage("Frame interval: "+(frint/1000)+" s");
		gd.addMessage("Zero time from first frame: "+zerotimestr);
		gd.addMessage("Time of current frame: "+msecToTimeString(frmtimes[(frm-1)*sls],"msec"));
		gd.addStringField("Set Zero time of day (subtracted from all times) or zero frame:",zerotimestr);
		gd.addCheckbox("Use current frame as 0 time? (ignores above)",true);
		gd.addMessage("Current frame is: "+frm);
		gd.addStringField("Frames to label?","1-"+frms);
		gd.addNumericField("Levels down?",1,0);
		gd.addStringField("Prefix:","");
		if(sls>1)gd.addCheckbox("Do all slices?",false);
		gd.addCheckbox("Background Fill?",true);
		if(frint%1000>0)precdef="msec";
		if(frint%60000==0)precdef="min";
		if(frint%(60000*60)==0)precdef="hour";
		gd.addChoice("Precision:",new String[]{"msec","sec","min","hour"},precdef);
		gd.addCheckbox("Label all hr min sec?",true);
		if(chs>1)gd.addCheckbox("Do all channels?",true);
		gd.addCheckbox("Print intervaltime below?",false);
		int eventFrame=getEventFrameFromInfo(imp, false);
		if(eventFrame>0 && eventFrame<=imp.getNFrames()) {
			java.awt.Button button= new java.awt.Button("Copy Event Corrected Times");
			button.addActionListener(new java.awt.event.ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					copyTimes(imp,SubTime.EVENT_SET);
				}
			});
			java.awt.Panel panel=new java.awt.Panel();
			panel.add(button);
			gd.addPanel(panel);
		}
		gd.showDialog();
		
		if(gd.wasCanceled())return;

		String temp=gd.getNextString();
		if(temp.indexOf(":")>-1){zerotime=timeStringToMsec(temp);}
		else {zerotime=frmtimes[(Integer.parseInt(temp)-1)*sls];}
		if(gd.getNextBoolean())zerotime=frmtimes[(frm-1)*sls]; //slszero
		int[] frRange=AJ_Utils.parseRange(gd.getNextString());
		int level=(int)gd.getNextNumber();
		String prefix=gd.getNextString();
		int endsls,endchs;
		if(sls>1&&gd.getNextBoolean()) endsls=sls; else endsls=1;
		boolean dofill=gd.getNextBoolean();
		String precision=gd.getNextChoice();
		if(gd.getNextBoolean())precision="la-"+precision;
		if(chs>1 && gd.getNextBoolean()) endchs=chs; else endchs=1;
		boolean printinterval=gd.getNextBoolean();


		//setBatchMode(true);
		ImageStack imgst=imp.getImageStack();
		sl--;ch--;
		long prevtime=0,previnterval=0;
		double bigmax=AJ_Utils.getMaxOfBits(imp.getBitDepth());
		LUT lut;
		double min=0,max=255.0;
		for(int sli=0;sli<endsls;sli++){
			if(endsls>1)sl=sli;
			for(int i=frRange[0];i<frRange[1];i++){
				long msecs=frmtimes[i*sls+sli]-zerotime;
				if(printinterval){previnterval=msecs-prevtime; prevtime=msecs;}
				//draw time string on image
				String addsp=""; if(msecs>=0)addsp=" ";
				for(int chi=0;chi<endchs;chi++){
					if(endchs>1)ch=chi;
					int pind=ch+sl*chs+i*sls*chs+1;
					ImageProcessor ip=imgst.getProcessor(pind);
					if(bigmax>255.0) {
						lut=ip.getLut();
						max=lut.max; min=lut.min;
						ip.setMinAndMax(0, bigmax);
					}
					ip.setFont(font);
					ip.setColor(Color.WHITE);
					ip.setJustification(ImageProcessor.RIGHT_JUSTIFY);
					String text=prefix+" "+addsp+msecToTimeString(msecs,precision);
					if(dofill)ip.drawString(text,w-10,fontheight*level,Color.BLACK);
					else ip.drawString(text,w-10,fontheight*level);
					if(printinterval && i>0){
						text="Interval: "+previnterval;
						if(dofill)ip.drawString(text,w-10,fontheight*(level+1),Color.BLACK);
						else ip.drawString(text,w-10,fontheight*(level+1));
					}
					if(bigmax>255.0)ip.setMinAndMax(min, max);
				}
			}
		}
		//setBatchMode(false);
		imp.updateAndDraw();
	}
	
	public static void copyTimes(ImagePlus imp) {
		copyTimes(imp,SubTime.START);
	}
	
	public static void copyTimes(ImagePlus imp, SubTime subtime) {
		double[] times=extractTimes(imp, false, subtime);
		String printer=Double.toString(times[0]);
		for(int i=1;i<times.length;i++) {
			printer+="\n"+times[i];
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(printer), null);
		IJ.log("Got Times");
		IJ.showStatus("Got Times");
	}
	
	private static void copyTimes(double[] times, int sls) {
		if(times.length==0)return;
		double starttime=times[0];
		String printer="0.0";
		for(int i=1;i<times.length/sls;i++) {
			printer+="\n"+(times[i*sls]-starttime);
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(printer), null);
		//IJ.log("Got Times");
		IJ.showStatus("Got Times");
	}
	
	public static double[] extractTimes(ImagePlus imp) {
		return extractTimes(imp, false, SubTime.START);
	}
	
	static public enum SubTime{
		NONE,
		START,
		EVENT_NO_SET,
		EVENT_SET
	}
	
	/**
	 * Returns a double array of frame times as unix time unless 
	 * subtractStart or correctForEventTime is true.
	 * Includes all slices if dosls is true, otherwise does only the first slice of each frame
	 * @param imp The ImagePlus to work on
	 * @param dosls Whether to do all slices for every frame or top slice per frame
	 * @param subtractStart Subtract first time (start at 0)
	 * @param correctForEventTime Subtract the time of the top slice of the event frame
	 * 							  as defined in the image info (e.g. "Event at: 21")
	 * @return
	 */
	public static double[] extractTimes(ImagePlus imp, boolean dosls, SubTime subtime) {
		int chs=imp.getNChannels(),sls=imp.getNSlices(),frms=imp.getNFrames();
		String hasAJZstr=imp.getStringProperty("2p-hasAJZ");
		boolean hasAJZ=false;
		if(hasAJZstr!=null &&hasAJZstr.equals("true"))hasAJZ=true;
		long frint=(long)(imp.getCalibration().frameInterval*1000);
		if(frint==0)frint=1;
		String[] labels=imp.getImageStack().getSliceLabels();
		long currtime, offsettime=-1;
		int partframe,prevpartframe=0;
		int endsls=dosls?sls:1;
		double[] result=new double[dosls?(frms*sls):frms];
		
		for(int i=0;i<frms;i++) {
			for(int j=0;j<endsls;j++) {
				String[] slinfo;
				if(!(labels==null || labels.length<(i*chs*sls+j*chs) || labels[i*chs*sls+j*chs]==null))
					slinfo=labels[i*chs*sls+j*chs].split("\n");
				else slinfo=new String[0];
				int sind=-1,sdateind=-1,ptyind=-1,sttind=-1;
				for(int k=0;k<Math.min(10, slinfo.length);k++){
					if(slinfo[k].startsWith("s_"))sind=k;
					if(slinfo[k].startsWith("DateTime"))sdateind=k;
					if(slinfo[k].startsWith("ptytime:"))ptyind=k;
					if(slinfo[k].startsWith("Starttime:"))sttind=k;
					//example:
					//s_C001Z012T005.tif
					//Software: FV10-ASW 4.2.2.1 /FileVersion=1.2.6.0
					//DateTime: 2016:08:23 16:24:48
					//ptytime: 1.54
				}
				if(sttind>-1) {
					offsettime=(long)AJ_Utils.parseDoubleTP(slinfo[sttind].split(" ")[1]);
				}else if(sdateind>-1) {
					//time of day of part hh:mm:ss
					//offsettime is msec since midnight
					offsettime=timeStringToMsec(slinfo[sdateind].split(" ")[2]);
				}
				if(ptyind>-1) {
					currtime=(long)(Double.parseDouble(slinfo[ptyind].split(" ")[1])*1000);
				}else {
					if(sind>-1) {
						partframe=getSframe(slinfo[sind]);
						prevpartframe=partframe;
					} else {
						IJ.log("s_ missing from frame: "+(i+1));
						partframe=(++prevpartframe);
					}
					currtime=(partframe-1)*frint;
					if(hasAJZ)currtime/=sls;
				}
				result[i*endsls+j]=((double)(offsettime+currtime))/1000;
			}
		}

		if(subtime!=SubTime.NONE) {
			double eventTime=result[0];
			if(subtime==SubTime.EVENT_SET || subtime==SubTime.EVENT_NO_SET) {
				int eventFrame=getEventFrameFromInfo(imp, subtime==SubTime.EVENT_SET);
				if(eventFrame>0 && eventFrame<=imp.getNFrames()) {
					eventTime=result[(eventFrame-1)*endsls];
				}
			}
			for(int i=0;i<result.length;i++)result[i]-=eventTime;
		}
		return result;
	}
	
	/**
	 * Returns (1-based, actual) frame that the user sets as the time of the event
	 * @param imp
	 * @param shouldSet
	 * @return
	 */
	public static int getEventFrameFromInfo(ImagePlus imp, boolean shouldSet) {
		String info=imp.getInfoProperty();
		if(info==null)info="";
		String[] infolns=info.split("\n");
		int csdfr=-1;
		int i=getInfoEventLine(infolns);
		if(i>(-1)) {
			try {
				Integer temp=Integer.valueOf(infolns[i].substring(infolns[i].indexOf(":")+1).trim());
				if(temp!=null)csdfr=temp.intValue();
			}catch(Exception e) {
				e.printStackTrace();
			}
		}
		if(csdfr==-1 && shouldSet) {
			csdfr=askEventFrame(imp);
		}
		return csdfr;
	}
	
	private static int getInfoEventLine(String[] infolns) {
		for(int i=0; i<infolns.length; i++) {
			if(infolns[i].startsWith("Event at: ") || infolns[i].startsWith("CSD at: ")) {
				return i;
			}
		}
		return -1;
	}
	
	public static int askEventFrame(ImagePlus imp){
		int eventfr=-1;
		NonBlockingGenericDialog gd=new NonBlockingGenericDialog("Set Event");
		gd.addNumericField("Which frame is the event?", imp.getT(), 0);
		gd.showDialog();
		if(gd.wasCanceled())return -1;
		eventfr=(int)gd.getNextNumber();
		if(eventfr>0 && eventfr<imp.getNFrames())setEventFrame(imp,eventfr);
		else eventfr=-1;
		return eventfr;
	}

	
	public static void setEventFrame(ImagePlus imp, int frame){
		if(frame<1 || frame>imp.getNFrames()) {
			IJ.error("Error Frame outside bounds");
			return;
		}
		String info=imp.getInfoProperty();
		if(info==null)info="";
		String[] infolns=info.split("\n");
		int i=getInfoEventLine(infolns);
		if(i>-1) {
			infolns[i]="Event at: "+frame;
			info=String.join("\n", infolns);
		}else {
			if(!info.equals("") && !info.endsWith("\n")) info+="\n";
			info=info+"Event at: "+frame;
		}
		imp.setProperty("Info", info+"\n");
	}
	
	private static int getSframe(String stitle) {
		int iot=stitle.indexOf("T");
		if(iot<0)return 1;
		return Integer.parseInt(stitle.substring(stitle.indexOf("T")+1,stitle.indexOf("T")+4));
	}
	
	private static String msecToTimeString(long ms,String type) {
		//[la-]hh:mm:ss:msec
		String lbl="",format="";
		if(type.startsWith("la-")) {lbl="la-"; type=type.substring(3,type.length());}
		switch(type) {
		case "msec": 
			format="ms:"+format;
		case "sec":
			format="s:"+format;
		case "min":
			format="m:"+format;
		case "hour":
			format="h:"+format;
			break;
		default:
			format="h:m:s:";
			break;
		}
		if(format.length()>1)format=lbl+format.substring(0,format.length()-1);
		return AJ_Utils.textTime(ms, format);
		
	}
	
	private static long timeStringToMsec(String timestring) {
		//ss, mm:ss, hh:mm:ss, or hh:mm:ss:msec
		return AJ_Utils.timeStringToMsec(timestring);
	}
	
}