package ajs.tools;
import ij.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Scanner;

import ij.process.*;
import ij.text.TextPanel;
import ij.text.TextWindow;

import java.awt.*;
import java.awt.event.*;
import ij.gui.*;
import ij.io.FileInfo;
import ij.measure.Calibration;

public class TwoPhotonImage implements AdjustmentListener{
	
	static final int screenwidth=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode().getWidth();
	static final String[] zmethnames= {"AVG_","MAX_","MIN_","SUM_","STD_","MED_"};
	static final FilenameFilter nohidden = new FilenameFilter(){
		public boolean accept(File dir, String name){
			return !(name.startsWith(".") || name.equals("Thumbs.db"));
		}
	};
	static String webpath=Prefs.get("AJ.TwoPhoton_Import.webpath","C:\\Inetpub\\wwwroot\\");
	
	//boolean updating=false;
	ImagePlus img=null,zimg=null;
		
	String dir=null, infofile, RGBname, exlocoutput, starttimestr;
	int zmethod=1;
	ij.measure.Calibration cal=new ij.measure.Calibration();
	int scale=100;
	int chs=1,sls=1,frms=1,totfrms=1,locs=1,loc=0,totaltps=0, totalsls=1, lastz=0;
	ArrayList<String> xylist=new ArrayList<String>();
	ArrayList<Double> times=new ArrayList<Double>();
	ArrayList<Integer> slicearray;
	int cycind,slind,chind;
	boolean hasT,hasZ,hasC,virtual=false,isOif=false,cont=false,mousePressed=false, hasAJZ=false;
	LUT[] stackluts={LUT.createLutFromColor(Color.red),LUT.createLutFromColor(Color.green),LUT.createLutFromColor(Color.blue),LUT.createLutFromColor(Color.magenta),LUT.createLutFromColor(Color.cyan),LUT.createLutFromColor(Color.yellow)};
	long firstfiletime, lastfiletime, tifsize;
	boolean dozee=false,
			dogamma=Prefs.get("AJ.TwoPhoton_Import.dogamma", false),
			dotimes=Prefs.get("AJ.TwoPhoton_Import.dotimes", false),
			dopos=Prefs.get("AJ.TwoPhoton_Import.dopos", true),
			noask=Prefs.get("AJ.TwoPhoton_Import.noask", false),
			monitor=false;
	
	//Updating variables
	int sl=0,frstart,frend;
	File[] fl;
	boolean web=false, allocate=true;
	ImageProcessor notLoaded=null;
	String[] eventString=new String[0];
	int updateDelay=5000, movieDelay=30000;
	float[][] monitorAves=null;
	private static final float MONITORFRAC=0.20f;
	private boolean alarmTrig=false;
	int montprev=0,monzprev=0;
	private volatile boolean threadrunning=false;
	TextWindow tw=null;
	private String warning="";
	
	final int MAXCHS=4;
	private int xmli=0;
	private String[] xmlfile=null;

	
	public TwoPhotonImage(String ndir) {
		dir=ndir;
		if(dir.endsWith("\\")||dir.endsWith("/"))dir=dir.substring(0,dir.length()-1);
		dir+=File.separator;
		this.fl=(new File(dir)).listFiles(nohidden);
		setup();
	}
	
	public TwoPhotonImage(File[] fl) {
		this.fl=fl;
		this.dir=fl[0].getParentFile().getAbsolutePath();
		if(!this.dir.endsWith(File.separator))this.dir+=File.separator;
		setup();
	}
	
	public TwoPhotonImage(ImagePlus imp) {
		this.img=imp;
		updateFromImage();
		addThisAdjustmentListener();
	}
	
	void setFrameRange(int start, int end) {
		frstart=start;
		frend=end;
		img.setProperty("2p-FrameStart", start);
		img.setProperty("2p-FrameEnd", end);
	}
	
	public ImagePlus open() {
		int tpstart=1,tpend=frms;
		
		//Use open window if same name
		ImagePlus img=WindowManager.getImage(RGBname);
		if(img!=null) {
			String imgdir= (String) img.getProperty("2p-Directory");
			if(imgdir.equals(dir) && !noask && !IJ.showMessageWithCancel("Use open","Use open window?"))img=null;
			if(!imgdir.equals(dir))img=null;
		}
		if(img!=null){
			updateFromImage(img);
			cont=true;
			WindowManager.setCurrentWindow(img.getWindow());
		}else{
			//Open dialog if image is not open already
			if(((IJ.maxMemory()-IJ.currentMemory())<(tifsize*(tpend-tpstart+1)*getSlices(loc)*chs))) virtual=true;
			if(IJ.shiftKeyDown())noask=!noask;
			if(!virtual && frms==1) noask=true;
			if(!noask|| cont || locs>1){
				String tpstr="1-"+frms;
				GenericDialog gd=new GenericDialog(RGBname);
				if(frms>1 && sls>1){
					gd.addStringField("Timepoints", tpstr);
				}
				if(sls>1)gd.addCheckbox("Z-Project?", dozee);
				gd.addCheckbox("Include slice times?", dotimes);
				if(locs>1){gd.addNumericField("Location:", 1, 0, 3, "out of "+locs);}
				gd.addCheckbox("Virtual?",virtual);
				gd.addCheckbox("Half Gamma?", dogamma);
				if(cont) gd.addCheckbox("Continue Update?",false);
				long tolf=(System.currentTimeMillis()-lastfiletime)/1000;
				if(tolf<10*60) gd.addMessage("Time since last file: "+ Math.round((double) tolf));
				
				gd.showDialog();
				
				if(gd.wasCanceled())return null;
				
				if(frms>1 && sls>1) {
					tpstr=gd.getNextString();
					int hyph=tpstr.indexOf("-");
					if(hyph==-1){tpend=AJ_Utils.parseIntTP(tpstr); tpstart=tpend; dozee=false;}
					else {tpstart=AJ_Utils.parseIntTP(tpstr.substring(0,hyph));
						tpend=AJ_Utils.parseIntTP(tpstr.substring(hyph+1,tpstr.length()));}
					if(tpstart<0)tpstart=1; if(tpend<0) tpend=frms;
					if(tpend>frms)tpend=frms;
					if(tpstart>tpend)tpstart=tpend;
				}
				if(sls>1)dozee=gd.getNextBoolean();else dozee=false;
				dotimes=gd.getNextBoolean();
				Prefs.set("AJ.TwoPhoton_Import.dotimes", dotimes);
				if(locs>1) setLocation((int) gd.getNextNumber()-1);
				virtual=gd.getNextBoolean();
				dogamma=gd.getNextBoolean();
				Prefs.set("AJ.TwoPhoton_Import.dogamma", dogamma);
				Prefs.savePreferences();
				if(cont) {
					cont=gd.getNextBoolean();
				}
				if(cont) {
					tpend=frms;
					setupContinuousUpdate();
					if(allocate)tpstart=1;
				}
			}else{
				//tpi.dotimes=false;
			}
			if(!cont)allocate=false;
			
			if(!virtual && ((IJ.maxMemory()-IJ.currentMemory())<(tifsize*(tpend-tpstart+1)*getSlices(loc)*chs))) {
				if(IJ.showMessageWithCancel("Virtual","Not enough memory, open as virtual?"))virtual=true;
				else {
					scale=(int) IJ.getNumber("Scale to save memory?",100);
					if(scale==IJ.CANCELED)scale=100;
				}
			}
			if(virtual) {
				scale=(int) IJ.getNumber("Change this from 100 to scale instead of Virtual stack",100);
				if(scale==IJ.CANCELED)scale=100;
				if(scale==100){dozee=false; dogamma=false;}
				else virtual=false;
			}
			
			//load the image
			if(IJ.getLog()!=null)IJ.log("");
			File f=new File(dir);
			IJ.log(f.getParentFile().getName()+File.separator+RGBname+":");
			IJ.log(exlocoutput);
			
			img=loadFirstImage(tpstart,tpend);
			
			if(dopos) {
				img.getWindow().setLocation(new Point(Math.max(10,screenwidth/2-img.getWindow().getWidth()-5),200));
			}
			if(dozee) {
				zProject();
				if(dopos && zimg!=null) zimg.getWindow().setLocation(new Point(screenwidth/2+5,200));
				if(allocate) {
					ImageStack zst=zimg.getStack();
					int tps=Math.max(totfrms, totaltps);
					for(int i=(tpend)*chs;i<tps*chs;i++)
						zst.addSlice(notLoaded);
					zimg.setDimensions(chs, 1, tps);
					zimg.updateAndRepaintWindow();
				}
			}
		}
		if(cont) startContinuousUpdate();
		
		return img;
	}
	
	public void setupContinuousUpdate() {
		exLoc();
		GenericDialog gd=new GenericDialog("Continuous Update");
		gd.addMessage("Continuous update on "+RGBname+"?");
		if(totaltps!=0)gd.addCheckbox("Pre-allocate stack?",allocate);
		gd.addCheckbox("Monitor for brightness?",monitor);
		gd.addCheckbox("Web",web);
		gd.addCheckbox("Set an event to mark",false);
		gd.addNumericField("Delay between updates (s)", updateDelay/1000);
		gd.addNumericField("Delay for gif movie post (s)", movieDelay/1000);
		gd.showDialog();
		if(gd.wasCanceled())return;
		if(totaltps!=0)allocate=gd.getNextBoolean();
		else allocate=false;
		monitor=gd.getNextBoolean();
		web=gd.getNextBoolean();
		if(gd.getNextBoolean()){ 
			gd=new GenericDialog("Set event");
			LocalTime lt=LocalTime.now();
			gd.addStringField("Time or time point of event",""+lt.getHour()+":"+lt.getMinute()+":"+lt.getSecond());
			gd.addStringField("Event name:","CGRP");
			gd.showDialog();
			if(!gd.wasCanceled()) {
				eventString=new String[2];
				eventString[0]=gd.getNextString();
				eventString[1]=gd.getNextString();
			}
		}
		updateDelay=(int)(1000*gd.getNextNumber());
		movieDelay=(int)(1000*gd.getNextNumber());
	}
	
	public void setupContinuousUpdate(boolean web, String[] eventstr, int updateDelay, int movieDelay, boolean allocate) {
		this.web=web; this.eventString=eventstr; this.updateDelay=updateDelay; this.movieDelay=movieDelay; this.allocate=allocate;
	}
	
	public void startContinuousUpdate(){
		IJ.log("Starting continuous update...");
		if(monitor) {
			startMonitor();
		}
		if(web && !(new File(webpath+"index.htm").exists())) web=setUpWebpage();
		String title="Time Series Clock";
		TextWindow tsc=(TextWindow) WindowManager.getWindow(title);
		if(tsc==null) {
			tsc=new TextWindow(title,"Currently "+sl+" slices of tp "+totfrms,500,190);
			tsc.setLocation(10,10);
		}
		TextPanel tscp=tsc.getTextPanel();
		long startTime=System.currentTimeMillis();
		int prevfrms=0;
		
		while(cont) {
			if(img==null || !img.isVisible() || tsc==null || !tsc.isVisible())break;
			
			String[] tpstr=new String[4];
			updateFileListAndInfo();
			tpstr[0]="Currently "+sl+"/"+totalsls+" slices of tp "+totfrms;
			long eltime=(System.currentTimeMillis()-firstfiletime);
			long deadtime=(System.currentTimeMillis()-lastfiletime);
			tpstr[1]="Running for "+AJ_Utils.textTime(eltime,"h:m:s");
			if((totaltps)>(frms)) {
				tpstr[0]=tpstr[0]+"/"+totaltps;
				tpstr[1]=tpstr[1]+" / "+AJ_Utils.textTime((long)((double)totaltps*cal.frameInterval*1000.0),"h:m:s");
			}
			tpstr[2]="Idle for "+AJ_Utils.textTime(deadtime,"h:m:s");
			tpstr[3]="lastz:"+lastz+" sl:"+sl+" lastframe:"+frend+" curfrms:"+frms;
			tscp.clear();
			for(int i=0;i<tpstr.length;i++)
				tsc.append(tpstr[i]);
			boolean updated=false;
			if(frms>frend) {
				ImageCanvas ic=img.getCanvas();
				ImageCanvas zic=null;
				if(zimg!=null) zic=zimg.getCanvas();
				while(mousePressed || ic.getModifiers()!=0 || (zic==null?(false):(zic.getModifiers()!=0))) {
					tscp.setLine(tscp.getLineCount()-1,"Waiting for mouse release to update image...");
					IJ.wait(50);
				}
				IJ.wait(50);
				updateImage();
				updated=true;
			}else if(allocate && (sl>lastz || (lastz==sls && sl>1))) {
				updateImage();
			}
			
			if(web){
				try {
					PrintStream ps=new PrintStream(webpath+"2p-update.txt");
					ps.println(tpstr[0]);
					ps.println(tpstr[1]);
					ps.println(tpstr[2]);
					ps.close();
				}catch(Exception e) {
					 IJ.error("Could not write to web text file\n"+e.getMessage());
				}
				
				if(updated) {
					ImagePlus latestmax=getLatestMax();
					latestmax.setDimensions(img.getNChannels(), 1, 1);
					latestmax.setDisplayMode(img.getDisplayMode());
					latestmax.show();
					IJ.run("Size...", "width=230 height=230 constrain interpolation=Bilinear");
					IJ.wait(500);
					IJ.saveAs(latestmax,"Jpeg", webpath+"2p-update.jpg");
					latestmax.changes=false;
					latestmax.close();
					if( ((frms-prevfrms)>10) && ((System.currentTimeMillis()-startTime)>movieDelay)){
						prevfrms=frms;
						startTime=System.currentTimeMillis();
						boolean haszimg=(zimg!=null);
						ImagePlus giffer=null;
						if(haszimg)giffer=zimg.duplicate();
						else giffer=zProject();
						giffer.setTitle("giffer");
						giffer.show();
						//WindowManager.setCurrentWindow(giffer.getWindow());
						//run("AVI... ", "compression=JPEG jpeg=10 frame=5 save="+webpath+"goingon.avi");
						IJ.run("Size...", "width=230 height=230 constrain interpolate"); IJ.wait(200);
						giffer=WindowManager.getImage("giffer");
						giffer.setDimensions(img.getNChannels(), 1, img.getNFrames());
						giffer.updateAndRepaintWindow();
						if(eventString.length==2){
							IJ.run("Print Times", "set="+eventString[0]+" levels=1 prefix=["+eventString[1]+"] background label do");
						}else {
							IJ.run("Print Times", "levels=1 background label do");
						}
						IJ.run("Stack to RGB", "frames");IJ.wait(200);
						ImagePlus rgbgiffer=WindowManager.getImage("giffer");
						WindowManager.setCurrentWindow(rgbgiffer.getWindow());
						IJ.run("Animated Gif ... ", "name=giffer set_global_lookup_table_options=[Load from Current Image] optional=[] image=[No Disposal] set=100 number=0 transparency=[No Transparency] red=0 green=0 blue=0 index=0 filename="+webpath+"2p-update.gif");
						if(giffer!=null) {giffer.changes=false; giffer.close();}
						if(rgbgiffer!=null) {rgbgiffer.changes=false; rgbgiffer.close();}
						if(!haszimg) {
							zimg.close();
							zimg=null;
						}
					}
				}
			}
			IJ.wait(updateDelay);
		}
		if(tsc!=null && tsc.isVisible())tsc.close();
		monitor=false;
		IJ.log("End Continuous Update");
	}
	
	static public boolean setUpWebpage(){
		IJ.showMessage("2p-Import can update files for a simple webpage.\nTo start, choose the directory of your webserver.");
		webpath=IJ.getDirectory("");
		if(webpath==null || webpath.equals("")) return false;
		if(webpath.endsWith("\\")||webpath.endsWith("/"))webpath=webpath.substring(0,webpath.length()-1);
		webpath+=File.separator;
		if(!(new File(webpath+"2p-update.txt").exists())) {
			try {
				PrintStream ps=new PrintStream(webpath+"2p-update.txt");
				ps.println("2p live data goes here");
				ps.close();
			}catch(Exception e) {
				 IJ.error("Could not write to web text file "+e.getMessage());
				 return false;
			}
		}else IJ.log("Using existing 2p-update.txt");
		if(!(new File(webpath+"index.htm").exists())) {
			try {
				PrintStream ps=new PrintStream(webpath+"index.htm");
				BufferedReader reader = new BufferedReader(new InputStreamReader(TwoPhoton_Import.class.getClassLoader().getResource("webroot/index.htm").openStream()));
				String contents="";
				String adder=reader.readLine();
				while(adder!=null) {
					contents+=adder+"\n";
					adder=reader.readLine();
				}
				ps.print(contents);
				ps.close();
			}catch(Exception e) {
				 IJ.error("Could not write to web index.html file "+e.getMessage());
				 return false;
			}
		}else IJ.log("Using existing index.htm");
		if(!(new File(webpath+"movie.htm").exists())) {
			try {
				PrintStream ps=new PrintStream(webpath+"movie.htm");
				BufferedReader reader = new BufferedReader(new InputStreamReader(TwoPhoton_Import.class.getClassLoader().getResource("webroot/movie.htm").openStream()));
				String contents="";
				String adder=reader.readLine();
				while(adder!=null) {
					contents+=adder+"\n";
					adder=reader.readLine();
				}
				ps.print(contents);
				ps.close();
			}catch(Exception e) {
				 IJ.error("Could not write to web index.html file "+e.getMessage());
				 return false;
			}
		}else IJ.log("Using existing movie.htm");
		Prefs.set("AJ.TwoPhoton_Import.webpath",webpath);
		Prefs.savePreferences();
		return true;
	}	
	
	public void setup() {
		long ptime=0;
		if(TwoPhoton_Import.debug) {IJ.log("Starting setup...");ptime=System.currentTimeMillis();}
		String lastfilename="";
		for(int i=0;i<fl.length;i++){
			lastfilename=fl[fl.length-1-i].getName();
			if(lastfilename.endsWith(".tif")) {
				lastfiletime=fl[fl.length-1-i].lastModified();
				if(lastfilename.startsWith("s_"))isOif=true;
				tifsize=fl[fl.length-1-i].length();
				break;
			}
		}
		if(!lastfilename.endsWith(".tif")){IJ.error("No tifs in folder");}
		RGBname=fl[0].getParentFile().getName();
		for(int i=0;i<fl.length;i++){
			if(fl[i].getName().endsWith(".tif")) {
				firstfiletime=fl[fl.length-1-i].lastModified();
				break;
			}
		}
		
		if(isOif){
			//file info
			RGBname=RGBname.substring(0,RGBname.length()-6);
			infofile=fl[0].getParentFile().getParent()+File.separator+RGBname;
			cycind=lastfilename.indexOf("T")+1; slind=lastfilename.indexOf("Z")+1; chind=lastfilename.indexOf("C")+3;
			hasT=(cycind>0);
			hasZ=(slind>0);
			hasC=(chind>2);
			
			//color
			int ind, n=0;
			for(int i=0;i<fl.length;i++){
				String fn=fl[i].getName();
				if(fn.startsWith("Saving")) cont=true;
				if(fn.endsWith(".lut")){
					int blue=255,green=255,red=255;
					String[] lutstr=openWithCharset(fl[i].getAbsolutePath()).split("\n");
					if(lutstr.length>18) {
						blue=Integer.parseInt(lutstr[2].substring(9,10))==0?0:255; green=Integer.parseInt(lutstr[12].substring(9,10))==0?0:255; red=Integer.parseInt(lutstr[18].substring(9,10))==0?0:255;
						//if(blue>0){res=Color.blue; if(green>0) {res=Color.cyan; if(red>0) res=Color.white;}else if(red>0) {res=Color.magenta;}}
						//else if(green>0){res=Color.green; if(red>0) res=Color.yellow;}
						//else if(red>0) res=Color.red;
						ind = AJ_Utils.parseIntTP(fn.substring(5,6));
						if(ind==Integer.MIN_VALUE)ind=n;
						else ind-=1;
						stackluts[ind]=LUT.createLutFromColor(new Color(red,green,blue));
		 				n=ind+1;
					}
				}
			}
		}else{
			//File info
			cycind=lastfilename.lastIndexOf("Cycle")+5; slind=lastfilename.length()-7; chind=lastfilename.indexOf("_Ch")+3;
			while(!lastfilename.substring(cycind+3,cycind+4).contentEquals("_")) cycind++;
			infofile=dir+RGBname+".xml";
			if(!(new File(infofile)).exists())infofile=fl[0].getAbsolutePath();
			//String zoom="NA";
			//if(cycind<5) oifnotime=true;
			hasT=cycind>5; hasZ=true; hasC=true;
			if(chind<3) {IJ.error("Error with tif file name, no Ch found in:\n"+lastfilename); return;}
			
			//color
			File cfgfile=new File(dir+RGBname+"Config.cfg");
			if(!cfgfile.exists()) {
				int li=0; while(!fl[li].getName().endsWith("Config.cfg") && li<fl.length-1)li++;
				if(li<fl.length)cfgfile=fl[li];
			}
			//Prairie channelColor seems like it is alpha, red, green, blue coded to a signed int:
			//int[] pGreens=new int[] {-15728896,-13172992,-11600128,-16711913,-10813696,-10551552,-8257792};
			//int[] pReds=new int[] {-65536,-57088};
			// blue was -16766721 or 0xFF0028FF  -- don't know why the 28, maybe slightly cyan?
			if(cfgfile.exists()){
				String[] cfgfilestr=openWithCharset(cfgfile.getAbsolutePath()).split("\n");
				for(int i=0;i<9;i++){
					if(cfgfilestr[i].startsWith("    <PVWindow x")){
						String[] line=cfgfilestr[i].split(" ");
						int chn=0,tpchi=0;
						for(;chn<6;chn++) {if(cfgfilestr[i].indexOf("channelColor_"+chn)<0) {chn--; break;}}
						for(int chi=0;chi<chn;chi++) {
							int chnum=0;
							for(int j=0;j<line.length;j++) {
								if(line[j].startsWith("channelState_"+chi) && getData(line[j]).contentEquals("True")) {
									if(line[j-1].startsWith("channelColor_"+chi)) {
										chnum=AJ_Utils.parseIntTP(getData(line[j-1]));
										int reds=(0x00FF0000 & chnum)>>16;
										int greens=(0x0000FF00 & chnum)>>8;
										int blues=(0x000000FF & chnum);
										if(reds==255 || greens==255 || blues==255) {
											//filtering out partial colors
											if(reds<255)reds=0;if(greens<255)greens=0;if(blues<255)blues=0;
											stackluts[tpchi++]=LUT.createLutFromColor(new Color(reds,greens,blues));
										}
									}
								}
							}
						}
					}
				}
			}
		}
		if(TwoPhoton_Import.debug) {IJ.log("Initial setup took "+(System.currentTimeMillis()-ptime)+"ms");}
		updateFLInfo();
		exLoc();
	}
	
	void updateFromImage(ImagePlus imp) {
		this.img=imp;
		updateFromImage();
	}
	
	void updateFromImage() {
		if(img==null) return;
		RGBname=img.getTitle();
		cal=img.getCalibration();
		sls=img.getNSlices(); chs=img.getNChannels();
		frstart=(int)img.getProperty("2p-FrameStart");
		frstart=frstart>0?frstart:1;
		frend=(int)img.getProperty("2p-FrameEnd");
		starttimestr=(String)img.getProperty("2p-starttime");
		frms=frend>0?frend:img.getNFrames();
		virtual=img.getStack().isVirtual();
		if(img.isComposite()){
			LUT[] luts=img.getLuts();
			for(int i=0;i<Math.min(stackluts.length, luts.length);i++){
				stackluts[i]=luts[i];
			}
		}else stackluts[0]=LUT.createLutFromColor(Color.white);
		if(zimg==null) {
			for(int i=0;i<zmethnames.length;i++) {
				zimg=WindowManager.getImage(zmethnames[i]+RGBname);
				if(zimg!=null) {zmethod=i; break;}
			}
		}
		if(dir==null)
			dir=(String)img.getProperty("2p-Directory");
		if(dir==null || dir.isEmpty() || dir.indexOf(File.separator)==-1) {
			String info=img.getInfoProperty();
			if(info!=null && !info.isEmpty())
				dir=info.split("\n")[0];
		}
		if(dir==null || dir.isEmpty() || dir.indexOf(File.separator)==-1) {
			FileInfo fi = img.getOriginalFileInfo();
			if (fi!=null && fi.directory!=null) dir= fi.directory;
		}
		if(dir!=null && !dir.endsWith(File.separator)) {
			if(dir.endsWith("/"))dir=dir.substring(0, dir.length()-1);
			dir=dir+File.separator;
		}
		fl=(new File(dir)).listFiles(nohidden);
		String[] locstr=((String)img.getProperty("2p-Location")).split("/");
		loc=AJ_Utils.parseIntTP(locstr[0]);
		if(locstr.length>1)locs=AJ_Utils.parseIntTP(locstr[1]);
		String[]boos=((String)img.getProperty("2p-booleans")).split("/");
		dotimes=AJ_Utils.parseIntTP(boos[0])>0;
		if(boos.length>1)dogamma=AJ_Utils.parseIntTP(boos[1])>0;
	}
	
	ImagePlus zProject(ImagePlus imp, int start, int stop) {
		return zProject(imp,start,stop,true,zmethod);
	}
	
	public static ImagePlus zProject(ImagePlus imp, int start, int stop, boolean allFrames, int zmethod) {
		ij.plugin.ZProjector zprojector=new ij.plugin.ZProjector(imp);
		zprojector.setStartSlice(start);
		zprojector.setStopSlice(stop);
		zprojector.setMethod(zmethod);
		zprojector.doHyperStackProjection(allFrames);
		ImagePlus projImage=zprojector.getProjection();
		projImage.setCalibration(imp.getCalibration());
		ajs.tools.Slicelabel_Transfer.transferSliceLabels(imp, projImage);
		return projImage;
	}
	
	ImagePlus zProject(ImagePlus imp) {
		return zProject(imp,1,imp.getNSlices()==1?imp.getNFrames():imp.getNSlices());
	}
	
	ImagePlus zProject() {
		if(img!=null) {
			zimg=zProject(img);
			zimg.show();
			return zimg;
		}else return null;
	}
	
	void setLocation(int newloc) {
		if(locs>1) {
			loc=Math.min(locs-1, loc);
			RGBname=RGBname+"-loc"+(loc+1);
			sls=slicearray.get(loc);
		}
	}
	
	int getSlices(int loc) {
		return slicearray.get(loc);
	}
	
	public void updateFileListAndInfo() {
		fl=(new File(dir)).listFiles(nohidden);
		cont=(new File(dir+File.separator+"Saving")).exists();
		updateFLInfo();
	}

	public void updateFLInfo(){
		long ptime=0;
		if(TwoPhoton_Import.debug) {ptime=System.currentTimeMillis();}
		int lasti=0;
		ArrayList<String> altif=new ArrayList<String>();
		for(int i=0;i<fl.length; i++){
			String name=fl[i].getName();
			if(name.endsWith(".tif") && !name.endsWith("Reference.tif") && !name.contains("-R0")){
				altif.add(name);
			}
		}
		altif.sort(null);

		String[] fltif=new String[altif.size()];
		for(int i=0;i<altif.size();i++) fltif[i]=altif.get(i);
		altif=null;
		lasti=fltif.length-1;
		if(hasZ) sl=Integer.parseInt(fltif[lasti].substring(slind,slind+3));
		
		ArrayList<Integer> cha=new ArrayList<Integer>();
		ArrayList<Integer> cyca=new ArrayList<Integer>();
		slicearray=new ArrayList<Integer>();
		int curcyc=0,curch=0,cursl=0,topprevslice=0,lastfilei=-1;
		for(int i=0;i<fltif.length;i++){
			if(hasT){
				curcyc=Integer.parseInt(fltif[i].substring(cycind, (isOif?fltif[i].indexOf("."):cycind+3)));
				if(!cyca.contains(curcyc)) {cyca.add(curcyc);}
				frms=cyca.size();
			}else frms=1;
			if(hasC){
				curch=Integer.parseInt(fltif[i].substring(chind, chind+1));
				if(!cha.contains(curch))cha.add(curch);
				chs=cha.size();
			}else chs=1;
			if(hasZ){
				cursl=Integer.parseInt(fltif[i].substring(slind, slind+3));
			}else cursl=1;
			if(isOif){
				if((hasT&&curcyc==frms) || !hasT) {sl=cursl; lastfilei=i;}
			}else{
				if((cursl==1 && curch==1) && topprevslice!=0 && frms<(locs+1)) slicearray.add(topprevslice);
				topprevslice=cursl;
			}
			sls=Math.max(sls, cursl);
		}
		if(isOif) {lastfiletime=fl[lastfilei].lastModified();}
		else {lastfiletime=fl[fl.length-1].lastModified();}
		if(isOif) {
			String ajzpath=dir.replace(".files"+File.separator, ".ajz");
			hasAJZ=(new File(ajzpath)).exists();
			if(hasAJZ) {
				IJ.showStatus("Found AJZ file");
				String[] ajz=openWithCharset(ajzpath).split("\n");
				int stmp=0;
				for(int i=0;i<ajz.length;i++)if(ajz[i].startsWith("zps:"))stmp++;
				sl=Math.min(1, frms%stmp);
				if(frms<stmp) {sls=frms;frms=1;}
				else{sls=stmp; frms/=sls;}
				IJ.log("AJZ file adjusted z steps to "+sls+" and frms to "+frms);
				cal.frameInterval*=stmp;
			}
		}
		if(isOif||slicearray.size()==0 ||frms<=xylist.size())slicearray.add(sls);
		if(!isOif) frms/=locs;
		//locs=slicearray.size();
		/*  Don't need because of adjustment for slices above I think
		if(locs<xylist.length){
			// could get rid of this if prairie slicearray made sure to add to slicearray as long as frame was 1
			locs=xylist.length;
			//This is only for the case that all locations have the same number of slices
			for(int i=1; i<locs; i++) slicearray.add(sls);
		}
		*/
		totfrms=frms;
		if(hasZ&&hasT && (sl!=(int)slicearray.get((locs-1)))) frms--;
		if(TwoPhoton_Import.debug) {IJ.log("UpdateFL took "+(System.currentTimeMillis()-ptime)+"ms");}
	}

	private ImagePlus loadImage(int tpstart, int tpend){
		return loadImage(tpstart, 1, tpend, sls);
	}
	private ImagePlus loadImage(int tpstart, int slstart, int tpend, int slend){
		tpstart--;slstart--;
		int totalslices=0;
		for(int i=0;i<slicearray.size();i++) totalslices+=slicearray.get(i);
		int sluptoloc=0;
		for(int i=0;i<(loc);i++) sluptoloc+=slicearray.get(i);
		int slsl=slicearray.get(loc), frms=tpend-tpstart;
		if(hasAJZ) {slsl=1;tpstart*=this.sls;tpend*=this.sls;}
		int xcorr=0;
		for(int i=0;i<fl.length;i++) {
			if(!fl[i].getName().endsWith(".tif"))xcorr++;
			else break;
		}
		
		String[] paths=new String[(tpend-tpstart-1)*slsl*chs+(slend-slstart)*chs];
		String printer;
		int n=0;
		for(int i=tpstart; i<tpend; i++) {
			for(int j=0;j<slsl;j++){
				if(i==tpstart && j==0 && slstart!=0)j=slstart;
				for(int k=0;k<chs;k++){
					if(isOif){
						printer="s_"+(hasC?("C"+String.format("%03d",k+1)):"")+(hasZ?("Z"+String.format("%03d",j+1)):"")+(hasT?("T"+String.format("%03d",i+1)):"")+".tif";
					}else{
						//printer=fl[(i*chs*totalslices)+(sluptoloc*chs)+(k*slsl)+j].getName();
						printer=fl[(i*chs*totalslices)+(sluptoloc*chs)+(k*slsl)+j+xcorr].getName();
					}
					paths[n++]=dir+printer;
				}
				if(i==(tpend-1) && j==(slend-1))break;
			}
		}
		ImagePlus newimg=new ImagePlus(paths[0]);
		int bd=newimg.getBitDepth(); if(bd==24)bd=32;
		final int width=newimg.getWidth(), height=newimg.getHeight();
		ImageStack newimgst=new ImageStack(width,height,LUT.createLutFromColor(Color.white));
		IJ.showStatus("Loading 2P Image");
		long sms=System.currentTimeMillis();
		for(int i=0;i<paths.length;i++) {
			if(paths[i]==null || paths[i].isEmpty())continue;
			ImagePlus adderimg=null;
			int ni=0;
			if(i==0) adderimg=newimg;
			else {
				while(adderimg==null && ni<5) {
					try{
						adderimg=IJ.openImage(paths[i]);
					}catch( Exception e) {
						IJ.log("AJ error but still trying: "+e.getMessage());
					}
					if(adderimg==null) {
						IJ.wait(10);
						IJ.showStatus("Failed to open "+(ni++)+" "+paths[i]+", trying again");
					}
				}
			}
			File f=new File(paths[i]);
			newimgst.addSlice(f.getName()+"\n"+(String)adderimg.getProperty("Info"), adderimg.getProcessor());
			adderimg.changes=false; adderimg.close();
			if(i%100==0 && i>1) {
				double sfltime=((double)(System.currentTimeMillis()-sms))/1000.0; sfltime=Math.max(sfltime,0.001);
				double mbps=((double)((bd/8)*width*height*(i+1)))/1000000.0/sfltime;
				IJ.showStatus("Loading 2P Image "+(int)mbps+"MB/s");
			}
			IJ.showProgress((double)i/(double)paths.length);
		} 
		IJ.showProgress(1.0);
		if(newimgst.getSize()==0)return null;
		newimg= new ImagePlus(RGBname, newimgst);
		if(slstart==0 && slend==sls)newimg.setDimensions(chs, slsl, frms);
		
		if(scale!=100){
			scaleImage(newimg);
		}
		if(dogamma) {
			ImageStack imgst=newimg.getStack();
			for(int i=0;i<imgst.getSize();i++) {
				imgst.getProcessor(i+1).gamma(0.5);
			}
		}
		return newimg;
	}
	
	private void allocatedStackUpdate() {
		IJ.showStatus("Updating allocated stack...");
		int achs=img.getNChannels(), asls=img.getNSlices(), afrms=img.getNFrames();
		if(hasAJZ) {asls=1; afrms*=asls;}
		ImageStack imgst=img.getImageStack();
		Object[] stack=imgst.getImageArray();
		String[] labels=imgst.getSliceLabels();
		
		int totalslices=0;
		for(int i=0;i<slicearray.size();i++) totalslices+=slicearray.get(i);
		int sluptoloc=0;
		for(int i=0;i<loc;i++) sluptoloc+=slicearray.get(i);
		int slsl=slicearray.get(loc);
		
		long starttime=0;
		if(starttimestr==null || starttimestr.isEmpty())starttimestr=(String)img.getProperty("2p-starttime");
		if(starttimestr!=null && !starttimestr.isEmpty())starttime=AJ_Utils.sysTime(starttimestr);
		boolean stop=false;
		int tpstart=-1, tpend=0;
		
		for(int t=0;t<afrms;t++) {
			for(int z=0;z<asls;z++) {
				for(int c=0;c<achs;c++) {
					int ind=c+z*chs+t*asls*chs;
					//IJ.showProgress((double)ind/((double)achs*asls*afrms));
					if(stack[ind]==notLoaded.getPixels()) {
						//if(c==0)IJ.log("slice z"+(z+1)+" t"+(t+1)+" is not loaded");
						if(tpstart==-1)tpstart=t+1;
						String path=dir+"s_"+(hasC?("C"+String.format("%03d",c+1)):"")+(hasZ?("Z"+String.format("%03d",z+1)):"")+(hasT?("T"+String.format("%03d",t+1)):"")+".tif";
						if(!isOif)path=dir+fl[(c*chs*totalslices)+(sluptoloc*chs)+(z*slsl)+t].getName();
						File f=new File(path);
						if(f.exists()) {
							IJ.showStatus("Adding z"+(z+1)+" t"+(t+1));
							//if(c==0)IJ.log("Found file "+f.getName());
							ImagePlus adderimg=IJ.openImage(path);
							if(adderimg!=null) {
								//if(c==0)IJ.log("Opened ok");
								if(scale!=100)scaleImage(adderimg);
								if(dogamma)adderimg.getProcessor().gamma(0.5);
								stack[ind]=adderimg.getProcessor().getPixels();
								labels[ind]=f.getName()+"\n"+(String)adderimg.getProperty("Info");
								if(dotimes) {
									String slicetime=getSliceTime(dir+f.getName().replaceFirst(".tif", ".pty"));
									if("".contentEquals(slicetime)){IJ.log("No time for slice "+(ind+1));}
									labels[ind]+="ptytime: "+slicetime;
									if(starttime>0)labels[ind]+="\nStarttime: "+starttime;
								}
								adderimg.changes=false; adderimg.close();
							}
						}else {stop=true; tpend=t+1; lastz=sl; break;}
					}
				}
				if(stop)break;
			}
			if(stop)break;
		}
		img.setStack(img.getStack());
		img.updateAndRepaintWindow();
		//IJ.showProgress(1.0);
		
		if(zimg!=null && zimg.isVisible()) {
			int curT=img.getT();
			for(int j=tpstart; j<=tpend; j++) {
				img.setPosition(img.getC(),img.getZ(),j);
				ImagePlus znewimg=zProject(img,1,sls,false,zmethod);
				stackPlacer(zimg,znewimg,j,1);
				znewimg.changes=false;znewimg.close();
			}
			zimg.updateAndRepaintWindow();
			img.setPosition(img.getC(),img.getZ(),curT);
		}
	}
	
	private void scaleImage(ImagePlus imp) {
		if(scale==100)return;
		int origWidth=imp.getWidth(), origHeight=imp.getHeight();
		int newWidth=(int)((double)scale/100.0*(double)imp.getWidth());
		int newHeight=(int)((double)scale/100.0*(double)imp.getHeight());
		imp.getProcessor().setInterpolationMethod(ImageProcessor.BILINEAR);
		try {
		StackProcessor sp = new StackProcessor(imp.getStack(),imp.getProcessor());
		ImageStack s2 = sp.resize(newWidth, newHeight, true);
		if (s2.getWidth()>0 && s2.getSize()>0) {
			Calibration cal = imp.getCalibration();
			if (cal.scaled()) {
				cal.pixelWidth *= origWidth/newWidth;
				cal.pixelHeight *= origHeight/newHeight;
			}
			imp.setStack(null, s2);
		}
		}catch(Exception e) {
			IJ.error(e.getLocalizedMessage());
		}
	}
	
	ImagePlus loadFirstImage(int tpstart, int tpend) {
		long sms=System.currentTimeMillis();
		img=loadImage(tpstart,tpend);
		lastz=sls;
		if(chs*sls*frms>sls) {
			if(TwoPhoton_Import.debug)IJ.log("LoadFirstImage "+chs+"chs "+sls+"sls "+frms+"frms "+slicearray.get(loc)+"sla "+img.getNFrames()+"ifrms "+img.getStackSize()+"ss");
			ImagePlus hsimg=ij.plugin.HyperStackConverter.toHyperStack(img, chs, slicearray.get(loc), tpend-tpstart+1);
			img.changes=false;
			img.close();
			img=hsimg;
		}
		if(!cont)allocate=false;
		if(allocate && (img.getNFrames()<totaltps || img.getNSlices()<totalsls)) {
			ImagePlus temp=IJ.createImage(RGBname, ""+(img.getBitDepth()==24?"RGB":""+img.getBitDepth()+"-bit"), img.getWidth(), img.getHeight(), 1, 1, 1);
			notLoaded=temp.getProcessor();
			notLoaded.setColor(Color.WHITE);
			notLoaded.drawLine(0, 0, notLoaded.getWidth(), notLoaded.getHeight());
			notLoaded.drawLine(0, notLoaded.getHeight(), notLoaded.getWidth(), 0);
			notLoaded.drawString("This slice is not yet loaded", notLoaded.getWidth()/5, notLoaded.getHeight()/6);
			ImageStack imgst=new ImageStack(img.getWidth(),img.getHeight(), chs*totalsls*totaltps);
			ImageStack origimgst=img.getStack();
			Object[] origstack=origimgst.getImageArray();
			String[] origsl=origimgst.getSliceLabels();
			Object[] stack=imgst.getImageArray();
			String[] slicelabels=imgst.getSliceLabels();
			//   Allocate stack currently must have tpstart=1, so this first part is not currently run
			for(int fr=0; fr<(tpstart-1); fr++) {
				for(int sl=0;sl<totalsls;sl++) {
					for(int ch=0;ch<chs;ch++) {
						stack[ch+chs*sl+chs*totalsls*fr]=notLoaded.getPixels();
						slicelabels[ch+chs*sl+chs*totalsls*fr]=null;
					}
				}
			}
			for(int fr=(tpstart-1); fr<tpend; fr++) {
				for(int sl=0;sl<totalsls;sl++) {
					for(int ch=0;ch<chs;ch++) {
						if(sl>=sls) {
							stack[ch+chs*sl+chs*totalsls*fr]=notLoaded.getPixels();
							slicelabels[ch+chs*sl+chs*totalsls*fr]=null;
						}
						lastz=sl+1;
						stack[ch+chs*sl+chs*sls*fr]=origstack[ch+chs*sl+chs*sls*(fr-tpstart+1)];
						slicelabels[ch+chs*sl+chs*sls*fr]=origsl[ch+chs*sl+chs*sls*(fr-tpstart+1)];
					}
				}
			}
			for(int fr=tpend; fr<totaltps; fr++) {
				for(int sl=0;sl<totalsls;sl++) {
					for(int ch=0;ch<chs;ch++) {
						stack[ch+chs*sl+chs*sls*fr]=notLoaded.getPixels();
						slicelabels[ch+chs*sl+chs*sls*fr]=null;
					}
				}
			}
			img.setStack(null,imgst);
			img.setDimensions(chs, sls, totaltps);
		}
		double sfltime=((double)(System.currentTimeMillis()-sms))/1000.0d;
		sfltime=Math.max(sfltime,0.001d);
		int bd=img.getBitDepth(); if(bd==24)bd=32;
		double mbps=((double)((bd/8)*img.getWidth()*img.getHeight()*img.getStackSize()))/1000000.0/sfltime;
		IJ.log("LoadImage took "+sfltime+"s, "+Math.round(mbps)+"MB/s");
		
		if(chs==1)stackluts[0]=LUT.createLutFromColor(Color.white);
		for(int i=0;i<stackluts.length;i++) {
			if(bd==16) {stackluts[i].max=4095;stackluts[i].min=0;}
			else if(bd==8){stackluts[i].max=256;stackluts[i].min=0;}
		}
		if(chs>1)((CompositeImage)img).setLuts(stackluts);
		img.setTitle(RGBname);
		img.setCalibration(cal);
		img.setProperty("Info", dir+"\n"+exlocoutput);
		img.setProperty("2p-Directory", dir);
		img.setProperty("2p-Location",""+loc+"/"+locs);
		img.setProperty("2p-booleans", ""+(dotimes?1:0)+"/"+(dogamma?1:0));
		img.setProperty("2p-hasAJZ",""+(hasAJZ?"true":"false"));
		img.setProperty("2p-starttime", starttimestr);
		setFrameRange(tpstart,tpend);
		img.show();
		if(frms<totaltps) addUpdateButton();
		addThisAdjustmentListener();
		if(dotimes) {
			if(allocate) updateImageSliceTimes((tpstart-1)*chs*sls,tpend*chs*sls);
			else updateImageSliceTimes(0,img.getImageStackSize());
		}
		return img;
		
	}
	
	void updateImage(){
		updateFileListAndInfo();
		if(img.isLocked())return;
		if(img.getWindow()==null)return;
		IJ.showStatus("Updating "+RGBname+"...");
		if(((totfrms-1)*totalsls+sl)<=((frend-1)*totalsls+lastz) || img==null) {IJ.showStatus(RGBname+" has no new frames or slices."); if(!cont) removeUpdateButton(); return;}
		Window currw=WindowManager.getActiveWindow();
		imageUpdater(zimg!=null && zimg.getWindow()!=null);
		setFrameRange(frstart,frms);
		WindowManager.setWindow(currw);
		if(monitor)updateMonitor();
	}
	
	public void startMonitor() {
		if(!cont)return;
		monitor=true;
		IJ.log("Starting monitor...");
		if(tw!=null && tw.isVisible()) {tw.close();}
		tw=new TextWindow("AJ 2p Monitor", "2p monitor starting...", 300, 500);
		updateMonitor();
	}
	
	private void updateMonitor() {
		if(alarmTrig && IJ.getLog()==null) {alarmTrig=false; warning="";}
		if(tw==null || !tw.isVisible()) {monitor=false; IJ.log("Monitor stopped"); monitorAves=null; tw=null; alarmTrig=false; return;}
		int tps=img.getNFrames();
		if(monitorAves==null) {
			monitorAves=new float[chs][sls];
		}
		final ImageStack imgst=img.getStack();
		final String[] slbs=imgst.getSliceLabels();
		final int start=montprev;
		
		final TextPanel tp=tw.getTextPanel();
		if(threadrunning)return;
		Thread t=new Thread() {
			public void run() {
				String p="";
				for(int t=start;t<tps;t++) {
					montprev=t;
					for(int z=0;z<sls;z++) {
						if(t==start && z==0 && monzprev!=0)z=monzprev;
						monzprev=z;
						int n=t*chs*sls+z*chs;
						String slb=slbs[n];
						if(slb==null || slb.isEmpty()) {threadrunning=false;return;}
						for(int c=0;c<chs;c++) {
							ImageStatistics im=ImageStatistics.getStatistics(imgst.getProcessor(n+c+1),ImageStatistics.MEAN, null);
							if(im.mean<(monitorAves[c][z]*MONITORFRAC))monitorAlarm(z+1,t+1);
							monitorAves[c][z]=(monitorAves[c][z]*t+(float)im.mean)/(t+1);
						}
						//p="2p monitor: "+IJ.pad((int)(100.0*(double)(t*sls+z+1)/(double)(tps*sls)),2)+"%";
						p="2p monitor position: t"+(t+1)+" z"+(z+1);
						p=p+"\n"+"c1        c2        c3";
						for(int i=0;i<sls;i++) {
							p=p+"\n";
							for(int j=0;j<chs;j++)p+=IJ.pad((int)(100*monitorAves[j][i]), 5)+"   ";
						}
						p+="\n"+warning;
						tp.clear();
						tp.append(p);
					}
				}
				threadrunning=false;
			}
		};
		threadrunning=true;
		t.start();
		
	}
	
	private void monitorAlarm(int slice, int frame) {
		if(alarmTrig)return;
		warning="WARNING TP Image (slice "+slice+"/"+sls+", frame "+frame+"/"+totaltps+") is currently less than "+(int)(MONITORFRAC*100f)+"% of previous average!";
		alarmTrig=true;
		java.awt.EventQueue.invokeLater(new Runnable() {
			public void run() {
				IJ.log(warning);
				IJ.selectWindow("Log");
			}
		});
		sendAlarmWebhook();
	}
	
	public static void sendAlarmWebhook() {

		final String wh=Prefs.get("AJ.TwoPhoton_Import.alarmwebhook", "");
		if(wh==null || "".contentEquals(wh))return;
		new Thread() {
			public void run() {
				if(wh!=null && wh.startsWith("http")) {
					try {
						String cmd="curl -X POST -H \"Content-Type: application/json\" "+wh;
						if(IJ.isWindows() && cmd.contains("&"))cmd.replace("&", "^&");
						if(cmd.contains("chat.googleapis.com"))cmd=cmd+" -d \"{\\\"text\\\":\\\"2P Alarm Triggered!!\\\"}\"";
						Process p=Runtime.getRuntime().exec(cmd);
						if(IJ.debugMode) {
							StringBuffer sb=new StringBuffer(256);
							BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
							String line;
							while ((line=reader.readLine())!=null)  {
				        		sb.append(line+"\n");
				        	}
							IJ.log(sb.toString());
						}
						else IJ.log("--Sent webhook--");
					}catch(Exception e) {
						IJ.log(e.getLocalizedMessage());
					}
				}
				IJ.beep();
				IJ.wait(1200);
				IJ.beep();
				IJ.wait(1200);
				IJ.beep();
				IJ.wait(1200);
			}
		}.start();
	}
	
	private void imageUpdater(boolean doz) {
		if(allocate) {allocatedStackUpdate(); return;}
		ImagePlus imp=img, newimg=null, znewimg=null;
		int zorno=doz?2:1;
		for(int i=0;i<zorno;i++) {
			if(i==1) imp=zimg;
			if(imp==null) {IJ.log("Imp was null");return;}
			ImageCanvas ic=imp.getCanvas();
			int dcw=0;
			if(!ic.getClass().getName().startsWith("ajs.joglcanvas"))dcw=0;
			else {
				dcw=1;
				for(WindowListener wl:imp.getWindow().getWindowListeners()) {if(wl.getClass().getName().equals("ajs.joglcanvas.JOGLImageCanvas")) {dcw=2;break;}}
			}
			Dimension wd=imp.getWindow().getSize();
			Point wl=imp.getWindow().getLocation();
			int ch=imp.getC(),sl=imp.getZ(),fr=imp.getT();
			double zoom=imp.getCanvas().getMagnification();
			Rectangle sr=imp.getCanvas().getSrcRect();
			boolean isAni= ((StackWindow)imp.getWindow()).getAnimate();
			if(isAni) {
				((StackWindow)imp.getWindow()).setAnimate(false);
				IJ.wait(1000);
				//WindowManager.setCurrentWindow(imp.getWindow()); IJ.runPlugIn("ij.plugin.Animator", "stop");
			}
			
			IJ.showStatus("Updating "+imp.getTitle()+"...");
			if(i==0) {
				newimg=loadImage(frend+1,frms);
				if(dotimes)updateImageSliceTimes(newimg,dir,starttimestr,0,newimg.getStackSize());
			}
			else znewimg=zProject(newimg);
			
			imp.lock();
			stackConcatenator(imp, i==0?newimg:znewimg);
			imp.updateAndRepaintWindow();
			imp.unlock();
			if(dcw>0 && imp.getWindow().getClass().getName().equals("ij.gui.StackWindow")) {
				if(dcw==1)IJ.run("Convert to JOGL Canvas");
				else if(dcw==2)IJ.run("Open JOGL Canvas Mirror");
				IJ.wait(1000);
			}
			imp.getWindow().setSize(wd);
			imp.getWindow().setLocationAndSize(wl.x,wl.y,wd.width,wd.height);
			if(sr.width!=imp.getWidth() && sr.height!=imp.getHeight()) {
				imp.getCanvas().setSourceRect(sr);
				imp.getCanvas().setMagnification(zoom);
			}
			imp.setPosition(ch, sl, fr);
			imp.updateAndRepaintWindow();
			if(isAni) {WindowManager.setCurrentWindow(imp.getWindow()); IJ.runPlugIn("ij.plugin.Animator", "start");}
			if(i==0 && frms<totaltps)addUpdateButton();
			preStrip(imp,true);
		}
		lastz=sl;
		newimg.changes=false;
		newimg.close();
		if(doz) {znewimg.changes=false;znewimg.close();}
	}
	
	void stackConcatenator(ImagePlus img, ImagePlus newimg) {
		ImageStack imgst=img.getImageStack();
		ImageStack newimgst=newimg.getImageStack();
		int newsize=newimgst.getSize();
		String[] labels=newimgst.getSliceLabels();
		int slices=img.getNSlices();
		int channels=img.getNChannels();
		int newfrms=img.getNFrames()+newimg.getNFrames();
		for(int i=0;i<newsize;i++) {
			imgst.addSlice(labels[i],newimgst.getProcessor(i+1));
		}
		img.setDimensions(channels, slices, newfrms);
		
	}
	
	public static void stackPlacer(ImagePlus img, ImagePlus newimg, int offsetT, int offsetZ) {
		if(img==null)return;
		if(newimg==null)return;
		ImageStack imgst=img.getImageStack();
		ImageStack newimgst=newimg.getImageStack();
		int newsize=newimgst.getSize();
		String[] newlabels=newimgst.getSliceLabels();
		Object[] newstack=newimgst.getImageArray();
		String[] labels=imgst.getSliceLabels();
		Object[] stack=imgst.getImageArray();
		int slices=img.getNSlices();
		int channels=img.getNChannels();
		for(int i=0;i<newsize;i++) {
			stack[(offsetT-1)*channels*slices+(offsetZ-1)*channels+i]=newstack[i];
			labels[(offsetT-1)*channels*slices+(offsetZ-1)*channels+i]=newlabels[i];
		}
	}
	
	private ImagePlus getLatestMax() {
		ImagePlus res=null;
		boolean hadzimg=(zimg!=null);
		if(!hadzimg) {
			int fr=img.getT();
			img.setPosition(img.getC(), img.getZ(), img.getNFrames());
			res=zProject(img,1,img.getNSlices(),false, zmethod);
			img.setPosition(img.getC(), img.getZ(), fr);
		}else res=zProject(zimg,zimg.getNFrames(),zimg.getNFrames());
		return res;
	}
	
	void exLoc() {
		exlocoutput=exLoc(infofile);
	}
	
	String exLoc(String infofilepath){
		String exlocoutput="";
		xylist=new ArrayList<String>();
		double xysize=1.0d, zsize=0.0d, tsize=1.0d;
		
		if(!infofilepath.endsWith("xml") && !infofilepath.endsWith("oif")) return "Don't know how to read info file: "+infofilepath;
		if(infofilepath.endsWith(".oif")) isOif=true;

		starttimestr="I dunno";
		String lwv="NA";
		String zoom="NA", lpower="NA", lpowerend="NA",objective="NA",pixels="0";
		String averaging="0", averagingtype="None";
		String[] gains=new String[MAXCHS], gainends=new String[MAXCHS], mults=new String[MAXCHS];
		for(int i=0;i<MAXCHS;i++) {
			gains[i]="NA";gainends[i]="NA"; mults[i]="";
		}
		
		String xpart="NA",ypart="NA",zpart="NA",locstr;
		
		double xmlintervaltime=0, totaltime;
		int steps=1, tps=1, chs=0;
		
		//String startdate="", version="NA";
		//double zpartend, xtotalsize, ytotalsize
		//boolean pchange=false;
		
		xmli=-1;
		long ptime=0;
		if(TwoPhoton_Import.debug) {IJ.log("Importing xmlfile...");ptime=System.currentTimeMillis();}
		xmlfile=openWithCharset(infofilepath).split("\n");
		if(TwoPhoton_Import.debug) {IJ.log("Xmlfile load took "+(System.currentTimeMillis()-ptime)+"ms");}
		
	 	if(isOif){   //for olympus oifs
	 		
	 		locs=1; //oifs don't have multiple locations as far as I know.
	 		
	 		//Find start date and time. Typo of ImageCaptureDate is currently ImageCaputreDate
	 		starttimestr=getNextXmlData("ImageCap", "I dunno", true);
	 		if(xmli==-1){IJ.error("Could not get data from oif file"); return "";}
	 		else{
	 			if(xmlfile[xmli+1].contains("MilliSec"))starttimestr+=":"+getData(xmlfile[xmli+1]);
		 		//version=getData(xmlfile[xmlfile.length-1]);
		 		//Averaging, called IntegrationCount and IntegrationType, are 2 and 3 after Capture Date
		 		if(xmlfile[xmli+2].contains("IntegrationCount"))averaging=getData(xmlfile[xmli+2]);
		 		if(xmlfile[xmli+3].contains("IntegrationType"))averagingtype=getData(xmlfile[xmli+3]);
	 		}
	 		
	 		//Laser Wavelength   also we get it later with Laser 0 parameters
	 		lwv=getNextXmlData("LaserWavelength", lwv, true);
	 		
	 		//Zoom
	 		zoom=getNextXmlData("ZoomValue", zoom, true);
	 		
	 		//xysize
			//oif does not contain xy stage data but rather image xy dimension size data
	 		//i=findData(xmlfile,"AxisCode=\"X",i,0);
	 		//i=findData(xmlfile,"EndPosition",i,0);
	 		//xtotalsize=parseDoubleTP(getData(xmlfile[i]));
	 		//Ysize
			//oif does not contain xy stage data but rather image xy dimension size data
	 		//i=findData(xmlfile,"AxisCode=\"Y",i,0);
	 		//i=findData(xmlfile,"EndPosition",i,0);
	 		//ytotalsize=parseDoubleTP(getData(xmlfile[i]));
	 		
	 		//Chs
	 		//number of chs
	 		if(moveXmlTo("AxisCode=\"C", true))
	 			chs=getNextXmlInt("EndPosition", false);
	 		
	 		//ZSize
	 		//it is the actual Z position (in nm)
	 		//i=findData(xmlfile,"AxisCode=\"Z",i,0);
	 		//i=findData(xmlfile,"EndPosition",i,0);
	 		//zpartend=AJ_Utils.parseDoubleTP(getData(xmlfile[i]))/1000; //oifs give z in nm not um
	 		
	 		//Z Axis
	 		if(moveXmlTo("AxisCode=\"Z", true)) {
	 			//Z Step Interval
	 			zsize=getNextXmlDouble("Interval", false)/1000;//oifs give z in nm not um
	 			if(zsize<0)zsize=-1d;
	 		
		 		//Number of Slices
		 		steps=Math.max(getNextXmlInt("MaxSize", false),1);
	
				//Z position
		 		zpart=Double.toString(getNextXmlDouble("StartPosition", false)/1000); //oifs give z in nm not um
	 		}

			//T Axis
	 		if(moveXmlTo("AxisCode=\"T", true)) {
	 			//Time in total
	 			totaltime=getNextXmlDouble("EndPosition", false)/1000d; //oifs give t in ms
	 		
				//Total Timepoints number
		 		tps=Math.max(getNextXmlInt("GUI MaxSize", false),1);//No we DO want GUI MaxSize
				//i=findData(xmlfile,"MaxSize",i+1,0); //+1 because we want the second MaxSize not GUI MaxSize
		 		
				//Time interval
				xmli-=2; //go back two lines
				xmlintervaltime=getNextXmlDouble("Interval", false)/1000d;
				if(xmlintervaltime<=0){xmlintervaltime=(double)Math.round(totaltime/tps*1000d)/1000d;}
	 		}
			
			//i=findData(xmlfile,"StartPosition",i+1,0);
			//startsecs=getData(xmlfile[i])/1000; //oifs give t in ms
			//probably startsecs=0
	 		moveXmlTo("[Channel 1 Parameters]",true);
	 		int limit=findData(xmlfile,"Corr Bright",xmli,0);
			for(int j=0;j<MAXCHS;j++) {
				xmli=findData(xmlfile,"[Channel "+(j+1),0,limit);
				if(xmli<=limit){
					xmli=findData(xmlfile,"AnalogPMTGain",xmli,limit);
					if(xmli<limit) {
						String temp=getData(xmlfile[xmli]);
						if(!temp.contentEquals("1000"))mults[j]="x"+(double)AJ_Utils.parseIntWithin(temp)/1000d;
					}
					xmli=findData(xmlfile,"AnalogPMTVoltage",xmli,limit);
					if(xmli<limit)gains[j]=getData(xmlfile[xmli]);
					else gains[j]="NA";
				}else gains[j]="NA";
			}
			
			if(moveXmlTo("[Laser 0", true)) {
				lpower=getNextXmlData("LaserTrans", lpower, false);
				lwv=getNextXmlData("LaserWavelength", lwv, false);
			}
			pixels=getNextXmlData("ImageWidth", pixels, true);
			xysize=getNextXmlDouble("WidthConvertValue", false);
			
			if(moveXmlTo("[Corr Bright 00 Laser00", true)) {
				lpower=getNextXmlData("Corr Bright 00 Laser00 ulIntensity", lpower, false);
				int cn=0;
				while(moveXmlTo("[Corr Bright "+String.format("%02d",++cn)+" Laser00", false));
				cn--;xmli--;
				if(cn>0)lpowerend=getNextXmlData("Corr Bright "+String.format("%02d",cn)+" Laser00 ulIntensity", lpowerend, false);
			}

			//olympus stores xy stage data in the pty file in the oif.files directory sometimes
	 		String oifdir=infofilepath+".files"+File.separator;	 		
	 		
 			String startptypath,endptypath;
 			xmli=0;
 			totalsls=steps;
 			if(sls>steps)totalsls=sls;

			for(int ch=1;ch<=chs;ch++) {
				startptypath=oifdir+"s_"+((chs>1)?"C"+String.format("%03d",ch):"")+((steps>1)?"Z001":"")+((tps>1)?"T001":"")+".pty";
				if(startptypath.equals(oifdir+"s_.pty"))startptypath=oifdir+"s_C001.pty";
				if(!(new File(startptypath)).exists()){
					IJ.log("Can't find "+startptypath);
				}else{
					//start pty file
					String[] ptyfile=openWithCharset(startptypath).split("\n");
					xmlfile=ptyfile;
					if(ptyfile.length>0){
						if(ch==1) {
							objective=searchAndGetData(ptyfile, "ObjectiveLens Name");
							if(objective.equals("XLPLN      25X W  NA:1.05")) objective="25X W NA:1.05";
							double dt=getNextXmlDouble("ExcitationOutPutLevel", true);
							if(dt!=Double.NEGATIVE_INFINITY)lpower=Long.toString(Math.round(dt*10));
							dt=getNextXmlDouble("AbsPositionValueX", true);
							if(dt!=Double.NEGATIVE_INFINITY)xpart=Double.toString(dt/1000);//oifs give position in nm
							dt=getNextXmlDouble("AbsPositionValueY", false);
							if(dt!=Double.NEGATIVE_INFINITY)ypart=Double.toString(dt/1000);//oifs give position in nm
						}
						gains[ch-1]=searchAndGetData(ptyfile, "PMTVoltage");
					}
				}
				if(sls<steps)continue;
				endptypath=oifdir+"s_"+((chs>1)?"C"+String.format("%03d",ch):"")+((steps>1)?("Z"+String.format("%03d",steps)):"")+((tps>1)?"T001":"")+".pty";
				if(endptypath.equals(oifdir+"s_.pty"))endptypath=oifdir+"s_C001.pty";
				if(!(new File(endptypath)).exists()){
					IJ.log("Can't find "+endptypath);
				}else{
					String[] ptyfile=openWithCharset(endptypath).split("\n");
					xmlfile=ptyfile;
					if(ptyfile.length>0){
						if(ch==1) {
							xmli=0;
							double dt=getNextXmlDouble("ExcitationOutPutLevel", true);
							if(dt>0)lpowerend=Long.toString(Math.round(dt*10));
						}
						gainends[ch-1]=searchAndGetData(ptyfile, "PMTVoltage");
					}
				}
			}
			
			locstr=""+xpart+ "  "+ypart + "  "+ zpart+ "  "+ zsize;
			xylist.add(locstr);
			
	 	} else {
	 		//IJ.log("No prairie yet");
	 		//if regular Prairie not oif
	 		
			//loxf=xmlfile.length;
	 		int fci=0;
	 		String version=getNextXmlData("version", "version", "NA", true);
	 		if(xmli==-1) {IJ.error("Could not read Prairie XML file"); return "";}
	 		starttimestr=getNextXmlData("date", "date", "I dunno", true);
	 		for(int curri=1; curri<50; curri++) {
	 			if(xmli+curri+2>xmlfile.length)break;
	 			if(xmlfile[xmli+curri].contains("cycle=")) {fci=xmli+curri; break;}
	 		}
	 		if(fci==0) {IJ.error("Can't find cycle in Prairie xml"); return "";}
	 		int i=0;
	 		chs=0; while(xmlfile[i].indexOf("<File channel")== -1) i++;
	 		while(xmlfile[i].indexOf("<File channel")> -1) {chs++; i++;}
	 		objective=getNextXmlData("objectiveLens", "value", "NA", false);
	 		pixels=getNextXmlData("pixelsPerLine", "value", "NA", false);
	 		averaging=getNextXmlData("frameAveraging", "value", "NA", false);
	 		xpart=getNextXmlData("positionCurrent_XAxis", "value", "NA", false);
	 		int fxac=xmli-fci;
	 		ypart=getNextXmlData("positionCurrent_YAxis", "value", "NA", false);
	 		zpart=getNextXmlData("positionCurrent_ZAxis", "value", "NA", false);
	 		zoom=getNextXmlData("opticalZoom", "value", "NA", false);
	 		xysize=AJ_Utils.parseDoubleTP(getNextXmlData("micronsPerPixel", "value", "NA", false));
	 		gains[0]=getNextXmlData("pmtGain", "value", "NA", false);
	 		for(i=1;i<MAXCHS; i++) {
	 			if(xmlfile[xmli+1].contains("pmtGain"))
	 				gains[i]=getNextXmlData("pmtGain", "value", "NA", false);
	 		}
	 		lpower=getNextXmlData("laserPower", "value", "NA", false);

			if(version=="4.3.1.17") xysize/=AJ_Utils.parseDoubleTP(zoom);
	 		

			//Get z-position of second slice of Z-series to determine zsize
	 		String zpart2=getNextXmlData("positionCurrent_ZAxis", "value", "NA", false);
			if(TwoPhoton_Import.debug) {IJ.log("zpart1:"+zpart+" zpart2:"+zpart2);}
	 		int nzi=0;
	 		if(!zpart2.contentEquals("NA") && !zpart.contentEquals("NA")) {
	 			zsize=1.0;
	 			double zp2d=AJ_Utils.parseDoubleTP(zpart2), zpd=AJ_Utils.parseDoubleTP(zpart);
	 			if(zp2d!=Double.NEGATIVE_INFINITY && zpd!=Double.NEGATIVE_INFINITY)zsize=zp2d-zpd;
	 			nzi=xmli-fci;
	 			if(TwoPhoton_Import.debug) {IJ.log("zsize "+zsize);}
	 		}
			
			//int firstcyclenum=AJ_Utils.parseIntTP(getData(xmlfile[i],"cycle"));
	
			// Need to fix for this Prairie Error
			//badnumstr="0.1689 0.1953 1.0135"; pchange=false;
			//if(indexOf(badnumstr,d2s(xysize*zm*pixels/512,4))!=-1) {xysize=propxy*512/pixels/zm; pchange=true;}
	 		
			i=0;
			while((xmlfile[i].indexOf("cycle=")== -1 && xmlfile[i].indexOf("PVScan")== -1) && (i<(xmlfile.length-1))) i++;
			if((i+1)<xmlfile.length && xmlfile[i].contains("cycle=") && xmlfile[i+1].contains("absoluteTime"))
				tsize=AJ_Utils.parseDoubleTP(getData(xmlfile[i],"absoluteTime"));
			i--;
			while(xmlfile[i].indexOf("index=")== -1 && i>0) i--;
			steps=AJ_Utils.parseIntTP(getData(xmlfile[i],"index"));
			xmli=i;
			gainends[0]=getNextXmlData("pmtGain", "value", "NA", false);
	 		for(i=1;i<MAXCHS; i++) {
	 			if(xmlfile[xmli+1].contains("pmtGain"))
	 				gainends[i]=getNextXmlData("pmtGain", "value", "NA", false);
	 		}
	 		lpowerend=getNextXmlData("laserPower", "value", "NA", false);
	
			boolean timecounter=false;
			tps=1;
	
			for(i=0;i<xmlfile.length;i++){
				if((xmlfile[i].indexOf("cycle=")>-1)&&((i+Math.max(nzi,fxac+2))<xmlfile.length)){
					//if(!timecounter) {
					//	xylisttemp=newArray(xylist.length+1);
					//}
					xpart=getData(xmlfile[i+fxac],"value");
					ypart=getData(xmlfile[i+fxac+1],"value");
					zpart=getData(xmlfile[i+fxac+2],"value");
					zpart2=getData(xmlfile[i+nzi],"value");
					if(TwoPhoton_Import.debug) {IJ.log("zpart1:"+zpart+" zpart2:"+zpart2);}
					if(zsize>0) zsize=(double)Math.round(Math.abs(AJ_Utils.parseDoubleTP(zpart2)-AJ_Utils.parseDoubleTP(zpart))*100)/100;
					String xystring=xpart+ "  "+ypart + "  "+ zpart+ "  "+ zsize;
					if(!timecounter){
						if(xylist.size()>0) if(xylist.get(0).contentEquals(xystring))  {timecounter=true; tps++;}
						if(!timecounter) {
							xylist.add(xystring);
						}
					} else if(xylist.get(0).contentEquals(xystring)) tps++;
					times.add(((double)Math.round(AJ_Utils.parseDoubleTP(getData(xmlfile[i+1],"absoluteTime"))*1000.0))/1000.0);
						
				}
			}
			xmlintervaltime=times.size()>0?times.get(0):0;
			locs=xylist.size();
			
	 	}//if isOif else
	 	int i;
	 	double adder=0;
	 	double avetimeframe=0,onelessavetimeframe=0,firsttimeframe=0;
	 	if(times.size()>1){
			for(i=0;i<tps-1;i++) adder = adder + (times.get((i+1)*locs)) - times.get(i*locs);
			if(tps>2) {i--; double onelesstp=adder-(times.get((i+1)*locs) - times.get(i*locs)); onelessavetimeframe=(onelesstp/(tps-2));}
			if(tps>1) {i=0; firsttimeframe=(times.get(((i+1)*locs)) - times.get(i*locs));}
			avetimeframe=(adder/(double)(tps-1));
			tsize=avetimeframe;
		}else{tsize=xmlintervaltime;}
		for(i=0;i<xylist.size();i++){
			xylist.set(i,xylist.get(i)+"  "+tsize+"  "+tps);
		}
		
		String gtxt="";
		for(i=0;i<chs;i++){
			if(!gains[i].equals("NA")){
				gtxt=gtxt+"Gain"+(i+1)+": "+gains[i];
				if(!gainends[i].equals("NA") && !gains[i].equals(gainends[i]))gtxt=gtxt+"-"+gainends[i];
				gtxt+=mults[i];
				gtxt=gtxt+"    ";
			}
		}
		String lwvtxt=""; if(lwv!="NA")lwvtxt="  Wv: "+lwv;
		String avetext="";
		if(!averaging.equals("0")){avetext="  Ave:"+averaging+" "+averagingtype;}

		
		// set up output and TPI variables
		locs=xylist.size();
		
		exlocoutput="Started at:  "+starttimestr+"   Locs: "+locs+"  Zoom: "+zoom+"  Obj: "+objective+"\n";
		exlocoutput=exlocoutput+"XY: "+pixels+" x "+xysize+"um   Z: "+steps+" x "+zsize+"um   T: "+tps+" x "+tsize+"s"+avetext+"\n";
		exlocoutput=exlocoutput+gtxt+"Laser: "+lpower+((!lpowerend.equals("NA")&&!lpower.equals(lpowerend))?("-"+lpowerend):"")+lwvtxt+"\n";
		
		String[] xys;
		for(i=0;i<xylist.size();i++) {
			xys=xylist.get(i).split("  ");
			String step1="Location "+(i+1)+":   "+xys[0]+"  "+xys[1]+"  "+xys[2];
			String step2="  Single Image";
			if(zsize>0) step2="  Zstep: "+xys[3];
			exlocoutput=exlocoutput+step1+step2+"\n";
		}

		if(xysize>0) {cal.setUnit("microns"); cal.pixelWidth=xysize; cal.pixelHeight=cal.pixelWidth;}
		if(zsize>0) {cal.setUnit("microns"); cal.pixelDepth=zsize;}
		if(tsize>0)cal.frameInterval=tsize;
		if(hasAJZ)tps/=sls;
		totaltps=frms;
		if(tps>frms)totaltps=tps;
		
		//For prairie
		if(times.size()>2) {IJ.log("1st tf / ave not last / Ave time frame / XML time:  " +firsttimeframe+" / "+ onelessavetimeframe +" / "+ avetimeframe +" / "+ xmlintervaltime);}
		else if(times.size()>1) {IJ.log("Time btn 1+2 / Ave time frame / XML time:  " +firsttimeframe+" / "+ avetimeframe +" / "+ xmlintervaltime);}

		if(TwoPhoton_Import.debug) {IJ.log("ExLoc took "+(System.currentTimeMillis()-ptime)+"ms");}
		return exlocoutput;
	}
	
	//Prairie location utilities
	void createPrairieLocXYfile() {
		boolean addloc=false;
		String title = "Exlocs.xy";
		int loffset=0;
		String startstr="<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<StageLocations>\n";
		TextWindow tw;
		tw=(TextWindow)WindowManager.getWindow(title);
		if(tw==null) {
			tw=new TextWindow(title, 80, 20);
		} else {
			addloc=true;  //=getBoolean("OK to add to Exlocs window (no means clear it)?"); 
			if(addloc) {
				String oldwindow=tw.getTextPanel().getText();
				if(oldwindow.startsWith(startstr)) startstr=oldwindow.substring(0,oldwindow.indexOf("</StageLocations>"));
				String[] oldsplit=oldwindow.split("\n");
				loffset=oldsplit.length-3;
			}
		}
		tw.getTextPanel().clear();
		tw.append(startstr);
		for(int i=0;i<locs;i++){
			String[] parts=xylist.get(i).split("  ");
			tw.append("  <StageLocation index=\""+(i+loffset)+"\" x=\""+parts[0]+"\" y=\""+parts[1]+"\" z=\""+parts[2]+"\" />\n");
		}
		tw.append("</StageLocations>");
	}
	
	void printLocationMap() {
	
		ImagePlus lm=WindowManager.getImage("LocMap");
		int currmapno;
		if(lm==null) {
			IJ.newImage("LocMap", "8-bit White", 500, 500, 1);
			currmapno=0;
		}else{
			currmapno=AJ_Utils.parseIntTP(lm.getInfoProperty());
			if(currmapno<0) currmapno=0;
		}
		IJ.setForegroundColor(0,0,0);
		lm.getProcessor().setJustification(ImageProcessor.CENTER_JUSTIFY);
		lm.getProcessor().drawString("Origin",250,250);
		for(int i=0;i<locs;i++){
			String[] parts=xylist.get(i).split("  ");
			double x=AJ_Utils.parseDoubleTP(parts[0]);
			double y=AJ_Utils.parseDoubleTP(parts[1]);
			if(x==0 && y==0) lm.getProcessor().drawString("(+"+(i+1)+")",278,250);
			else lm.getProcessor().drawString(Integer.toString(i+currmapno+1)+"\n"+Math.round(x)+"  "+Math.round(y),(int)(-1*x/4+250),(int)(-1*y/4+250));
		}
		lm.setProperty("Info", currmapno+locs);
	}
	
	void updateImageSliceTimes(int start, int end) {
		updateImageSliceTimes(img,dir,starttimestr,start,end);
	}
	
	public static void updateImageSliceTimes() {
		updateImageSliceTimes(WindowManager.getCurrentImage());
	}
	
	public static void updateImageSliceTimes(ImagePlus imp) {
		updateImageSliceTimes(imp,null,null,0,imp.getStackSize());
	}
	
	/**
	 * 
	 * @param imp
	 * @param dir
	 * @param starttimestr
	 * @param start 0 based index of stacklabel array
	 * @param end up to ImagePlus.getStackSize()
	 */
	public static void updateImageSliceTimes(ImagePlus imp, String dir, String starttimestr, int start, int end) {
		if(imp==null) {IJ.error("No image"); return;}
		IJ.showStatus("Loading Image Slice Times");
		long sms=System.currentTimeMillis();
		boolean visible=imp.isVisible();
		//String[] ptypaths;
		if((dir==null || dir.isEmpty() || dir.indexOf(File.separator)==-1)) {
			String info=imp.getInfoProperty();
			if(info!=null && !info.isEmpty())
				dir=info.split("\n")[0];
		}
		if(dir==null || dir.isEmpty() || dir.indexOf(File.separator)==-1) {
			FileInfo fi = imp.getOriginalFileInfo();
			if (fi!=null && fi.directory!=null) dir= fi.directory;
		}
		if(dir==null || dir.isEmpty() || dir.indexOf(File.separator)==-1){IJ.showMessage("Need original directory"); dir=IJ.getDirectory("");}
		if(dir==null)return;
		
		long starttime=0;
		if(starttimestr==null || starttimestr.isEmpty())starttimestr=(String)imp.getProperty("2p-starttime");
		if(starttimestr!=null && !starttimestr.isEmpty())starttime=AJ_Utils.sysTime(starttimestr);
		
		ImageStack imst=imp.getStack();
		if(imst==null) {IJ.error("Not a stack"); return;}
		String[] labels=imst.getSliceLabels();
		
		//String RGBname=(new File(dir)).getName();
		//String xmlfilepath=dir+RGBname+".xml";
		//boolean isPrairie=(new File(xmlfilepath)).exists();
		
		
		
		//ptypaths=new String[nSlices];
		if(labels!=null && labels.length>0) {
			for(int i=start;i<end;i++) {
				String sllabel=labels[i];
				if(sllabel!=null){
					String[] label=sllabel.split("\n");
					int ind=-1;
					for(int j=0;j<label.length;j++) {
						if(label[j].startsWith("s_")&&label[j].endsWith(".tif")) {ind=j; break;}
					}
					if(ind==-1) {IJ.log("Not an oif file (with slices labeled)"); return;}
					String slicetime=getSliceTime(dir+label[ind].replaceFirst("tif", "pty"));
					if("".contentEquals(slicetime)){IJ.log("No time for slice "+(i+1));}
					if(!sllabel.endsWith("\n") && !"".contentEquals(sllabel))sllabel+="\n";
					sllabel+="ptytime: "+slicetime;
					if(starttime>0)sllabel+="\nStarttime: "+starttime;
					if(visible && !imp.isVisible()) {IJ.showProgress(1.0); IJ.showStatus("Canceled slice times, image was closed"); return;}//image closed
					labels[i]=sllabel;
				}
				//else {IJ.log("Slice "+(i+1)+" was empty"); IJ.showProgress(1.0);return;}
				IJ.showProgress((double)i/(double)((end-start)-1));
			}
			IJ.showStatus("Completed addition of pty slice times!");
			String log=IJ.getLog();
			if(log!=null) {
				String[] logs=log.split("\n");
				String ll=logs[logs.length-1];
				if(ll.startsWith("LoadImage"))IJ.log("\\Update:"+ll+"  STs took: "+(System.currentTimeMillis()-sms)/1000.0);
			}else IJ.log("STs took: "+(System.currentTimeMillis()-sms)/1000.0);
		}else IJ.error("Oif file needs slice labels");
		
	}
	
	/*
	private static String[] getSliceTimes(String[] ptyfilepaths) {
		int ptyl=ptyfilepaths.length;
		String[] alltimes=new String[ptyl];
		for(int j=0;j<ptyl;j++){
			alltimes[j]=getSliceTime(ptyfilepaths[j]);
			IJ.showProgress((double)j/(double)(ptyl-1));
		}
		IJ.showProgress(1.0);
		return alltimes;
	}
	*/
	
	private static String getSliceTime(String ptyfilepath) {
		String temp=openWithCharset(ptyfilepath);
		if(temp=="") {IJ.error("Bad OIF directory");return "";}
		String[] ptyfile=temp.split("\n");
		int i=0; i=findData(ptyfile,"Axis 4 Parameters",0,0);
		i=findData(ptyfile,"AbsPositionValue",i,0);
		if(i<ptyfile.length) return Double.toString(AJ_Utils.parseDoubleTP(getData(ptyfile[i]))/1000d);
		return "";
	}
	
	private String getNextXmlData(String findtext, String ifnotfound, boolean wrap) {
		return getNextXmlData(findtext, "", ifnotfound, wrap);
	}
	
	private String getNextXmlData(String findtext, String findwithin, String ifnotfound, boolean wrap) {
		for(int i=xmli+1; i<xmlfile.length; i++) {
			if(xmlfile[i].indexOf(findtext)> -1) {xmli=i; return getData(xmlfile[i], findwithin);}
		}
		if(wrap) {
			for(int i=0;i<=xmli;i++) {
				if(xmlfile[i].indexOf(findtext)> -1) {xmli=i; return getData(xmlfile[i], findwithin);}
			}
		}
		return ifnotfound;
	}
	
	private int getNextXmlInt(String findtext, boolean wrap) {
		return AJ_Utils.parseIntTP(getNextXmlData(findtext, "", wrap));
	}
	
	private double getNextXmlDouble(String findtext, boolean wrap) {
		return AJ_Utils.parseDoubleTP(getNextXmlData(findtext, "", wrap));
	}
	
	private boolean moveXmlTo(String findtext, boolean wrap) {
		int i=xmli;
		getNextXmlData(findtext, "", wrap);
		if(xmli==i)return false;
		return true;
	}

	
	//
	// Utility Funcitons
	//
	
	//oif functions
	private static String getData(String str) {
		return getData(str,"");
	}
	
	
	private static String getData(String str, String findtext){
		if(str.indexOf("=")==-1) return "";
		String[] a=str.split("=");
		String data=a[1];
		if(findtext!=null && !findtext.contentEquals("")) {
			for(int i=0;i<a.length-1;i++) {
				if(a[i].contains(findtext)) {data=a[i+1]; break;}
			}
		}
		if(data.contentEquals(""))return "NA";
		if((data.indexOf("\'")>-1 || data.indexOf("\"")>-1)) data= data.substring(1,data.length()-1);
		if((data.indexOf("\'")>-1)) data= data.substring(0,data.indexOf("\'"));
		if((data.indexOf("\"")>-1)) data= data.substring(0,data.indexOf("\""));
		return data;
	}
	
	private static int findData(String[] xmlfile,String findtext,int starti,int limit){
		if(limit<1 || limit>(xmlfile.length))limit=(xmlfile.length);
		for(int i=starti;i<limit;i++){
			if(xmlfile[i].indexOf(findtext)> -1) {return i;} 
		}
		return starti;
	}

	//line from bigfile must start with or be close to starting with search
	private static String searchAndGetData(String[] bigfile, String search) {
		for(int i=0;i<bigfile.length;i++) {
			int ios=bigfile[i].indexOf(search);
			if(ios>-1 && ios<3) {
				return getData(bigfile[i], search);
			}
		}
		return "NA";
	}
	
	private static String openWithCharset(String path) {
		return openWithCharset(path, null);
	}
	
	private static String openWithCharset(String path, String charset) {
		Scanner scanner=null;
		String res="";
		try{
			if(charset==null || charset.contentEquals("")) {
				charset=StandardCharsets.UTF_16.name();
				if(path.endsWith("xml")||path.endsWith("cfg")||path.endsWith("ajz"))charset=StandardCharsets.UTF_8.name();
			}
			scanner = new Scanner(Paths.get(path), charset);
			res= scanner.useDelimiter("\\A").next();
		}catch (Exception e){
			IJ.error(""+e.getMessage());
		}
		if(scanner!=null)scanner.close();
		return res;
	}
	
	private void addThisAdjustmentListener() {
		preStrip(img, true);
		if(zimg!=null)preStrip(zimg, true);
	}
	
	private void preStrip(ImagePlus imp, boolean add) {
		if(imp==null)return;
		ImageWindow imgwin=imp.getWindow();
		Component[] cps=((Container) imgwin).getComponents();
		for(int j=0;j<cps.length;j++) {
			if(cps[j].getClass().getName().contains("Scroll")) {
				AdjustmentListener[] mls=(AdjustmentListener[]) cps[j].getListeners(AdjustmentListener.class);
				for(int i=0;i<mls.length;i++) {
					if(mls[i].getClass().getName().startsWith(this.getClass().getName())){
						((ScrollbarWithLabel)cps[j]).removeAdjustmentListener(mls[i]);
						IJ.log("Removed old aL"+mls[i]);
					}
				}
				if(add) {
					((ScrollbarWithLabel)cps[j]).addAdjustmentListener(this);
					//IJ.log("added "+cps[j].getClass().getName());
				}
			}
		}
	}
	
	public synchronized void adjustmentValueChanged(AdjustmentEvent e){
		//IJ.log("\\Update:source: "+e.getSource()+" type:"+e.getAdjustmentType()+" val:"+e.getValue()+" isadj:"+e.getValueIsAdjusting()+" mp:"+mousePressed);
		if(e.getValueIsAdjusting()) this.mousePressed=true;
		else this.mousePressed=false;
	}

	/*
    public void mouseExited(MouseEvent e) {} 
    public void mouseClicked(MouseEvent e) {}	
    public void mouseEntered(MouseEvent e) {}
    public void mouseReleased(MouseEvent e) {
    	this.mousePressed=false;
    }	
    public void mousePressed(MouseEvent e) {
    	this.mousePressed=true;
    	IJ.log("MP");
    } 
    */
	
	public void addUpdateButton() {
		ImagePlus imp=img;
		if(imp.getWindow()==null)return;
		if(!(imp.getWindow() instanceof StackWindow))return;
		StackWindow stwin=(StackWindow) imp.getWindow();
		ScrollbarWithLabel scr=getLastSBWL(stwin);
		removeUpdateButton(stwin,scr);
		
		if(scr!=null) {
			Button updateButton= new Button("U");
			updateButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					/*Thread thread=new Thread() {
						public void run() {
							updating=true;
							String[] upt=new String[] {"U","P","D","A","T","I","N","G"};
							int i=0;
							while(updating) {
								updateButton.setLabel(upt[i]);
								updateButton.repaint();
								i++;
								if(i==upt.length)i=0;
								IJ.wait(100);
							}
						}
					};*/
					Thread thread=new Thread() {
						public void run() {
							updateImage();
						}};
					thread.start();
					//updateImage();
					//updating=false;
				}
			});
			scr.add(updateButton,BorderLayout.EAST);
			stwin.pack();
		}
	}
	
	public void removeUpdateButton() {
		if(img==null)return;
		if(img.getWindow()==null)return;
		if(!(img.getWindow() instanceof StackWindow))return;
		removeUpdateButton((StackWindow) img.getWindow(),null);
	}
	
	private ScrollbarWithLabel getLastSBWL(StackWindow stwin) {
		Component[] comps=stwin.getComponents();
		ScrollbarWithLabel scr=null;
		for(int i=0;i<comps.length;i++) {
			if(comps[i] instanceof ij.gui.ScrollbarWithLabel) {
				scr=(ScrollbarWithLabel)comps[i];
			}
		}
		return scr;
	}
	
	private void removeUpdateButton(StackWindow stwin, ScrollbarWithLabel scr) {
		if(scr==null) scr=getLastSBWL(stwin);
		if(scr==null) return;
		Component[] comps=scr.getComponents();
		for(int i=0;i<comps.length;i++) {
			if(comps[i] instanceof Button) {
				if(((Button)comps[i]).getLabel().equals("U")) {
					scr.remove(comps[i]);
					stwin.pack();
				}
			}
		}
	}
	    

}