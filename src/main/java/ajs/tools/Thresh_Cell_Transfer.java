package ajs.tools;

import ij.plugin.*;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.gui.*;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.io.RoiDecoder;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.*;
import ij.process.*;
import ij.text.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.IndexColorModel;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;


/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class Thresh_Cell_Transfer implements PlugIn, MouseListener, KeyListener, MouseWheelListener {

	private final static String version="1.5.0";
	private static boolean DEBUG=false;
	private static double LIPO_RATIO=0.5;
	private static int AVERATIO_GREEN_MIN=50;
	public static enum HEADINGS{
		LABEL("Label", true), ROI("ROI"), CELL("Cell"), AREA("Area"), PERIMETER("Perimeter"), CIRCULARITY("Circularity"), X("X"), Y("Y"),
		MEAN("Mean"), SLICE("Slice"), FRAME("Frame"), THRESH("Thresh"), TIME("Time"), REDMEAN("RedMean"), GREENPIXELS("GreenPixels"), 
		REDPIXELS("RedPixels"), AVERATIO("AveRatio"), MEDRATIO("MedianRatio"), AXONDISTANCE("AxonDistance"), BVDISTANCE("BVDistance"), CCR2FRAME("CCR2Frame", true);
		private String string;
		public boolean isString=false;
		private HEADINGS(String string) {this.string=string;}
		private HEADINGS(String string, boolean isString) {this.string=string; this.isString=isString;}
		public String getString() { return string;}
		public int getIndex() {return indexOf(getString());}
		public static String[] getStrings() {
			HEADINGS[] hs=HEADINGS.values();
			String[] strings=new String[hs.length];
			for(int i=0;i<strings.length;i++)strings[i]=hs[i].getString();
			return strings;
		}
		public static String getFullString() {
			return String.join("\t", getStrings());
		}
		public static String getString(int i){
			return values()[i].getString();
		}
		public static int length() {return values().length;}
		public static int indexOf(String string) {
			String[] hs = getStrings();
			for(int i=0;i<hs.length; i++) {
				if(hs[i].contains(string))return i;
			}
			return -1;
		}
		public static HEADINGS get(int i) {
			return values()[i];
		}
		public static HEADINGS get(String string) {
			for(HEADINGS h : values()) {
				if(h.getString().contentEquals(string))return h;
			}
			return null;
		}
	}
	//private final static double AREATOLERANCE=0.2;
	private final static int MAX_POINTS=8;
	private final static Color addColor=new Color(0,255,0), subColor=new Color(255,0,0), onlyColor=new Color(128,64,255);
	private final static int[] WHEELFACTORS= new int[] {1, 10, 30, 100};
	public static enum DirectRoiTypes{ 
		SUBTRACTIVE("Subtractive"), ADDITIVE("Additive"), ONLY_WITHIN("Only-within");
		private String string;
		private DirectRoiTypes(String string) {
			this.string=string;
		}
		public String getString() {return string;}
		public static String[] getStrings() {
			DirectRoiTypes[] drt = DirectRoiTypes.values();
			String[] strings=new String[drt.length];
			for(int i=0;i<drt.length;i++)strings[i]=drt[i].getString();
			return strings;
		}
	};
	private Overlay soverlay=null;
	private boolean done=false, gocellcomplete=false, updatedLabel=false;
	private boolean addDirectRoi=false, editRoi=false;
	private boolean drawCellLabel=false;
	private boolean autoAccept=false;
	private boolean checkZmax=true;
	private boolean[] buttonpress=new boolean[3];
	private AtomicBoolean altWasDown=new AtomicBoolean(false), shiftWasDown=new AtomicBoolean(false), 
							spacepress=new AtomicBoolean(false), acceptedROI=new AtomicBoolean(false);
	private boolean[] postFirstAccept=new boolean[MAX_POINTS];
	//private boolean showEachPointRoi=true;
	private boolean autoSave=true;
	private int pointIndex=0;
	private ArrayList<WandPoint> xys=new ArrayList<WandPoint>();
	private int curX=-1, curY=-1;
	private int labelsl=0, celln=1;
	private int wheelfactori = 1;
	private int MYLUT=ImageProcessor.RED_LUT;
	private ImagePlus simp, timp, ajtctcpimp=null;
	private String title, endtitle, basetitle;
	private ArrayList<String> cellLabels=new ArrayList<String>();
	private String cellLabel="Unlabeled";
	private AutoWandRoi[] curWand;
	private double[] threshMultiplier=null;
	private TCTPanel tctpanel;
	private Thread[] updateCurWandThreads=null;
	private TctTextWindow results;
	private enum TctLines{
		CURRENTWAND, CURRENTCELL, LASTACCEPTED, CURRENTPOINT, FRAMESLEFT, FRAMESWAND, EXTRA;
	}
	private double[] times;
	private AtomicBoolean shouldStop=new AtomicBoolean(), threshChanged=new AtomicBoolean(false), 
							goToZmax=new AtomicBoolean(false), inThreadCurwand=new AtomicBoolean(true);
	private PlotWindow plotWindow=null;
	private static LUT LUT_GLASBEY_INV=new LUT(ij.plugin.LutLoader.getLut("glasbey inverted"),0,255);
	private final Color DEF_ROI_COLOR=Roi.getColor();
	private String spath="";
	private boolean editing=false, reconfirming=false, skipconfirmation=false, importingFromAllRois=false;
	private ArrayList<Roi> allRois=null;
	private static int greench=2, redch=3;
	private Roi axonRoi=null, duraBVRoi=null, piaBVRoi=null;
	private boolean showAllRois=true;
	private Roi showAllRoi=null, ajtctcpimpAllroi=null;
	private boolean syncWindows=true, fullZ=false;
	private int[] ccr2frames=null;
	private AtomicInteger recalcABV=new AtomicInteger(0);

	public void run(String arg) {
		if(arg.contentEquals("getClosestMaxZ")){
			IJ.log("Closest Max Z: "+getClosestMaxZ());
			return;
		}
		if(arg.contentEquals("ptReslice")){
			ptReslice();
			return;
		}
		if(arg.contentEquals("masksToRois")){
			masksToRois();
			return;
		}
		if(arg.contentEquals("fullz"))fullZ=true;
		TCT(WindowManager.getCurrentImage());
	}

	public static ImagePlus[] getSourceAndTarget(){
		ImagePlus simp=WindowManager.getCurrentImage();
		if(simp==null) {
			IJ.showMessage("No image found");
			return null;
		}
		ImagePlus timp=null;
		String title=simp.getTitle();
		String basetitle=title.endsWith(".tif")?title.substring(0,title.length()-4):title;
		if(basetitle.endsWith("-AJTCT")){
			timp=simp;
			if(!title.endsWith(".tif"))timp.setTitle(title+".tif");
			title=title.substring(0,title.length()-10);
			simp=WindowManager.getImage(title);
			if(simp==null)simp=WindowManager.getImage(title+".tif");
			if(simp==null){IJ.showMessage("Can't find original image: "+title); return null;}
			if(!simp.getTitle().endsWith(".tif"))simp.setTitle(simp.getTitle()+".tif");
			title=simp.getTitle();
			basetitle=title.substring(0,title.length()-4);
		}
		String[] titles=WindowManager.getImageTitles();
		if(timp==null){
			String endtitle=basetitle+"-AJTCT.tif";
			timp=WindowManager.getImage(endtitle);
			if(timp==null) {
				timp=WindowManager.getImage(basetitle+"-AJTCT");
				if(timp!=null)timp.setTitle(endtitle);
			}
			if(timp==null && simp.getNFrames()==1) {
				for(String t : titles) {
					if(t.endsWith("SingleStack.tif")) {
						YesNoCancelDialog ync=new YesNoCancelDialog(null, "AJTCT SingleStack Found", "Use the AJTCT SingleStack image as target?");
						if(ync.cancelPressed()) return null;
						if(ync.yesPressed()) {
							timp=WindowManager.getImage(t);
							timp.setTitle(endtitle);
						}
					}
				}
			}
		}
		ImagePlus ajtctcpimp=null;
		for(String t : titles) {
			if(t.endsWith("-AJTCTcp.tif")) {
				YesNoCancelDialog ync=new YesNoCancelDialog(null, "AJTCTcp Found", "Use "+t+" image as AJTCTcp?");
				if(ync.cancelPressed()) break;
				if(ync.yesPressed()) {
					ajtctcpimp=WindowManager.getImage(t);
					break;
				}
				
			}
		}
		return new ImagePlus[] {simp, timp, ajtctcpimp};
	}

	private static ImagePlus createTarget(ImagePlus simp, boolean fullz) {
		String title=simp.getTitle();
		String basetitle=title.endsWith(".tif")?title.substring(0,title.length()-4):title;
		ImagePlus timp=IJ.createHyperStack(basetitle+"-AJTCT.tif", simp.getWidth(),simp.getHeight(), 2, fullz?simp.getNSlices():1, simp.getNFrames(), 8);
		timp.setDisplayMode(IJ.COMPOSITE);
		timp.show();
		if(LUT_GLASBEY_INV!=null)((CompositeImage)timp).setChannelLut(LUT_GLASBEY_INV, 1);
		Rectangle sbounds=simp.getWindow().getBounds();
		timp.getWindow().setLocationAndSize((int) (sbounds.getX()+sbounds.getWidth()+5),(int) sbounds.getY(),(int) sbounds.getWidth(),65535);
		return timp;
	}

	private boolean setup() {
		//Get Glasbey LUT
		if(LUT_GLASBEY_INV==null) {
			IndexColorModel cm=ij.plugin.LutLoader.getLut("glasbey inverted");
			if(cm!=null)LUT_GLASBEY_INV=new LUT(cm, 0, 255);
			else IJ.log("Please install the LUT: glasbey inverted");
		}

		//title info
		title=simp.getTitle();
		basetitle=title.endsWith(".tif")?title.substring(0,title.length()-4):title;
		if(timp!=null)endtitle=timp.getTitle();
		else endtitle=basetitle+"-AJTCT.tif";

		//if(timp!=null)convertTCTtoGlasbey(timp, simp.getCalibration().pixelWidth);

		//Get Times
		if(simp.getNFrames()>1) {
			times=Time_Extractor.extractTimes(simp, false, Time_Extractor.SubTime.EVENT_SET);
		}else times=new double[] {0};

		//Get CCR2 frames
		ccr2frames=getCCR2Frames(simp);

		//Create Target Image
		if(timp==null) {
			timp=createTarget(simp, fullZ);
		}else fullZ=(simp.getNSlices()>1 && timp.getNSlices()==simp.getNSlices());
		if(timp==simp){IJ.log("Same window exiting"); done=true; return false;}
		if(timp==null){IJ.log("No target window"); done=true; return false;}
		
		//Get path for saving target image and data and rois
		FileInfo fi = simp.getOriginalFileInfo();
		if (fi!=null && fi.directory!=null) spath= fi.directory;
		if(spath.contentEquals("")) {IJ.error("Please save source image");return false;}

		if(ajtctcpimp==null){
			java.io.File[] files = new java.io.File(spath).listFiles();
			if(files!=null) {
				for(java.io.File f : files) {
					if(f.getName().endsWith("-AJTCTcp.tif")) {
						YesNoCancelDialog ync=new YesNoCancelDialog(null, "AJTCTcp Found", "Use "+f.getName()+" image as AJTCTcp?");
						if(ync.cancelPressed()) break;
						if(ync.yesPressed()) {
							ajtctcpimp=IJ.openImage(f.getAbsolutePath());
							ajtctcpimp.show();
							break;
						}
					}
				}
			}
		}

		//Set up Listeners and Window focus and overlay
		ImageWindow siw=simp.getWindow();
		WindowManager.setWindow(timp.getWindow());
		WindowManager.setWindow(siw);
		preStripListeners();
		ImageCanvas ic = simp.getCanvas();
		ic.disablePopupMenu(true);
		ic.addMouseListener(this);
		ic.removeKeyListener(IJ.getInstance());
		ic.addKeyListener(this);

		siw.removeMouseWheelListener(siw);
		siw.addMouseWheelListener(this);
		simp.setPosition(simp.getNChannels()==3?2:1,simp.getZ(),simp.getT());
		soverlay=new Overlay();
		simp.setOverlay(soverlay);
		
		//IJ defaults
		IJ.setForegroundColor(255,255,255);
		IJ.setBackgroundColor(0,0,0);
		IJ.setTool(ij.gui.Toolbar.FREEROI);
		for(String roifilename : new String[] {"axon.roi","duraBV.roi","piaBV.roi", "bv.roi", "bv-dura.roi", "bv-pia.roi", "dura-bv.roi", "pia-bv.roi"}) {
			java.io.File roifile=new java.io.File(spath+roifilename);
			if(roifile.exists()){
				RoiManager rm=RoiManager.getRoiManager();
				RoiDecoder rd = new RoiDecoder(spath+roifilename);
				Roi roi=null;
				try {
					roi = rd.getRoi();
				} catch (IOException e) {
					e.printStackTrace();
				}
				if(roi!=null) rm.addRoi(roi);
				if(roifilename.contentEquals("axon.roi"))axonRoi=roi;
				else if(roifilename.contentEquals("bv.roi"))duraBVRoi=roi;
				else if(roifilename.contentEquals("duraBV.roi"))duraBVRoi=roi;
				else if(roifilename.contentEquals("piaBV.roi"))piaBVRoi=roi;
				else if(roifilename.contentEquals("bv-dura.roi"))duraBVRoi=roi;
				else if(roifilename.contentEquals("bv-pia.roi"))piaBVRoi=roi;
				else if(roifilename.contentEquals("dura-bv.roi"))duraBVRoi=roi;
				else if(roifilename.contentEquals("piaB-bv.roi"))piaBVRoi=roi;
			}
		}
		return true;
	}

	private void preStripListeners(){
		if(simp==null)return;
		ImageCanvas ic=simp.getCanvas();
		ImageWindow siw=simp.getWindow();
		KeyListener[] kls=ic.getKeyListeners();
		for(int i=0;i<kls.length;i++) {
			if(kls[i].getClass().getName().startsWith("Thresh_Cell")){
				ic.removeKeyListener(kls[i]);
				IJ.log("Removed old keyL"+kls[i]);
			}
		}
		MouseListener[] mls=ic.getMouseListeners();
		for(int i=0;i<mls.length;i++) {
			if(mls[i].getClass().getName().startsWith("Thresh_Cell")){
				ic.removeMouseListener(mls[i]);
				IJ.log("Removed old mL"+mls[i]);
			}
		}
		MouseWheelListener[] mwls=siw.getMouseWheelListeners();
		boolean hasit=false;
		for(int i=0;i<mwls.length;i++) {
			if(mwls[i].getClass().getName().startsWith("Thresh_Cell")){
				siw.removeMouseWheelListener(mwls[i]);
				IJ.log("Removed old mwL"+mwls[i]);
			}
			if(mwls[i]==siw)hasit=true;
		}
		if(!hasit) {
			IJ.log("Replacing ImageWindow MouseWheel Listener");
			siw.addMouseWheelListener(siw);
		}
		if(ajtctcpimp!=null && ajtctcpimp.isVisible()) {
			ImageCanvas ajc=ajtctcpimp.getCanvas();
			MouseListener[] ajmls=ajc.getMouseListeners();
			for(int i=0;i<ajmls.length;i++) {
				if(ajmls[i].getClass().getName().startsWith("Thresh_Cell")){
					ajc.removeMouseListener(ajmls[i]);
					IJ.log("Removed old AJTCTcp mL"+ajmls[i]);
				}
			}
			MouseMotionListener[] ajmmls=ajc.getMouseMotionListeners();
			for(int i=0;i<ajmmls.length;i++) {
				if(ajmmls[i].getClass().getName().startsWith("Thresh_Cell")){
					ajc.removeMouseMotionListener(ajmmls[i]);
					IJ.log("Removed old AJTCTcp mmL"+ajmmls[i]);
				}
			}
		}
	}

	private void setupAJTCTcp(){
		if(ajtctcpimp!=null && ajtctcpimp.isVisible()) {
			ImageCanvas ajc=ajtctcpimp.getCanvas();
			ajc.disablePopupMenu(true);
			MouseAdapter ma=new MouseAdapter() {
				Roi temproi=null;
				Boolean accepted=false;
				int curCell=-1;

				public void mouseReleased(MouseEvent e) {
					if(ajtctcpimp==null || !ajtctcpimp.isVisible())return;
					if(e.getButton()==MouseEvent.BUTTON1) {
						if(ajtctcpimp.getT() != simp.getT()) return;
						if(accepted){
							int x=ajc.offScreenX(e.getX()), y=ajc.offScreenY(e.getY());
							ImageProcessor ipc=ajtctcpimp.getProcessor();
							int cell=ipc.getPixel(x, y);
							if(DEBUG) log("Accepted cell "+cell+" from AJTCTcp");
							if(cell>0 && cell!=curCell){
								Roi roi=doWandAt(ipc, x, y);
								curWandSetRoi(roi);
								curCell=cell;
							}else{
								accepted=false;
								return;
							}
						}
						accepted=true;
					}else if(e.getButton()==MouseEvent.BUTTON3) {
						if(ajtctcpimpAllroi!=null){
							if(soverlay!=null)soverlay.remove(ajtctcpimpAllroi);
							ajtctcpimpAllroi=null;
						}else{
							ImageProcessor ipc=ajtctcpimp.getProcessor();
							ajtctcpimpAllroi=getAllRois(ipc);
							ajtctcpimpAllroi.setStrokeColor(Color.RED);
							if(soverlay!=null)soverlay.add(ajtctcpimpAllroi);
						}
						simp.updateAndDraw();
					}
				}
				public void mouseEntered(MouseEvent e) {
					if(ajtctcpimp==null || !ajtctcpimp.isVisible() || (ajtctcpimp.getT() != simp.getT()) )return;
					accepted=false;
					if(curWand!=null &&curWand[simp.getFrame()-1]!=null && curWand[simp.getFrame()-1].roi!=null)
						temproi=curWand[simp.getFrame()-1].getRoi();
					else temproi=simp.getRoi();
				}
				public void mouseExited(MouseEvent e) {
					if(ajtctcpimp==null || !ajtctcpimp.isVisible() || (ajtctcpimp.getT() != simp.getT()) || accepted) {
						return;
					}
					if(temproi!=null) {
						curWandSetRoi(temproi);
					}else{
						simp.resetRoi();
						timp.resetRoi();
					}
				}
				@Override
				public void mouseMoved(MouseEvent e){
					if(ajtctcpimp==null || !ajtctcpimp.isVisible() || (ajtctcpimp.getT() != simp.getT()) || accepted) return;
					int x=ajc.offScreenX(e.getX()), y=ajc.offScreenY(e.getY());
					ImageProcessor ipc=ajtctcpimp.getProcessor();
					int cell=ipc.getPixel(x, y);
					if(cell>0 && cell!=curCell){
						Roi roi=doWandAt(ipc, x, y);
						curWandSetRoi(roi);
						curCell=cell;
					}
				}
				private void curWandSetRoi(Roi roi){
					if(roi!=null) {
						int fr=simp.getFrame()-1;
						if(curWand[fr]==null)curWand[fr]=new AutoWandRoi(roi, 0.0, simp.getC(), simp.getZ(), simp.getFrame());
						else curWand[fr].setRoi(roi);
						curWand[fr].showBoth();
						if(DEBUG) log("Set curWand["+fr+"] to "+roi);
					}
				}
				private Roi doWandAt(ImageProcessor ipc, int x, int y) {
					Wand w=new Wand(ipc);
					w.autoOutline(x, y);
					if(w.npoints>2) {
						return new PolygonRoi(w.xpoints, w.ypoints, w.npoints, Roi.TRACED_ROI);
					}
					return null;
				}
			};
			ajc.addMouseListener(ma);
			ajc.addMouseMotionListener(ma);
		}
	}

	public void TCT(ImagePlus imp) {
		if (imp==null) {IJ.log("noImage"); return;}
		ImagePlus[] imps=getSourceAndTarget();
		if(imps==null)return;
		simp=imps[0]; timp=imps[1]; ajtctcpimp=imps[2];
		if(!setup())return;

		int chs=simp.getNChannels();

		GenericDialog tctgd=new GenericDialog("AJTCT");
		tctgd.addMessage("AJTCT ver "+version);
		tctgd.addMessage("Working on: "+title);
		tctgd.addNumericField("Green Ch:", greench<=chs?greench:1);
		tctgd.addNumericField("Red Ch:", redch<=chs?redch:(chs>=2?2:1));
		tctgd.addCheckbox("Adjust LUTs?", true);
		tctgd.showDialog();
		if(tctgd.wasCanceled())return;
		greench=(int)tctgd.getNextNumber();
		redch=(int)tctgd.getNextNumber();
		greench=Math.max(1, Math.min(greench, chs));
		redch=Math.max(1, Math.min(redch, chs));
		if(tctgd.getNextBoolean()){
			if(simp.isComposite()){
				LUT[] luts=((CompositeImage)simp).getLuts();
				LUT green=new LUT(ij.plugin.LutLoader.getLut("green"),luts[greench-1].min,luts[greench-1].max);
				LUT red=new LUT(ij.plugin.LutLoader.getLut("red"),luts[redch-1].min,luts[redch-1].max);
				((CompositeImage)simp).setChannelLut(green, greench);
				((CompositeImage)simp).setChannelLut(red, redch);
				simp.updateAndDraw();
			}
		}
		tctgd=null;

		//Start Results Window
		results=new TctTextWindow(basetitle);
		if(results.rtw==null){IJ.error("Failed to make Results Window"); return;}
		if("Unlabeled".equals(cellLabel)) askCellLabel();
		else timp.setProperty("Info", results.getText(true));

		//Start TCT Panel
		tctpanel=new TCTPanel();
		tctpanel.addWindowListener(new WindowAdapter(){  
			public void windowClosing(WindowEvent e) {  
				done=true;
				tctpanel.dispose();  
			}  
		});  
		tctpanel.setVisible(true);

		//Initialize loop variables
		int sl=simp.getSlice(), fr=simp.getFrame();
		int frms=simp.getNFrames();
		int prevsl=sl, prevfr=fr;
		int prevcelln=-1;
		curX=-1; curY=-1;
		int xprev=curX, yprev=curY;
		String frmsleft="";
		boolean ignoreCellComplete=false;

		Overlay tov1=timp.getOverlay();
		boolean importFromOverlay=false;
		if(tov1!=null && tov1.size()>0) {
			YesNoCancelDialog ync=new YesNoCancelDialog(null, "AJTCT-Overlay-Import", "Import from AJTCT Overlay?");
			if(ync.yesPressed()) importFromOverlay=true;
		}
		double defaultThresh=0;
		if(simp.getProcessor().getMinThreshold()>0)defaultThresh=simp.getProcessor().getMinThreshold();

		setupAJTCTcp();
		int importFromAllRoisLine=0;

		//-------loop-------------
		//main loop
		while(!done){
			//reset if new cell
			if(celln!=prevcelln || editRoi || importingFromAllRois) {
				frmsleft=""; for(int i=0;i<frms;i++) frmsleft+=(i+1)+" ";
				tctpanel.setTextLine(TctLines.FRAMESLEFT, "Frames left: "+frmsleft);
				xys.clear();
				pointIndex=0;
				tctpanel.resetWP();

				for(int i=0;i<MAX_POINTS;i++) {
					postFirstAccept[i]=false;
				}
				
				curX=-1; curY=-1;
				curWand=new AutoWandRoi[frms];
				tctpanel.setTextLine(TctLines.CURRENTCELL, "Working on "+cellLabel+" Cell: "+celln);
				xys.add(new WandPoint(null, 0, false));

				//import from overlay of a previously saved AJTCT image
				if(importFromOverlay) {
					timp.setOverlay(new Overlay());
					for(int i=0;i<tov1.size();i++) {
						Roi tovroi=tov1.get(i);
						if(tovroi!=null) {
							int frtov=tovroi.getTPosition();
							curWand[frtov]=new AutoWandRoi(tovroi,0.0, simp.getC(), simp.getZ(), frtov);
							curWand[frtov].accept();
						}
					}
					importFromOverlay=false;
				}
				
				//if importing from all rois, set up the curWand array
				if(importingFromAllRois){
					if(allRois!=null && allRois.size()>0){
						Roi curRoi=allRois.get(0);
						allRois.remove(0);
						simp.setZ(getDuraCellZ(simp, curRoi));
						int min=65535, roiNum=0;
						Point[] pts=curRoi.getContainedPoints();
						ImageProcessor ip=simp.getProcessor();
						ImageProcessor tip=timp.getStack().getProcessor(timp.getStackIndex(1, simp.getZ(), simp.getFrame()));
						for(Point p : pts){
							int val=ip.get(p.x, p.y);
							if(val>0 && val<min)min=val;
							if(roiNum==0){
								int tval=tip.get(p.x, p.y);
								if(tval!=0){
									roiNum=tval;
								}
							}
						}
						if(ajtctcpimp!=null && ajtctcpimp.isVisible()){
							String addString="ImportFromAllRois last roi: "+roiNum;
							String info=ajtctcpimp.getInfoProperty();
							if(info.endsWith("\n"))info=info.substring(0, info.length()-1);
							if(!info.contains("ImportFromAllRois")){
								info+="\n"+addString;
							}else{
								String[] infon=info.split("\n");
								if(importFromAllRoisLine==0){
									for (int i=0;i<infon.length-1;i++){
										if(infon[i].contains("ImportFromAllRois")){
											importFromAllRoisLine=i;
											break;
										}
									}
								}
								infon[importFromAllRoisLine]=addString;
								info=String.join("\n", infon);
							}
							ajtctcpimp.setProperty("Info", info);
							IJ.saveAsTiff(ajtctcpimp, ajtctcpimp.getOriginalFileInfo().directory+ajtctcpimp.getTitle());
						}
						Rectangle r=curRoi.getBounds();
						curX=r.x+r.width/2; curY=r.y+r.height/2;
						xys.clear();
						Point p=new Point(curX, curY);
						xys.add(new WandPoint(p, min, false));
						defaultThresh=min;
						curWand[0]=new AutoWandRoi(curRoi, (double)min, p, simp.getC(), simp.getZ(), 1);
						curWand[0].showBoth();
						setSrcRectAtPoint(p);
					}else{
						importingFromAllRois=false;
						IJ.showMessage("Importing ROIs complete");
					}
				}
				
				//start edit if editRoi
				if(editRoi)editRoi();
				if(reconfirming) editRoi(celln);
				if(editing){
					if(curWand[0]!=null && curWand[0].wandPoints!=null && curWand[0].wandPoints.size()>0) {
						curX=curWand[0].wandPoints.get(0).getX();
						curY=curWand[0].wandPoints.get(0).getY();
						xys.clear();
						xys.add(new WandPoint(curWand[0].wandPoints.get(0).point, curWand[0].wandPoints.get(0).thresh, false));
						defaultThresh=curWand[0].wandPoints.get(0).thresh;
					}
					for(int i=1;i<frms;i++) {
						if(curWand[i]!=null)curWand[i].accept();
					}
					if(!reconfirming)ignoreCellComplete=true;
				}

				//lastfr=-1;
				xprev=curX; yprev=curY;
				prevsl=simp.getSlice(); prevfr=simp.getFrame();
				prevcelln=celln;
				soverlay.clear();
				if(showAllRois){
					showAllRois();
				}else if(showAllRoi!=null){
					soverlay.remove(showAllRoi);
					showAllRoi=null;
				}
				simp.setOverlay(soverlay);
				simp.getProcessor().setThreshold(defaultThresh, (double) 65535, MYLUT);
				simp.updateAndDraw();
				threshChanged.set(false);
			}
			acceptedROI.set(false);

			//loop in here for each frame of the cell until accepted
			while(!acceptedROI.get() && !gocellcomplete) {
				fr=simp.getFrame(); sl=simp.getSlice();
				int ch=simp.getChannel();
				if(fullZ && timp.getZ()!=sl) timp.setPosition(1, sl, fr);
				else if(timp.getCurrentSlice()!=timp.getStackIndex(1, labelsl, fr)) timp.setPosition(1,labelsl,fr);
				if(ajtctcpimp!=null && ajtctcpimp.isVisible() && ajtctcpimp.getT()!=fr){
					ajtctcpimp.setPosition(1,1,fr);
				}
				if(sl!=prevsl || fr!=prevfr) {
					//tctpanel.setTextLine(5, "Sl"+sl+" psl"+prevsl+" fr"+fr+" pfr"+prevfr);
					if(curWand[fr-1]!=null && curWand[fr-1].roi!=null) {
						if(fr!=prevfr){
							if(checkZmax) {
								if(curWand[fr-1].sl!=sl){
									sl=curWand[fr-1].sl;
									simp.setZ(sl);
									tctpanel.setTextLine(TctLines.EXTRA, "Switched to new found Zmax: "+curWand[fr-1].sl);
								} else {
									tctpanel.setTextLine(TctLines.EXTRA, "");
								}
							}
						}
						if(curWand[fr-1].sl!=sl) {
							if(checkZmax){
								if(DEBUG)IJ.log("Updating wand "+fr+" to new slice: "+sl);
								if(curWand[fr-1].accepted){
									curWand[fr-1].setSliceChannel(sl, ch);
									curWand[fr-1].updateWandRoi();
								}else{
									updateCurWands(false, false, true);
								}
							}else{
								curWand[fr-1].setSliceChannel(sl, ch);
								curWand[fr-1].updateWandRoi();
							}
						}
						if(curWand[fr-1]!=null) curWand[fr-1].showBoth();
					}
					//justfirst=true;
				}
				if(curWand[fr-1]!=null && curWand[fr-1].ch!=ch) {
					curWand[fr-1].setSliceChannel(sl, ch);
					curWand[fr-1].showBoth();
				}
				prevsl=sl; prevfr=fr;
				IJ.wait(10);
				
				if(showAllRois && showAllRoi==null){
					showAllRois();
				}
				if(!showAllRois && showAllRoi!=null){
					IJ.log("Remove showAllRoi");
					soverlay.remove(showAllRoi);
					showAllRoi=null;
				}

				if(syncWindows){
					Rectangle sSrcRect=simp.getCanvas().getSrcRect(), tsrcRect=timp.getCanvas().getSrcRect();
					double sMag=simp.getCanvas().getMagnification(), tMag=timp.getCanvas().getMagnification();
					if(!sSrcRect.equals(tsrcRect) || !(Math.round(sMag*100.0)==Math.round(tMag*100.0))) {
						timp.getCanvas().setMagnification(Math.round(sMag*100.0)/100.0);
						timp.getCanvas().setSourceRect(sSrcRect);
						timp.updateAndDraw();
					}
					if(ajtctcpimp!=null && ajtctcpimp.isVisible()){
						Rectangle csrcRect=ajtctcpimp.getCanvas().getSrcRect();
						double cMag=ajtctcpimp.getCanvas().getMagnification();
						if(!sSrcRect.equals(csrcRect) || !(Math.round(sMag*100.0)==Math.round(cMag*100.0))) {
							ajtctcpimp.getCanvas().setMagnification(Math.round(sMag*100.0)/100.0);
							ajtctcpimp.getCanvas().setSourceRect(sSrcRect);
							ajtctcpimp.updateAndDraw();
						}
					}
				}

				if(addDirectRoi)addDirectRoi();

				//if edit, stop this loop (by accepting the roi)
				if(editRoi) {
					curWand[fr-1]=null;
					break;
				}

				//recalculate Axons and BV distances
				if(recalcABV.get()>0) {
					log("Recalculating Axon and BV distances on thread...");
					results.addPostData(2|4, recalcABV.get()==2);
					recalcABV.set(0);
				}

				//if threshold was changed (like by mousewheel), update
				if(threshChanged.get()) {
					if(curWand[fr-1]==null)curWand[fr-1]=new AutoWandRoi(ch,sl,fr);
					else curWand[fr-1].setThresh(pointIndex,xys.get(pointIndex).thresh);
					curWand[fr-1].showBoth();
					updateCurWands(true, false, true);
					threshChanged.set(false);
				}

				//on new click
				if(((curX!=xprev||curY!=yprev)) && curX!=-1 && !(altWasDown.get() || shiftWasDown.get())){
					xprev=curX;yprev=curY;
					Point c=new Point(curX,curY);
					if(pointIndex>=xys.size()) {
						xys.add(new WandPoint(c, defaultThresh==0?simp.getProcessor().get(curX, curY)/2.0:defaultThresh, false));
					}else {
						xys.get(pointIndex).setPoint(c);
						if(xys.get(pointIndex).thresh==0)xys.get(pointIndex).setThresh(simp.getProcessor().get(curX, curY)/2.0);
						else xys.get(pointIndex).setThresh(xys.get(pointIndex).thresh);
					}
					if(curWand[fr-1]==null)curWand[fr-1]=new AutoWandRoi(ch,sl,fr);
					else {
						curWand[fr-1].updatePoint();
					}
					curWand[fr-1].showBoth();
					if(!postFirstAccept[pointIndex])updateCurWands(false, false, true);
					else updateCurWands(false, true, true);
					tctpanel.setTextLine(TctLines.CURRENTPOINT, "Point:"+(pointIndex+1)+" x:"+curX+" y:"+curY+" z:"+sl+" fr:"+fr);
				}
				if(WindowManager.getWindow(title)==null || WindowManager.getWindow(endtitle)==null) {IJ.log("Window Closed"); done=true;}
				if(done) {cleanup(); return;}
				if(((spacepress.get()) || buttonpress[2]))IJ.wait(10);
				//if(DEBUG)IJ.log("\\Update:"+System.nanoTime()/1000000+"  xy:"+xy.x+" "+xy.y+" aa"+autoAccept+" probable"+(curWand[fr-1]==null?"NA":""+curWand[fr-1].probable)+" rc:"+buttonpress[2]+" gb:"+goback);
				if(curX!=-1 && curY!=-1 && autoAccept && curWand[fr-1].probable && ((spacepress.get() && !buttonpress[0]) || buttonpress[2])){acceptedROI.set(true);}
				//if((auto||!firsthit) && x!=-1 && y!=-1 && !firsttime)acceptedROI=true; else IJ.wait(200);
				
				//go to closest zmax if z key is pressed
				if(goToZmax.get()) {
					goToZmax.set(false);
					if(simp.getRoi()!=null) {
						int zmax=getClosestMaxZ(simp, simp.getRoi());
						if(zmax>0 && zmax!=sl) {
							simp.setPosition(simp.getChannel(), zmax, simp.getFrame());
						}
					}
				}

				if(acceptedROI.get() && checkZmax && simp.getRoi()!=null && curWand[fr-1]!=null && !curWand[fr-1].wasClose) {
					if(checkZmax && !curWand[fr-1].wasClose) {
						int zmax=getClosestMaxZ(simp, simp.getRoi());
						if(zmax>0 && zmax!=sl && Math.abs(zmax-sl)<=5) {
							YesNoCancelDialog ync=new YesNoCancelDialog(null, "Max Z-plane Check", "A close z-maximum was found: "+zmax+". Go to this z?");
							if(ync.cancelPressed()) {
								acceptedROI.set(false);
							}else if(ync.yesPressed()) {
								acceptedROI.set(false);
								simp.setPosition(simp.getChannel(), zmax, simp.getFrame());
							}
						}
					}
					if(!postFirstAccept[0] && curWand[fr-1].roi!=null) {
						ImageProcessor tip=timp.getStack().getProcessor(timp.getStackIndex(1,labelsl,fr));
						Point[] pts=curWand[fr-1].roi.getContainedPoints();
						int max=0;
						for(Point p : pts) {
							int val=tip.get(p.x, p.y);
							if(val>max)max=val;
						}
						if(max>0) {
							YesNoCancelDialog ync=new YesNoCancelDialog(null, "Overlap Check", "Warning-- Current selection overlaps with another cell (Cell "+max+"), continue?");
							if(!ync.yesPressed()) {
								acceptedROI.set(false);
							}
						}
					}
				}
			} //end loop per frame/accepted

			if(updatedLabel) {prevcelln=celln; updatedLabel=false;}

			if(curWand[fr-1]==null || simp.getRoi()==null) {
				IJ.log("No selection");
				if(importingFromAllRois){
					if(buttonpress[1]){
						prevcelln--;
					} else {
						YesNoCancelDialog ync=new YesNoCancelDialog(null, "Importing from AllRois", "No selection found. Continue to next ROI?");
						if(ync.yesPressed()) prevcelln--;
					}
				}
			}else if(!gocellcomplete){
				//curWand[fr-1].setRoi(simp.getRoi());
				curWand[fr-1].accept();
				postFirstAccept[pointIndex]=true;
				//updateCurWands(false, fr, curWand[fr-1]);
			}

			long startpresstime=System.nanoTime();
			long waittime=200;
			while(spacepress.get() && !gocellcomplete) {
				IJ.wait(20);
				long presstime=System.nanoTime()-startpresstime;
				if((presstime/1000000)>waittime){break;}
			}

			boolean cellcomplete=true;
			frmsleft="";
			int lowsl=1;
			for(int i=frms;i>0;i--) {
				if(curWand[i-1]==null || !curWand[i-1].accepted) {cellcomplete=false; frmsleft=(i)+" "+frmsleft; lowsl=i;}
				else frmsleft=((i>9)?"   ":"  ")+frmsleft;
				//else frmsleft=" ̸"+(i)+((frmsleft.startsWith(" ")||frmsleft.startsWith(" ̸"))?"":" ")+frmsleft;
			}
			if(DEBUG){if(cellcomplete)log("Cell complete icc="+ignoreCellComplete+" gocc="+gocellcomplete);}
			tctpanel.setTextLine(TctLines.FRAMESLEFT, "Frames left: "+frmsleft);
			if((cellcomplete && !ignoreCellComplete) || gocellcomplete){
				gocellcomplete=false;
				ignoreCellComplete=false;
				boolean oktogo=true;
				if( (!editing || !(reconfirming && skipconfirmation)) && (frms>1)){
					GenericDialog gd = new GenericDialog("Cell Complete");
					String message="All done with cell #"+celln+"?";
					if(!cellcomplete)message=message+"\nWarning: Some frames have no data!!";
					gd.addMessage(message);
					gd.enableYesNoCancel("Yes", "No");
					gd.showDialog();
					oktogo=gd.wasOKed();
					if(!oktogo)ignoreCellComplete=true;
				}
				spacepress.set(false); buttonpress[0]=false; buttonpress[2]=false; buttonpress[1]=false;
				if (oktogo){
					timp.setColor(Color.white);
					for(int i=0;i<frms;i++) {
						HashMap<HEADINGS,Object> output=null;
						if(curWand[i]!=null && curWand[i].accepted) {
							curWand[i].draw();
							output=curWand[i].output;
						}else{
							output=new HashMap<HEADINGS,Object>();
							output.put(HEADINGS.LABEL, cellLabel);
							output.put(HEADINGS.ROI, 0);
							output.put(HEADINGS.CELL, celln);
							output.put(HEADINGS.FRAME, i+1);
							output.put(HEADINGS.THRESH, xys.get(0).thresh);
							output.put(HEADINGS.TIME, times[i]);
						}
						if(editing || !cellLabel.contentEquals(cellLabels.get(cellLabels.size()-1))) results.insertLine(output);
						else {results.appendLine(output);}
					}
					results.updateDisplay();
					timp.setProperty("Info", results.getText(true));
					timp.setPosition(1,labelsl,fr);
					Overlay tov=timp.getOverlay();
					if(tov!=null)tov.clear();
					if(celln>255 && !editing && timp.getBitDepth()==16) {
						timp.resetDisplayRange();
					}
					timp.updateAndRepaintWindow();
					simp.deleteRoi();
					timp.deleteRoi();
					if(plotWindow!=null) {
						if(plotWindow.isClosed()) {plotWindow=null;}
						else {
							showPlot();
						}
					}
					if(autoSave) {
						boolean dosave=true;
						if(cellLabel.equals("Dura") && celln==1 && !editing) {
							if((new java.io.File(spath+timp.getTitle())).exists()) {
								YesNoCancelDialog ync=new YesNoCancelDialog(null,"File Exists","AJTCT file already exists, ok to overwrite?\n(Otherwise autosave will be turned off)");
								if(!ync.yesPressed()) {
									autoSave=false;
									tctpanel.autosavecbmi.setState(autoSave);
									dosave=false;
								}
							}
						}
						if(dosave) {
							IJ.save(timp, spath+timp.getTitle());
							results.save();
							IJ.showStatus("TCT saved at "+spath+timp.getTitle());
						}
					}
					if(editing & !reconfirming)celln=results.getLastCellForLabel(cellLabel)+1;
					else celln++;
					editing=false;
					if(reconfirming) {
						if(celln>results.getLastCellForLabel(cellLabel)){
							editRoi=false;
							reconfirming=false;
							skipconfirmation=false;
							IJ.showMessage("Finished reconfirming all cells for label: "+cellLabel);
						}
					}
					fr=0;
				}
			}
			if(fr<frms) fr++; else fr=lowsl;
			results.roin++;
			simp.setT(fr);
		} // end main loop
		cleanup();
	}

	private String buildLine(HashMap<HEADINGS, Object> data) {
		String[] line=new String[HEADINGS.length()];
		for(int i=0;i<line.length;i++) {
			line[i]="NA";
			if(i==HEADINGS.CCR2FRAME.getIndex())line[i]=""+false;
		}
		for(int i=0;i<line.length;i++) {
			if(data.containsKey(HEADINGS.get(i))){
				Object val=data.get(HEADINGS.get(i));
				if(val instanceof Double)
					line[i]=IJ.d2s((Double)val,4);
				else 
					line[i]=val.toString();
			}
		}
		return String.join("\t", line);
	}
	
	private HashMap<HEADINGS, Object> fillLipoData(int slice, int frame, Roi roi, HashMap<HEADINGS, Object> data) {
		if(roi!=null){
			ImageProcessor gip=simp.getStack().getProcessor(simp.getStackIndex(greench, slice, frame));
			ImageProcessor rip=simp.getStack().getProcessor(simp.getStackIndex(redch, slice, frame));
			gip.setRoi(roi);
			Point[] cpoints=roi.getContainedPoints();
			ArrayList<Double> rats=new ArrayList<Double>();
			if(cpoints!=null) {
				double rave=0, ratave=0;
				int gpix=0, rpix=0, n=0;
				for(Point p : cpoints) {
					int gvalue=gip.get(p.x,p.y), rvalue=rip.get(p.x,p.y);
					rave+=(double)rvalue;
					if(gvalue>AVERATIO_GREEN_MIN){
						rats.add(((double)rvalue/(double)gvalue));
						ratave+=rats.get(n);
						n++;
					}
					if(((double)rvalue/(double)gvalue)>LIPO_RATIO)rpix++;
					else gpix++;
				}
				double ratmed=0;
				if(n==0)n=1;
				else{
					rats.sort(null);
					int mid = n / 2;
					if (n % 2 != 0) {
						ratmed = rats.get(mid);
					} else {
						ratmed = (rats.get(mid - 1) + rats.get(mid)) / 2.0;
					}
				}
				if(data==null)data=new HashMap<HEADINGS, Object>();
				data.put(HEADINGS.REDMEAN, (rave/(double)cpoints.length));
				data.put(HEADINGS.GREENPIXELS, gpix);
				data.put(HEADINGS.REDPIXELS, rpix);
				data.put(HEADINGS.AVERATIO, (ratave/(double)n));
				data.put(HEADINGS.MEDRATIO, ratmed);
			}
		}
		return data;
	}

	private HashMap<HEADINGS, Object> fillRoiData(Roi compareRoi, Roi roi, HashMap<HEADINGS, Object> data, HEADINGS heading) {
		Double roidist=Double.NaN;
		if(roi!=null){
			if(compareRoi!=null) {
				Double ad=AJ_Misc_Plugins.distanceFromRoiToRoi(compareRoi, roi);
				if(ad!=null)roidist=ad*simp.getCalibration().pixelWidth;
			}
		}
		data.put(heading, roidist);
		return data;
	}

	private void addCellLabel(String newlabel){
		if(!cellLabels.contains(newlabel)){
			cellLabels.add(newlabel);
			celln=1;
			updatedLabel=true;
		}
		labelsl=cellLabels.indexOf(newlabel)+1;
		int chs = timp.getNChannels();
		int sls = timp.getNSlices();
		int frms = timp.getNFrames();
		while(labelsl>timp.getNSlices()){
			WindowManager.setCurrentWindow(timp.getWindow());
			timp.setZ(timp.getNSlices());
			//IJ.run("Add Slice", "add=slice");IJ.wait(200);
			//add slice has some sort of error where it rearranges slices, so I'll add it:
			ImageStack stack=timp.getStack();
			for (int t=frms; t>=1; t--) {
				int index = (t)*chs*sls;
				for (int i=0; i<chs; i++) {
					ImageProcessor ip = stack.getProcessor(1).duplicate();
					ip.setColor(0); ip.fill();
					stack.addSlice(newlabel, ip, index);
				}
			}
			sls++;
			timp.setStack(stack, chs, sls, frms);
		}
		if(labelsl==1) {
			for(int fr=0;fr<timp.getNFrames();fr++)timp.getStack().setSliceLabel(newlabel, fr*chs*sls+1);
		}
		//timp.setPosition(1,labelsl,1);
		//timp.getStack().setSliceLabel(newlabel,timp.getCurrentSlice());
		if(LUT_GLASBEY_INV!=null)((CompositeImage)timp).setChannelLut(LUT_GLASBEY_INV, 1);
		timp.updateAndDraw();
		cellLabel=newlabel;
	}

	private void askCellLabel(){
		askCellLabel("");
	}
	
	private void askCellLabel(String def) {
		GenericDialog gd = new GenericDialog("Add new cell label");
		ArrayList<String> askLabels=new ArrayList<String>(cellLabels);
		if(!askLabels.contains("Dura"))askLabels.add(0, "Dura");
		if(!askLabels.contains("Pia"))askLabels.add(1, "Pia");
		if(def==null || "".equals(def)) def=cellLabels.isEmpty()?askLabels.get(0):cellLabels.get(cellLabels.size()-1);
		if(!cellLabels.isEmpty() && def.equals("Dura"))def="Pia";
		gd.addChoice("Labels: ",askLabels.toArray(new String[askLabels.size()]),def);
		gd.addStringField("Or new label: ", "");
		gd.addCheckbox("Draw Label on AJTCT stack?", drawCellLabel);
		gd.showDialog();
		if (gd.wasCanceled()) {
			if(cellLabel!=null && cellLabels.isEmpty())setCellLabel(cellLabel);
			return;
		}
		cellLabel=gd.getNextChoice();
		String temp=gd.getNextString();
		if(!temp.equals(""))cellLabel=temp;
		setCellLabel(cellLabel);
		drawCellLabel=gd.getNextBoolean();
	}
	
	private void setCellLabel(String label) {
		if(cellLabels.contains(label)) {
			cellLabel=label;
			labelsl=cellLabels.indexOf(cellLabel)+1;
			celln=results.getLastCellForLabel(cellLabel)+1;
			updatedLabel=true;
		}else {
			addCellLabel(label);
		}
		if(tctpanel!=null)tctpanel.whichlabel.select(cellLabels.indexOf(label));
	}

	private void cleanup(){
		//if(autoSave &&  celln>1 && timp!=null && timp.isVisible())IJ.save(timp, spath+timp.getTitle());
		tctpanel.dispose();
		if(simp!=null){
			if(soverlay!=null) soverlay.clear();
			soverlay=simp.getOverlay();
			if(soverlay!=null)soverlay.clear();
			simp.updateAndDraw();
			ImageCanvas ic=simp.getCanvas();
			if(ic!=null){
				ImageWindow siw=simp.getWindow();
				ic.removeKeyListener(this);
				ic.removeMouseListener(this);
				siw.removeMouseWheelListener(this);
				siw.addMouseWheelListener(siw);
				ic.disablePopupMenu(false);
				ic.addKeyListener(IJ.getInstance());
			}
		}
		if(ajtctcpimp!=null && ajtctcpimp.isVisible()){
			ImageCanvas ajc=ajtctcpimp.getCanvas();
			MouseListener[] mouseListeners = ajc.getMouseListeners();
			MouseMotionListener[] mouseMotionListeners = ajc.getMouseMotionListeners();
			ajc.removeMouseListener(mouseListeners[mouseListeners.length-1]);
			ajc.removeMouseMotionListener(mouseMotionListeners[mouseMotionListeners.length-1]);
		}
		IJ.log("Thresh Cell Tranfer complete");
	}
	
	private void genAutoThresh() {
		YesNoCancelDialog yncd=new YesNoCancelDialog(null,"AutoThreshold","Generate new ThreshMultipliers?","Yes", "No (delete existing)");
		if(yncd.cancelPressed())return;
		threshMultiplier=null;
		if(!yncd.yesPressed())return;
		threshMultiplier=new double[simp.getNFrames()];
		double[] allthreshs=results.getColumn("Thresh");
		double[] frames=results.getColumn("Frame");
		String[] vars=results.getColumnAsStrings("Label");
		double curthresh=xys.get(0).thresh;
		int n=0;
		for(int i=0;i<vars.length;i++) {
			if(!cellLabel.contentEquals(vars[i]))continue;
			if(frames[i]==1.0) {
				n++;
			}
		}
		double[][] threshs=new double[n][simp.getNFrames()];
		n=-1;
		for(int i=0;i<vars.length;i++) {
			if(!cellLabel.contentEquals(vars[i]))continue;
			if(frames[i]==1f) {
				threshs[++n]=new double[simp.getNFrames()];
				threshs[n][0]=1f; curthresh=allthreshs[i];
			}else {
				threshs[n][(int)frames[i]-1]=allthreshs[i]/curthresh;
			}
		}
		for(int i=0;i<simp.getNFrames();i++) {
			int cn=0;
			for(int j=0;j<n;j++) {
				threshMultiplier[i]+=threshs[j][i];
				cn++;
			}
			threshMultiplier[i]/=cn;
		}
		String printer="Thresh Multiplier applied: ";
		for(int i=0;i<threshMultiplier.length;i++)printer+=""+(i+1)+"-"+threshMultiplier[i]+"  ";
		IJ.log(printer);
	}
	
	private void showPlot() {
		int frms=times.length;
		if(frms==0)return;
		String[] colors=new String[] {"Cyan", "Magenta", "Green", "Red", "Blue"};

		double[] crcs=results.getColumn("Circularity");
		double[] cells=results.getColumn("Cell");
		String[] labels=results.getColumnAsStrings("Label");
		
		//if(crcs==null || crcs.length==0) {IJ.log("Can't show plot until at least one cell is finished."); return;}
		Plot plot=new Plot("Macrophage Circularity","Time","Circularity");
		
		if(crcs==null || crcs.length==0) {plotWindow=plot.show(); return;}
		
		double[] minutes=new double[times.length];
		for(int i=0;i<times.length;i++)minutes[i]=times[i]/60.0;
		
		int[] ncells=new int[cellLabels.size()];
		for(int i=0;i<cellLabels.size();i++) {
			for(int j=0;j<cells.length; j+=frms) {
				if(labels[j].contentEquals(cellLabels.get(i))) {
					ncells[i]=(int)cells[j];
				}
			}
		}
		int csdfr=0;
		for(int i=0;i<frms;i++) {
			if(times[i]==0) {csdfr=i; break;}
		}
		double totmax=0;
		for(int i=0;i<cellLabels.size();i++) {
			if(ncells[i]>0) {
				double[] sums=new double[frms], sum2s=new double[frms], means=new double[frms], ses=new double[frms];
				for(int j=0;j<cells.length; j+=frms) {
					if(labels[j].contentEquals(cellLabels.get(i))) {
						double bmean=0;
						for(int k=0;k<csdfr;k++) bmean+=crcs[j+k];
						bmean/=(double)csdfr;
						for(int k=0;k<times.length;k++) {
							double c=crcs[j+k]/bmean;
							sums[k]+=c; sum2s[k]+=c*c;
						}
					}
				}
				for(int k=0;k<times.length;k++) {
					double n=(double)ncells[i];
					means[k]=sums[k]/n;
					if(ncells[i]>1) ses[k]=(Math.sqrt(((n*sum2s[k]-sums[k]*sums[k])/n)/(n-1.0))/means[k])/Math.sqrt(n);
					totmax=Math.max(totmax, means[k]+ses[k]);
				}
				plot.setColor(colors[i]);
				plot.add("line", minutes, means);
				if(ncells[i]>1)plot.addErrorBars(ses);
			}
		}
		plot.setLimits(minutes[0], minutes[frms-1], 0.8, totmax);
		if(plotWindow!=null && !plotWindow.isClosed()) {
			plotWindow.setPlot(plot);
			plotWindow.setImage(plot.getImagePlus());
			plot.draw();
		}else {
			plotWindow=plot.show();
		}
	}
	
	public static void convertTCTtoGlasbey(ImagePlus imp, double xycal) {
		final int AREA_FUZZ=50;
		if(imp==null)return;
		if(imp.getBitDepth()==16){IJ.showStatus("16-bit AJTCT is assumed already Glasbey"); return;}
		ImageStack ist=imp.getStack();
		ImageProcessor ip=ist.getProcessor(imp.getStackIndex(1, 1, 1));
		if(ip==null)return;
		java.awt.image.ColorModel cm=ip.getColorModel();
		//check if LUT is already glasbey inverted
		if(cm!=null && cm.getRed(255)==248 && cm.getGreen(255)==248 && cm.getBlue(255)==232) {IJ.showStatus("AJTCT already converted"); return;}
		if(!imp.getTitle().contains("-AJTCT")) {
			YesNoCancelDialog ync=new YesNoCancelDialog(null, "Really?","Attempting to convert "+imp.getTitle()+", are you sure?");
			if(!ync.yesPressed())return;
		}
		String tresults=imp.getInfoProperty();
		if(tresults==null || "".contentEquals(tresults)) {
			Window[] wins=WindowManager.getAllNonImageWindows();
			for(int i=0;i<wins.length;i++) {
				if(wins[i] instanceof TextWindow && ((TextWindow)wins[i]).getTitle().startsWith("ThreshCellTransfer")) {
					tresults=((TextWindow)wins[i]).getTextPanel().getText();
					if(tresults.startsWith("\n"))tresults=tresults.substring(1);
					tresults=tresults.substring(tresults.indexOf("\n")+1); //remove heading
				}
			}
		}
		if(tresults==null || "".contentEquals(tresults)) {IJ.log("Cannot convert AJTCT, missing results"); return;}
		if(tresults.startsWith("\n"))tresults=tresults.substring(1);
		
		YesNoCancelDialog ync=new YesNoCancelDialog(null, "Really?","It looks like "+imp.getTitle()+" needs updating to Glasbey, ok to proceed?");
		if(!ync.yesPressed())return;
		if(LUT_GLASBEY_INV!=null)((CompositeImage)imp).setChannelLut(LUT_GLASBEY_INV, 1);
		
		String[] text=tresults.split("\n");
		for(int i=0;i<text.length;i++) {
			String[] line=text[i].split("\t");
			int x=(int)(AJ_Utils.parseDoubleTP(line[6])/xycal),y=(int)(AJ_Utils.parseDoubleTP(line[7])/xycal), frame=AJ_Utils.parseIntTP(line[10]), slice=1;
			int cellnum=AJ_Utils.parseIntTP(line[2]);//, roiarea2=0;
			double area=AJ_Utils.parseDoubleTP(line[3]), roiarea=0;
			if("Pia".contentEquals(line[0]))slice=2;
			ip=ist.getProcessor(imp.getStackIndex(1, slice, frame));
			ip.setColor(cellnum);
			int MAXATTEMPTS=5;
			boolean success=false;
			for(int j=0;j<MAXATTEMPTS;j++) {
				int xfac=0, yfac=0;
				if(j==1)xfac=-5; if(j==2)yfac=5; if(j==3)xfac=-10; if(j==4)yfac=10;
				Wand w=new Wand(ip);
				w.autoOutline(x+xfac, y+yfac);
				if(w.npoints>2) {
					Roi roi=new PolygonRoi(w.xpoints, w.ypoints, w.npoints, Roi.TRACED_ROI);
					Rectangle b=roi.getBounds();
					if(x>b.x &&  x<(b.x+b.width) && y>b.y && y<(b.y+b.height)) {
						ip.setRoi(roi);
						//roiarea2=roi.getContainedPoints().length;
						ImageStatistics imgstat=ImageStatistics.getStatistics(ip, 127, null);
						roiarea=(imgstat.area*xycal*xycal);
						if(roiarea<(area+AREA_FUZZ) && roiarea>(area-AREA_FUZZ)) {
							ip.fill(roi);
							success=true;
						}
					}
				}
			}
			if(!success) {
				imp.setC(1); imp.setZ(slice); imp.setT(frame);
				WaitForUserDialog wfu=new WaitForUserDialog("Could not find cell "+cellnum+" ROI at "+x+","+y+", please select\nArea:"+area+" rA:"+roiarea);
				wfu.show();
				if(wfu.escPressed())return;
				Roi roi=imp.getRoi();
				ip.fill(roi);
			}
		}
	}

	public void genSingleStack() {
		if(simp==null)return;
		String singletitle=basetitle+"-AJTCT-SingleStack.tif";
		ImagePlus timp2=IJ.createHyperStack(singletitle, timp.getWidth(), timp.getHeight(), timp.getNChannels(), timp.getNSlices(), 1, timp.getBitDepth());
		timp2.setDisplayMode(IJ.COMPOSITE);
		if(LUT_GLASBEY_INV!=null)((CompositeImage)timp2).setChannelLut(LUT_GLASBEY_INV, 1);
		ImageStack ist1=timp.getStack();
		ImageStack ist2=timp2.getStack();
		int chs=timp.getNChannels();
		int sls=timp.getNSlices();
		for(int z=1;z<=sls;z++) {
			for(int c=1;c<=chs;c++) {
				ImageProcessor ip=ist1.getProcessor(timp.getStackIndex(c, z, 1));
				ist2.setProcessor(ip.duplicate(), timp.getStackIndex(c, z, 1));
			}
		}
		timp2.setStack(ist2, chs, sls, 1);
		String info="";
		String tresults=timp.getInfoProperty();
		if(tresults!=null && !"".contentEquals(tresults)) {
			String[] lines=tresults.split("\n");
			info=lines[0]+"\n";
			int fri=HEADINGS.indexOf("Frame");
			for(int i=1;i<lines.length;i++) {
				String[] parts=lines[i].split("\t");
				if(parts.length<fri)continue;
				if(AJ_Utils.parseIntTP(parts[fri])==1) {
					info+=lines[i]+"\n";
				}
			}
			timp2.setProperty("Info", info);
		}

		timp2.show();
	}

	class TctTextWindow{
		TextWindow rtw;
		ResultsTable rt=null;
		public int roin=0;

		public TctTextWindow(String basetitle) {
			String currentHeadings=HEADINGS.getFullString();
			String twtitle="ThreshCellTransfer-"+basetitle+".csv";
			String[] restitles=new String[] {twtitle, "ThreshCellTransfer-"+basetitle+".xls", "ThreshCellTransfer-"+basetitle+".txt"};
			String openedHeadings="", openedText="";
			for(String restitle : restitles) {
				rtw=(TextWindow) WindowManager.getWindow(restitle);
				if(rtw!=null)break;
			}
			if(rtw!=null){
				rt=rtw.getResultsTable();
				openedHeadings=rt.getColumnHeadings();
				openedText=rtw.getTextPanel().getText();
				if(openedText.startsWith("\n"))openedText=openedText.substring(1);
				if(openedText.contains("\n"))openedText=openedText.substring(openedText.indexOf("\n")+1);
				rtw.close();
			}else{
				if(timp!=null && timp.getInfoProperty()!=null && !timp.getInfoProperty().trim().isEmpty()) {
					openedText=timp.getInfoProperty();
					if(openedText.startsWith("\n"))openedText=openedText.substring(1);
					openedHeadings=openedText.substring(0,openedText.indexOf("\n"));
					String[] ohsplit=openedHeadings.split("\t");
					if(AJ_Utils.parseIntTP(ohsplit[1])>0 && !ohsplit[1].toLowerCase().contains("roi")) {
						openedHeadings="";
					}else {
						openedText=openedText.substring(openedText.indexOf("\n")+1);
					}
					if(openedHeadings.contentEquals("")){
						int oldresheadlen=openedText.split("\n")[0].split("\t").length;
						openedHeadings=HEADINGS.values()[0].getString();
						for(int i=1;i<oldresheadlen; i++)openedHeadings=openedHeadings+"\t"+HEADINGS.values()[i].getString();
					}
				}
			}

			rtw=new TextWindow(twtitle,currentHeadings,"",800,400);

			int postData=0;
			if(!openedText.contentEquals("")){
				if(openedHeadings.contains("Cell#"))openedHeadings.replace("Cell#", "Cell");
				if(openedHeadings.contains("Cell."))openedHeadings.replace("Cell.", "Cell");
				if(!openedHeadings.contentEquals(currentHeadings)) {
					openedText=buildLines(openedHeadings.split("\t"), openedText);
				}
				if(!openedHeadings.contains("RedMean"))postData=1;
				String[] ot=openedText.split("\n")[0].split("\t");
				if((!openedHeadings.contains("AxonDistance") || ot[getHeadingIndex("AxonDistance")].contentEquals("NA")) && axonRoi!=null)postData+=2;
				if((!openedHeadings.contains("BVDistance") || ot[getHeadingIndex("BVDistance")].contentEquals("NA")) && (duraBVRoi!=null || piaBVRoi!=null))postData+=4;
				if((!openedHeadings.contains("CCR2Frame") || (ccr2frames!=null && openedText.split("\n")[ccr2frames[0]-1].split("\t")[getHeadingIndex("CCR2Frame")].contentEquals("false"))))postData+=8;
				rtw.append(openedText);
			}else{
				ImageProcessor ip=timp.getStack().getProcessor(1);
				int val=0; for(int x=0;x<timp.getWidth();x++)for(int y=0;y<timp.getHeight();y++)val=Math.max(val, ip.get(x,y));
				if(val>0) {
					YesNoCancelDialog ync=new YesNoCancelDialog(null,"Generate Results","No results Found, try to generate results from AJTCT?");
					if(ync.cancelPressed()) { rtw=null; return;}
					if(ync.yesPressed()) {
						times=Time_Extractor.extractTimes(simp, false, Time_Extractor.SubTime.EVENT_SET);
						int frms=simp.getNFrames();
						for(int sl=0;sl<timp.getNSlices();sl++) {
							if(sl>0) {
								val=0; ip=timp.getStack().getProcessor(timp.getStackIndex(1, sl+1, 1));
								for(int x=0;x<timp.getWidth();x++)for(int y=0;y<timp.getHeight();y++)val=Math.max(val, ip.get(x,y));
							}
							String label=timp.getStack().getSliceLabel(timp.getStackIndex(1, sl+1, 1));
							if(label!=null && !label.trim().isEmpty()) {label=label.trim().split("\n")[0];}
							if(label==null || label.trim().isEmpty() || label.contentEquals(timp.getTitle()))label=((sl+1)==1)?"Dura":"Pia";
							if(label!=null && !label.trim().isEmpty()) {
								cellLabels.add(label);
								cellLabel=label;
								for(celln=1; celln<=val; celln++) {
									curWand=new AutoWandRoi[frms];
									for(int fr=0;fr<frms;fr++) {
										Roi roi=getDrawnRoi(label,celln,fr+1);
										appendLineFromRoi(roi, fr+1, false, true);
									}
								}
								
							}
						}
					}
				}
			}

			if(!rtw.getTitle().contentEquals(twtitle))rtw.setTitle(twtitle);
			rtw.getTextPanel().setResultsTable(null);
			rt=rtw.getTextPanel().getOrCreateResultsTable();
			if(rt==null) {rt=new ResultsTable(); rtw.getTextPanel().setResultsTable(rt);}

			//IJ.log(""+panel.getLineCount());
			if(rt.getCounter()>0){
				if(postData>0)addPostData(postData);
				int troin=0;
				celln=0;
				labelsl=1;
				for(int i=0;i<rt.getCounter();i++) {
					String label=rt.getStringValue(0,i);
					if(i==0){
						cellLabels.add(label);
					}else{
						if(!cellLabels.contains(label)) {
							cellLabels.add(label);
							labelsl++;
						}
					}
					if(!rt.getStringValue(1,i).startsWith("NA"))troin=(int)rt.getValueAsDouble(1,i);
					if(!rt.getStringValue(2,i).startsWith("NA"))celln=(int) rt.getValueAsDouble(2,i);
					if(troin>roin)roin=troin;
				}
				if(cellLabels.size()>0)cellLabel=cellLabels.get(cellLabels.size()-1);
				celln++;roin++;
				IJ.log("Using open window, roin: "+roin+" celln: "+celln+" Label: "+cellLabel);
			}
		}

		

	private void appendLineFromRoi(Roi roi, int frame, boolean draw, boolean checkZ){
		int fr=frame-1;
		if(roi!=null) {
			int z=roi.getZPosition();
			roi.setPosition(0, 0, 0);
			if(z==0){
				z=getDuraCellZ(simp, roi);
				if(z==simp.getNSlices())z=simp.getZ();
			}
			if(z>simp.getNSlices() || z<1)z=simp.getZ();
			curWand[fr]=new AutoWandRoi(roi, 0.0, simp.getC(), z, fr+1);
			curWand[fr].accept(draw, roin++);
			results.appendLine(curWand[fr].output);
			curWand[fr].draw();
		}else {
			HashMap<HEADINGS, Object> emptyvals=new HashMap<HEADINGS, Object>();
			emptyvals.put(HEADINGS.LABEL, cellLabel);
			emptyvals.put(HEADINGS.ROI, 0);
			emptyvals.put(HEADINGS.CELL, celln);
			emptyvals.put(HEADINGS.FRAME, (fr+1));
			emptyvals.put(HEADINGS.THRESH, -1);
			emptyvals.put(HEADINGS.TIME, times[fr]);
			results.appendLine(emptyvals);
		}
	}

		public void addPostData(int postDataType) {
			addPostData(postDataType, false);
		}

		public void addPostData(int postDataType, boolean onlyIfNaN) {
			addPostData(postDataType, onlyIfNaN, 0, rt.getCounter());
		}

		
		/**
		 * Adds post-processing data to the provided text based on the specified data type.
		 * This method processes each line of the input text, extracts cell, slice, and frame
		 * information, and inserts additional data based on the type of post-processing 
		 * required.
		 *
		 * @param oldText The input text containing tab-separated values to process.
		 * @param postDataType An integer representing the type of post-processing data to add.
		 *                     It can be a combination of the following:
		 *                     - 1 (LIPOTYPE): Adds lipofuscin-related data.
		 *                     - 2 (AXONTYPE): Adds axon-distance.
		 *                     - 4 (BVTYPE): Adds blood vessel distance.
		 *                     - 8 (CCR2TYPE): Adds CCR2-related data.
		 * @return A string containing the processed text with added post-processing data.
		 */
		public void addPostData(int postDataType, boolean onlyIfNaN, int starti, int endi) {
			if(starti>=endi)return;
			final int LIPOTYPE=0b0001, AXONTYPE=0b0010, BVTYPE=0b0100, CCR2TYPE=0b1000;
			if((postDataType & AXONTYPE)==AXONTYPE && axonRoi==null)postDataType-=AXONTYPE;
			if((postDataType & BVTYPE)==BVTYPE && duraBVRoi==null && piaBVRoi==null)postDataType-=BVTYPE;
			if((postDataType & CCR2TYPE)==CCR2TYPE && ccr2frames==null)postDataType-=CCR2TYPE;
			if(postDataType<=0)return;
			String curlabel="";
			int tslice=0;
			double nResults=(double)rt.getCounter();
			IJ.showProgress(1.0/nResults);
			log("Adding post-processing data to results... 0/"+(int)nResults);
			for(int i=starti; i<endi; i++) {
				IJ.showStatus("Adding Ratio/axon/bvdistance data...");
				boolean skipline=false;
				if(onlyIfNaN){
					skipline=true;
					String text=rt.getStringValue(getHeadingIndex("RedMean"), i);
					if( (((postDataType & LIPOTYPE)==LIPOTYPE) && text!=null && ((text.contentEquals("NA")) || text.contentEquals("NaN")))){skipline=false;}
					text=rt.getStringValue(getHeadingIndex("AxonDistance"), i);
					if( (((postDataType & AXONTYPE)==AXONTYPE) && text!=null && ((text.contentEquals("NA")) || text.contentEquals("NaN")))){skipline=false;}
					text=rt.getStringValue(getHeadingIndex("BVDistance"), i);
					if( (((postDataType & BVTYPE)==BVTYPE) && text!=null && ((text.contentEquals("NA")) || text.contentEquals("NaN")))){skipline=false;}
				}
				if(!curlabel.contentEquals(rt.getStringValue(0, i))) {curlabel=rt.getStringValue(0, i); tslice++;}
				int cell=(int)rt.getValue("Cell",i), frame=(int)rt.getValue("Frame",i), slice=(int)rt.getValue("Slice",i);
				HashMap<HEADINGS, Object> data=null;
				if(slice>-1 && !skipline) {
					//Roi rtemp=timp.getRoi();
					////double pw=simp.getCalibration().pixelWidth;
					//int x=(int)(AJ_Utils.parseDoubleTP(line[getHeadingIndex("X")])/pw), y=(int)(AJ_Utils.parseIntTP(line[getHeadingIndex("Y")])/pw);
					//int cwidth=(int)(AJ_Utils.parseDoubleTP(line[getHeadingIndex("Perimeter")])/pw*2.0);
					//timp.setRoi(new Roi(Math.max(0, x-cwidth/2), Math.max(0, y-cwidth/2), cwidth, cwidth));
					ImageProcessor tip=timp.getStack().getProcessor(timp.getStackIndex(1, tslice, frame));
					//timp.setRoi(rtemp);
					tip.setThreshold(cell, cell);
					ThresholdToSelection tts=new ThresholdToSelection();
					Roi roi=tts.convert(tip);
					data=new HashMap<HEADINGS, Object>();
					if((postDataType & LIPOTYPE)==LIPOTYPE){
						data=fillLipoData(slice,frame,roi, data);
					}
					if((postDataType & AXONTYPE)==AXONTYPE){
						if(curlabel.contentEquals("Dura")) data=fillRoiData(axonRoi, roi, data, HEADINGS.AXONDISTANCE);
						else data.put(HEADINGS.AXONDISTANCE, Double.NaN);
					}
					if((postDataType & BVTYPE)==BVTYPE){
						Roi compareRoi=null;
						if(curlabel.contentEquals("Dura"))compareRoi=duraBVRoi;
						else if(curlabel.contentEquals("Pia"))compareRoi=piaBVRoi;
						data=fillRoiData(compareRoi,roi, data, HEADINGS.BVDISTANCE);
					}
					if((postDataType & CCR2TYPE)==CCR2TYPE){
						data.put(HEADINGS.CCR2FRAME, ""+isCCR2Frame(frame));
					}
				}
				
				for(HEADINGS key : data.keySet()){
					if(key.isString) rt.setValue(key.getIndex(), i, (String)data.get(key));
					else {
						Object val=data.get(key);
						if(val==null)val=Double.NaN;
						if(val instanceof Integer) rt.setValue(key.getIndex(), i, ((Integer)data.get(key)).doubleValue());
						else if(val instanceof Double) rt.setValue(key.getIndex(), i, ((Double)data.get(key)).doubleValue());
						else if(val instanceof Float) rt.setValue(key.getIndex(), i, ((Float)data.get(key)).doubleValue());
						else if(val instanceof Long) rt.setValue(key.getIndex(), i, ((Long)data.get(key)).doubleValue());
						else if(val instanceof Short) rt.setValue(key.getIndex(), i, ((Short)data.get(key)).doubleValue());
						else if(val instanceof Byte) rt.setValue(key.getIndex(), i, ((Byte)data.get(key)).doubleValue());
						else {
							IJ.log("Unknown data type for "+key.name()+", "+val.getClass().getName());
							rt.setValue(key.getIndex(), i, Double.NaN);
						}
					}
				}
				IJ.showProgress(((double)i+1.0)/nResults);
				rt.updateResults();
			}
		}

		private String getText(boolean withHeadings) {
			if(withHeadings)return rtw.getTextPanel().getText();
			String[] temp=rtw.getTextPanel().getText().split("\n");
			String result="";
			if(temp.length>1)result=temp[1];
			for(int i=2; i<temp.length;i++)result+="\n"+temp[i];
			return result;
		}

		public ResultsTable getResultsTable() {return rt;}

		public String[] getColumnAsStrings(int col) {
			String[] headings=rt.getColumnHeadings().split("\t");
			if(headings.length<(col+1) || col<0) {
				IJ.log("Selected column "+col+" out of range.");
				return null;
			}
			if(rt.getCounter()==0) {
				//IJ.log("Textpanel had no data");
				return null;
			}
			String[] result=new String[rt.getCounter()];
			for(int i=0; i<rt.getCounter();i++)result[i]=rt.getStringValue(col, i);
			return result;
		}

		public double[] getColumn(int col) {
			if(rt.getCounter()==0)return null;
			double[] result=new double[rt.getCounter()];
			for(int i=0; i<rt.getCounter();i++)result[i]=rt.getValueAsDouble(col, i);
			return result;
		}

		public int getHeadingIndex(String col) {
			String headings=rtw.getTextPanel().getText().split("\n")[0];
			if(headings==null || headings.isEmpty() || headings.contentEquals(""))headings=HEADINGS.getFullString();
			String[] hds=headings.split("\t");
			for(int i=0;i<hds.length;i++)if(hds[i].contains(col))return i;
			return -1;
		}

		public String[] getColumnAsStrings(String col) {
			return getColumnAsStrings(getHeadingIndex(col));
		}

		public double[] getColumn(String col) {
			return getColumn(getHeadingIndex(col));
		}

		private int getLineIndex(String elabel, int cell, int frame) {
			if(rt.getCounter()==0)return -1;
			int frms=simp.getNFrames();
			if(cell<1 || frame>frms || frame<1)return -1;
			int index=cellLabels.indexOf(elabel);
			if(index==-1) {return -1;}
			int i=0;
			while(i<rt.getCounter() && !rt.getStringValue(HEADINGS.LABEL.getIndex(), i).contentEquals(elabel))i++;
			while(i<rt.getCounter() && rt.getStringValue(HEADINGS.LABEL.getIndex(), i).contentEquals(elabel) && 
				rt.getValueAsDouble(HEADINGS.CELL.getIndex(),i)!=cell)i++;
			while(i<rt.getCounter() && rt.getStringValue(HEADINGS.LABEL.getIndex(), i).contentEquals(elabel) && 
				rt.getValueAsDouble(HEADINGS.CELL.getIndex(),i)==cell && rt.getValueAsDouble(HEADINGS.FRAME.getIndex(),i)!=frame)i++;
			if(i>=rt.getCounter()) return -1;
			if(!rt.getStringValue(HEADINGS.LABEL.getIndex(), i).contentEquals(elabel) || (int)rt.getValueAsDouble(HEADINGS.CELL.getIndex(), i)!=cell 
				|| (int)rt.getValueAsDouble(HEADINGS.FRAME.getIndex(), i)!=frame) {
				return -1;
			}
			return i;
		}

		public int getZforCellFrame(String elabel, int cell, int frame) {
			int linei=getLineIndex(elabel, cell, frame);
			if(linei==-1)return -1;
			return (int)rt.getValueAsDouble(HEADINGS.SLICE.getIndex(), linei);
		}

		public double getValueForCellFrame(String elabel, int cell, int frame, HEADINGS col) {
			int linei=getLineIndex(elabel, cell, frame);
			if(linei==-1)return Double.NaN;
			return rt.getValueAsDouble(col.getIndex(), linei);
		}

		private void updateDisplay(){
			TextPanel tp=rtw.getTextPanel();
			//tp.clear();
			tp.setColumnHeadings(HEADINGS.getFullString());
			for(int i=0; i<rt.getCounter();i++) {
				tp.appendWithoutUpdate(rt.getRowAsString(i));
			}
			tp.updateDisplay();
		}

		private void insertLine(HashMap<HEADINGS, Object> values) {
			String elabel=(String)values.get(HEADINGS.LABEL);
			int cell=(int)values.get(HEADINGS.CELL);
			int frame=(int)values.get(HEADINGS.FRAME);
			int linei=getLineIndex(elabel, cell, frame);
			if(linei==-1){
				while(cell>getLastCellForLabel(elabel)) {
					addCellForLabel(elabel);
				}
				linei=getLineIndex(elabel, cell, frame);
			}
			for(HEADINGS key : HEADINGS.values()){
				Object val=values.get(key);
				setValue(linei, key, val);
			}
		}

		private void appendLine(HashMap<HEADINGS, Object> values) {
			int i=rt.getCounter();
			for(HEADINGS key : HEADINGS.values()){
				Object val=values.get(key);
				setValue(i, key, val);
			}
		}

		private void setValue(int index, HEADINGS key, Object val) {
			if(index==-1)return;
			if(key.isString) {
				if(val==null)val="NA";
				else val=val.toString();
				rt.setValue(key.getString(), index, (String)val);
			}else {
				if(val==null)val=Double.NaN;
				else if(val instanceof Integer)val=((Integer)val).doubleValue();
				else if(val instanceof Double)val=((Double)val).doubleValue();
				else val=Double.NaN;
				rt.setValue(key.getString(), index, (double)val);
			}
		}

		private void addCellForLabel(String elabel){
			if(rt.getCounter()==0)return;
			int inserti=0;
			while(inserti<rt.getCounter() && !rt.getStringValue(HEADINGS.LABEL.getIndex(), inserti).contentEquals(elabel))inserti++;
			while(inserti<rt.getCounter() && rt.getStringValue(HEADINGS.LABEL.getIndex(), inserti).contentEquals(elabel))inserti++;
			if(inserti==rt.getCounter())return;
			int cell=(int)rt.getValueAsDouble(HEADINGS.CELL.getIndex(), inserti-1)+1;
			int frms=(int)rt.getValueAsDouble(HEADINGS.FRAME.getIndex(), inserti-1);
			ResultsTable temp=new ResultsTable();
			for(int c=0;c<HEADINGS.length();c++){
				for(int i=0; i<inserti; i++){
					if(HEADINGS.values()[c].isString)
						temp.setValue(HEADINGS.values()[c].getString(), i, rt.getStringValue(c, i));
					else{
						double val=rt.getValueAsDouble(c, i);
						temp.setValue(HEADINGS.values()[c].getString(), i, val);
					}
				}
				for(int i=0; i<frms; i++){
					if(c==0) {
						temp.setValue(HEADINGS.LABEL.getIndex(), inserti+i, elabel);
					} else if(c==HEADINGS.CELL.getIndex()) {
						temp.setValue(c, inserti+i, cell);
					}else if(c==HEADINGS.FRAME.getIndex()) {
						temp.setValue(c, inserti+i, i+1);
					}
				}
				for(int i=inserti; i<rt.getCounter(); i++){
					if(HEADINGS.values()[c].isString)
						temp.setValue(HEADINGS.values()[c].getIndex(), i+frms, rt.getStringValue(c, i));
					else
						temp.setValue(HEADINGS.values()[c].getIndex(), i+frms, rt.getValueAsDouble(c, i));
				}
			}
			rtw.getTextPanel().setResultsTable(temp);
			rt=rtw.getTextPanel().getResultsTable();
		}
		
		public int getLastCellForLabel(String elabel) {
			int cell=0;
			boolean curlabel=false;
			for(int i=0; i<rt.getCounter();i++) {
				if(rt.getStringValue(HEADINGS.LABEL.getIndex(), i).contentEquals(elabel)) {
					cell=(int)rt.getValueAsDouble(HEADINGS.CELL.getIndex(), i);
					curlabel=true;
				}else if(curlabel)break;
			}
			return cell;
		}

		public String buildLines(String[] oldHeadings, String oldText){
			String[] text=oldText.split("\n");
			HashMap<HEADINGS, Object> linemap=new HashMap<HEADINGS, Object>();
			for(int i=0;i<text.length;i++){
				String[] parts=text[i].split("\t");
				for(int j=0;j<parts.length;j++) {
					Object val=null;
					HEADINGS key=HEADINGS.get(oldHeadings[j]);
					if(key!=null) {
						if(key.isString)val=parts[j];
						else {
							try {
								val=Double.parseDouble(parts[j]);
							}catch(NumberFormatException e) {
								val=Double.NaN;
							}
						}
					}
					linemap.put(key, val);
				}
				text[i]=buildLine(linemap);
				linemap.clear();
			}
			return String.join("\n", text);
		}
		
		public void save() {save(null);}
		
		public void save(String path) {
			if(path==null) path=spath+rtw.getTitle();
			rt.save(path);
		}
	}

	class TCTPanel extends Frame{

		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;
		TextArea ta=null;
		private Choice whichPoint;
		private Choice whichlabel;
		public CheckboxMenuItem autosavecbmi;

		public TCTPanel() {
			super("TCT Control Panel version "+version);
			/*
			 addWindowListener(new WindowAdapter(){
				public void windowClosing(WindowEvent we){
					recorder.stop();
					dispose();
				}
			});
			 */
			this.setFocusable(false);
			setLayout(new GridBagLayout());
			GridBagConstraints c = new GridBagConstraints();
			
			final Choice roiDistChoice=new Choice();
			roiDistChoice.add("Distance Rois");
			roiDistChoice.add(axonRoi==null?"Add Axon Roi":"--Axon Roi Added--");
			roiDistChoice.add(duraBVRoi==null?"Add Dura BV Roi":"--Dura BV Roi Added--");
			roiDistChoice.add(piaBVRoi==null?"Add Pia BV Roi":"--Pia BV Roi Added--");

			ActionListener l=new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					switch(e.getActionCommand()){
					case "LUT":
						changeLutType();
						break;
					case "askCellLabel":
						askCellLabel();
						break;
					case "gocellcomplete":
						gocellcomplete=true;
						break;
					case "changeWheelFactor":
						changeWheelFactor(1);
						((Button)e.getSource()).setLabel("Wheel - "+WHEELFACTORS[wheelfactori]);
						break;
					case "updateCurWand":
						updateCurWands(false, false, false);
						break;
					case "Re-confirm All":
						GenericDialog gd=new GenericDialog("Re-confirm All Cells");
						gd.addNumericField("Start reevaluate at cell #:",1,0	);
						gd.addCheckbox("With confirmation?", true);
						gd.showDialog();
						if(gd.wasCanceled())break;
						celln=(int)gd.getNextNumber();
						skipconfirmation=!gd.getNextBoolean();
						reconfirming=true;
						break;
					case "autoacceptall":
						autoAcceptAll();
						break;
					case "addDirectRoi":
						addDirectRoi=true;
						break;
					case "deleteDirectRoi":
						deleteDirectRoi();
						break;
					case "editRoi":
						reconfirming=false;
						editRoi=true;
						break;
					case "autothresh":
						genAutoThresh();
						break;
					case "showplot":
						showPlot();
						break;
					case "done":
						done=true;
						break;
					default:
						break;
					}

				}

			};

			//c.fill=GridBagConstraints.HORIZONTAL;
			c.weightx=1; c.weighty=1;
			c.gridwidth=1; c.gridheight=1;
			Button b=null;
			
			c.gridy=0;	// line 1 --------------------- //
			
			c.gridx=0; //X=0
			whichlabel=new Choice();
			whichlabel.setFocusable(false);
			resetWL();
			whichlabel.select(cellLabels.contains(cellLabel)?cellLabels.indexOf(cellLabel):0);
			whichlabel.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					String cs=whichlabel.getSelectedItem();
					if(cs.startsWith("Add new")) {
						askCellLabel();
					}else {
						setCellLabel(cs);
					}
					whichlabel.select(cellLabels.indexOf(cellLabel));
					if(showAllRois)showAllRois();
				}
			});
			add(whichlabel,c);
			//b=new Button();
			//b.setFocusable(false);
			//b.setActionCommand("LUT");
			//b.setLabel("LUT");
			//b.addActionListener(l);
			//if(simp.isComposite())b.setEnabled(false);
			//add(b,c);
			
			c.gridx++; //1
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("editRoi");
			b.setLabel("Edit Existing Roi");
			b.addActionListener(l);
			add(b,c);
			
			c.gridx++; //2
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("changeWheelFactor");
			b.setLabel("Wheel - "+WHEELFACTORS[wheelfactori]);
			b.addActionListener(l);
			add(b,c);
			
			c.gridx++; //3
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("gocellcomplete");
			b.setLabel("Cell complete");
			b.addActionListener(l);
			add(b,c);
			
			c.gridx++; //4
			roiDistChoice.setFocusable(false);
			roiDistChoice.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					final int si=roiDistChoice.getSelectedIndex();
					if(si==0)return;
					String whichRoi="Axon Roi";
					if(si==2) whichRoi="Dura BV Roi";
					else if(si==3) whichRoi="Pia BV Roi";

					ImagePlus roiimp=IJ.createImage(whichRoi, "8bit black", simp.getWidth(), simp.getHeight(), 1);
					roiimp.show();
					
					if(roiDistChoice.getSelectedItem().startsWith("--")) {
						if(si==1)roiimp.setRoi(axonRoi);
						else if(si==2)roiimp.setRoi(duraBVRoi);
						else roiimp.setRoi(piaBVRoi);
						YesNoCancelDialog ync=new YesNoCancelDialog(null,"Distance Rois","Remove "+whichRoi+"?");
						if(ync.yesPressed()) {
							if(si==1)axonRoi=null;
							else if(si==2)duraBVRoi=null;
							else piaBVRoi=null;
						}
					}else {
						GenericDialog gd=new GenericDialog("Distance Rois");
						gd.addMessage("Add new "+whichRoi);
						RoiManager roiManager=RoiManager.getRoiManager();
						int openRois=roiManager.getCount();
						if(openRois>1) {
							String[] rmis=new String[openRois+1];
							rmis[0]="No";
							for(int i=0; i<openRois; i++)rmis[i+1]=""+(i+1);
							gd.addChoice("Add from RoiManager:", rmis, "No");
							((java.awt.Choice)gd.getChoices().get(0)).addItemListener(new ItemListener() {
								@Override
								public void itemStateChanged(ItemEvent e) {
									int selec=gd.getNextChoiceIndex()-1;
									if(selec>-1) {
										Roi oproi=roiManager.getRoi(selec);
										roiimp.setRoi(oproi);
										roiimp.updateAndDraw();
									}
								}
							});
						}
						gd.addButton("Add from file", new ActionListener() {
							@Override
							public void actionPerformed(ActionEvent e) {
								OpenDialog od = new OpenDialog("Open", "");
								String directory = od.getDirectory();
								String name = od.getFileName();
								if (name!=null) {
									String path = directory+name;
									WindowManager.setCurrentWindow(simp.getWindow());
									Roi temproi=simp.getRoi();
									Roi oproi=null;
									if(name.endsWith(".roi")) {
										IJ.open(path);
										IJ.wait(1000);
										oproi=simp.getRoi();
										simp.setRoi(temproi);
									}else {
										ImagePlus tempimp=IJ.openImage(path);
										IJ.run("Create Selection");
										IJ.wait(1000);
										oproi=tempimp.getRoi();
										tempimp.close();
									}
									if(oproi!=null) {
										roiManager.addRoi(oproi);
										roiimp.setRoi(oproi);
										roiimp.updateAndDraw();
										if(si==1)axonRoi=oproi;
										else if(si==2)duraBVRoi=oproi;
										else piaBVRoi=oproi;
									}else IJ.log("Roi open was not successful, no roi found");
								}
							}
						});
						gd.showDialog();
						if(!gd.wasCanceled()) {
							Roi oproi=roiimp.getRoi();
							if(si==1)axonRoi=oproi;
							else if(si==2)duraBVRoi=oproi;
							else piaBVRoi=oproi;
						}
					}
					roiimp.close();
					roiDistChoice.removeAll();
					roiDistChoice.add("Distance Rois");
					roiDistChoice.add(axonRoi==null?"Add Axon Roi":"--Axon Roi Added--");
					roiDistChoice.add(duraBVRoi==null?"Add Dura BV Roi":"--Dura BV Roi Added--");
					roiDistChoice.add(piaBVRoi==null?"Add Pia BV Roi":"--Pia BV Roi Added--");
				}
			});
			add(roiDistChoice,c);
			
			c.gridy++; //1 ---- line 2 ----- //
			c.gridx=0;
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("updateCurWand");
			b.setLabel("Update curWand");
			b.addActionListener(l);
			add(b,c);
			c.gridx++; //1
			b=new Button("Re-confirm All");
			b.setFocusable(false);
			b.addActionListener(l);
			add(b,c);
			c.gridx++; //2
			whichPoint=new Choice();
			whichPoint.setFocusable(false);
			resetWP();
			whichPoint.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					String cs=whichPoint.getSelectedItem();
					if(cs.startsWith("Which Point")) {
						return;
					}else if(cs.contentEquals("New Point") || cs.contentEquals("New Negative Point")) {
						if(xys.size()==0 || xys.size()==MAX_POINTS) {
							if(xys.size()==0) IJ.showMessage("First point must defined before adding more");
							if(xys.size()==MAX_POINTS) IJ.showMessage("Sorry, maximum number of points reached");
							whichPoint.select(0);
							return;
						}
						boolean isNeg=cs.contentEquals("New Negative Point");
						xys.add(new WandPoint(null, xys.get(0).thresh, isNeg));
						if(!isNeg && xys.get(0).getDirectRoiSize()>0) {
							YesNoCancelDialog ync=new YesNoCancelDialog(null, "New Point","Copy all directRoi for new Point?");
							if(ync.cancelPressed()) {whichPoint.select(0); return;}
							if(ync.yesPressed()) {
								for(Roi droi : xys.get(0).directRois) {
									xys.get(xys.size()-1).addDirectRoi((Roi)droi.clone());
								}
							}
						}
						setPointIndex(xys.size()-1);
					}else if(cs.contentEquals("Delete Point")) {
						int ind=xys.size()-1;
						if(ind>0) {
							if(xys.get(ind).directRois!=null && xys.get(ind).directRois.size()>0) {
								for(Roi roi : xys.get(ind).directRois){
									soverlay.remove(roi);
								}
							}
							xys.remove(ind);
							if(pointIndex>=xys.size())pointIndex=xys.size()-1;
							resetWP();
							for(int i=0;i<curWand.length;i++) {
								if(curWand[i]!=null) {
									curWand[i].deletePoint(ind);
									curWand[i].updateWandRoi();
									if(i==(simp.getT()-1))curWand[i].showBoth();
								}
							}
						}else IJ.showStatus("Cannot delete point 1");
					} else {
						setPointIndex(whichPoint.getSelectedIndex()-1);
					}
				}
			});
			add(whichPoint, c);
			c.gridx++; //2
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("autoacceptall");
			b.setLabel("Accept all frames");
			b.addActionListener(l);
			add(b,c);
			c.gridx++; //3
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("done");
			b.setLabel("Stop TCT");
			b.addActionListener(l);
			add(b,c);
			c.gridx=0;
			c.gridy++;
			Button dropDownMenu=new Button("Options ▼");
			dropDownMenu.setFocusable(false);
			PopupMenu options=new PopupMenu();
			autosavecbmi=new CheckboxMenuItem("Auto Save", autoSave);
			autosavecbmi.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					autoSave=!autoSave;
					autosavecbmi.setState(autoSave);
				}
			});
			options.add(autosavecbmi);
			CheckboxMenuItem cbmi=new CheckboxMenuItem("Show All Rois", showAllRois);
			cbmi.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					showAllRois=!showAllRois;
					((CheckboxMenuItem)e.getSource()).setState(showAllRois);
				}
			});
			options.add(cbmi);
			cbmi=new CheckboxMenuItem("Sync Zoom", syncWindows);
			cbmi.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					syncWindows=!syncWindows;
					((CheckboxMenuItem)e.getSource()).setState(syncWindows);
				}
			});
			options.add(cbmi);
			cbmi=new CheckboxMenuItem("Auto Accept", autoAccept);
			cbmi.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					autoAccept=!autoAccept;
					((CheckboxMenuItem)e.getSource()).setState(autoAccept);
				}
			});
			options.add(cbmi);
			cbmi=new CheckboxMenuItem("Check for closest z-maximum", checkZmax);
			cbmi.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					checkZmax=!checkZmax;
					((CheckboxMenuItem)e.getSource()).setState(checkZmax);
				}
			});
			options.add(cbmi);
			cbmi=new CheckboxMenuItem("Log DEBUG", DEBUG);
			cbmi.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					DEBUG=!DEBUG;
					((CheckboxMenuItem)e.getSource()).setState(DEBUG);
				}
			});
			options.add(cbmi);
			cbmi=new CheckboxMenuItem("First Curwand in-thread", inThreadCurwand.get());
			cbmi.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					inThreadCurwand.set(!inThreadCurwand.get());
					((CheckboxMenuItem)e.getSource()).setState(inThreadCurwand.get());
				}
			});
			options.add(cbmi);
			MenuItem mi=new MenuItem("Recalculate A/BV Distances");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					recalcABV.set(1);
					log("Recalculating A/BV distances...");
				}
			});
			options.add(mi);
			mi=new MenuItem("Recalculate A/BV Distances NaN only");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					recalcABV.set(2);
					log("Recalculating A/BV Distances for NaN values only...");
				}
			});
			options.add(mi);
			mi=new MenuItem("Recalculate Red channel data");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					java.awt.EventQueue.invokeLater(new Runnable() {
						@Override
						public void run() {
							log("Recalculating Red Channel data...");
							results.addPostData( 1);
						}
					});
				}
			});
			options.add(mi);
			mi=new MenuItem("Convert AJTCT to Glasbey Inverted LUT");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					java.awt.EventQueue.invokeLater(new Runnable() {
						@Override
						public void run() {
							log("Converting AJTCT to Glasbey Inverted LUT...");
							convertTCTtoGlasbey(timp, simp.getCalibration().pixelWidth);
						}
					});
				}
			});
			options.add(mi);
			mi=new MenuItem("Generate AJTCT SingleStack");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					genSingleStack();
				}
			});
			options.add(mi);
			mi=new MenuItem("Save MDInfo File");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					saveMDInfo();
				}
			});
			options.add(mi);
			mi=new MenuItem("Set CCR2 Frames");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					setCCR2Frames();
				}
			});
			options.add(mi);
			mi=new MenuItem("Copy Previous Roi");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					int fr=simp.getFrame()-1;
					if(curWand[fr]==null) curWand[fr]=new AutoWandRoi(simp.getC(), simp.getZ(), fr+1);
					curWand[fr].setPrevRoi();
					curWand[fr].showBoth();
				}
			});
			options.add(mi);
			mi=new MenuItem("AJTCTcp diff overlay");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					if(soverlay!=null)soverlay.remove(ajtctcpimpAllroi);
					ajtcpcpDiffOverlay();
					if(soverlay!=null)soverlay.add(ajtctcpimpAllroi);
					simp.updateAndDraw();
				}
			});
			options.add(mi);
			mi=new MenuItem("Import All Rois from Manager");
			mi.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					importAllRoisFromManager();
				}
			});
			options.add(mi);
			dropDownMenu.add(options);
			dropDownMenu.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					options.show(dropDownMenu, 0, dropDownMenu.getHeight());
				}
			});
			add(dropDownMenu,c);
			c.gridx++;
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("addDirectRoi");
			b.setLabel("Add direct ROI");
			b.addActionListener(l);
			add(b,c);
			c.gridx++;
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("deleteDirectRoi");
			b.setLabel("Delete Direct Roi");
			b.addActionListener(l);
			add(b,c);
			c.gridx++;
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("autothresh");
			b.setLabel("AutoThresh");
			b.addActionListener(l);
			add(b,c);
			c.gridx++;
			b=new Button("Show Plot");
			b.setFocusable(false);
			b.setActionCommand("showplot");
			b.addActionListener(l);
			add(b,c);

			c.gridwidth=5; c.gridx=0; c.gridy++; //2
			ta=new TextArea("",15,105,TextArea.SCROLLBARS_NONE);
			ta.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
			ta.setEditable(false);
			ta.setFocusable(false);
			add(ta,c);

			pack();
		}

		/**
		 * Set (0-index) line of text area with text
		 * @param line
		 * @param text
		 */
		public void setTextLine(TctLines tctline, String text) {
			int line=tctline.ordinal();
			String[] tatext=ta.getText().split("\n");
			String out="";
			final int max=Math.max(line,tatext.length);
			for(int i=0;i<=max;i++) {
				if(i==line)out+=text+"\n";
				else if(i<tatext.length)out+=tatext[i]+"\n";
				else out+="\n";
			}
			ta.setText(out);

		}

		public void resetWP() {
			whichPoint.removeAll();
			whichPoint.add("Which Point: "+(pointIndex+1));
			for(int i=0;i<xys.size();i++)whichPoint.add(""+(i+1)+(xys.get(i).isNeg?"-":""));
			whichPoint.add("New Point");
			whichPoint.add("New Negative Point");
			whichPoint.add("Delete Point");
			whichPoint.select(0);
		}
		
		public void resetWL() {
			whichlabel.removeAll();
			whichlabel.add("Dura");
			whichlabel.add("Pia");
			for(int i=0; i<cellLabels.size(); i++) {
				String label=cellLabels.get(i);
				if(!(label.equals("Dura") || label.equals("Pia")))whichlabel.add(label);
			}
			whichlabel.add("Add new Label");
			int i=cellLabels.indexOf(cellLabel); if(!cellLabels.contains("Dura"))i++; if(!cellLabels.contains("Pia") && !cellLabel.contentEquals("Dura"))i++; 
			whichlabel.select(i);
		}
			
	}

	private void ajtcpcpDiffOverlay(){
		if(ajtctcpimp==null) {
			IJ.error("No AJTCTcp image found, please run AJTCTcp first");
			return;
		}
		if(showAllRoi==null)showAllRois();
		if(showAllRoi==null) {
			IJ.error("Could not create showAllRoi overlay, please check that rois are present");
			return;
		}
		
		ImagePlus diffimp=IJ.createImage("AJTCTcp Diff Overlay", "8-bit black", simp.getWidth(), simp.getHeight(), 1);
		ImageProcessor dip=diffimp.getProcessor();
		dip.fill(showAllRoi);
		int max=0;
		ImageProcessor cip=ajtctcpimp.getProcessor();
		if(cip instanceof ByteProcessor){
			byte bmax=0;
			byte[] pixels=(byte[])cip.getPixels();
			for(int i=0;i<pixels.length;i++) {
				if(pixels[i]>bmax)bmax=pixels[i];
				if(bmax==255)break;
			}
			max=(int)bmax&0xff;
		}else if(cip instanceof ShortProcessor){
			short smax=0;
			short[] pixels=(short[])cip.getPixels();
			for(int i=0;i<pixels.length;i++) {
				if(pixels[i]>smax)smax=pixels[i];
			}
			max=(int)smax&0xffff;
		}else{
			IJ.error("AJTCTcp image is not 8-bit or 16-bit, cannot create diff overlay");
			return;
		}
		ajtctcpimpAllroi=null;
		for(int i=0;i<max;i++) {
			Roi roi=getDrawnRoi(cip, i+1);
			Point[] pts=roi.getContainedPoints();
			boolean hasPix=false;
			for(Point p : pts) {
				if(dip.getPixel(p.x,p.y)==1){
					hasPix=true;
					break;
				}
			}
			if(hasPix) {
				ajtctcpimpAllroi=addRoi(ajtctcpimpAllroi, roi);
			}
		}
	}

	private void importAllRoisFromManager() {
		if(simp.getNFrames()>1) {
			IJ.log("RoiManager Import not currently working for multi-frame images");
			return;
		}
		RoiManager rm=RoiManager.getInstance();
		String source="RoiManager";
		int startWithRoi=1;
		if(celln>1) {
			if(ajtctcpimp!=null){
				String info=ajtctcpimp.getInfoProperty();
				if(info!=null && info.contains("importFromCell: ")) {
					String[] lines=info.split("\n");
					for(String line : lines) {
						if(line.startsWith("importFromCell: ")) {
							String[] parts=line.split(": ");
							try {
								startWithRoi=Integer.parseInt(parts[1].trim());
							}catch(NumberFormatException e) {
								startWithRoi=celln;
							}
						}
					}
				}
			}
			GenericDialog gd=new GenericDialog("Import All Rois");
			gd.addMessage("Current cell number is "+celln+"\nDo you want to start importing from this cell number or another?");
			gd.addNumericField("Start importing from cell number:", celln, 0);
			gd.showDialog();
			if(gd.wasCanceled())return;
			startWithRoi=(int)gd.getNextNumber();
		}
		if(rm==null || rm.getCount()==0) {
			if(ajtctcpimp!=null){
				YesNoCancelDialog ync=new YesNoCancelDialog(null, "Import All Rois", "No RoiManager rois found, but AJTCTcp image is present\nDo you want to import all rois from AJTCTcp?");
				if(ync.yesPressed()){
					allRois=new ArrayList<Roi>();
					masksToRois(ajtctcpimp.getProcessor(), true, allRois, startWithRoi);
					source="AJTCTcp";
				}else return;
			}else{
				IJ.error("No RoiManager rois found, please open RoiManager and add rois");
				return;
			}
		}else {
			allRois=new ArrayList<Roi>(Arrays.asList(rm.getRoisAsArray()));
			if(startWithRoi>1) {
				if(allRois.size()<startWithRoi) {
					IJ.error("Not enough rois in RoiManager to skip first "+(startWithRoi-1)+" rois");
					return;
				}
				allRois=new ArrayList<Roi>(allRois.subList(startWithRoi-1, allRois.size()));
			}
		}
		YesNoCancelDialog ync=new YesNoCancelDialog(null, "Import All Rois", "Importing all rois from " + source + "\nDo you want to confirm each one?");
		if(ync.cancelPressed())return;
		if(ync.yesPressed()){
			importingFromAllRois=true;
			curWand[0]=null;
			acceptedROI.set(true);
			return;
		}
		for(Roi roi : allRois) {
			int frame=roi.getTPosition();
			if(frame==0)frame=1;
			results.appendLineFromRoi(roi, frame, true, true);
			celln++;
		}
		results.rt.updateResults();
	}

	private boolean isCCR2Frame(int fr) {
		if(ccr2frames==null)return false;
		for(int f : ccr2frames)if(f==fr)return true;
		return false;
	}

	private void setCCR2Frames() {
		GenericDialog gd=new GenericDialog("Set CCR2 Frames");
		gd.addMessage("Enter frame numbers for CCR2 expression, separated by commas (e.g. 1,3,5) or dash (1-2)");
		String current="";
		if(ccr2frames!=null) {
			for(int f : ccr2frames)current+=f+",";
			current=current.substring(0, current.length()-1);
		}
		gd.addStringField("CCR2 Frames:", current, 15);
		gd.showDialog();
		if(gd.wasCanceled())return;
		String input=gd.getNextString();
		input=input.trim();
		String[] info=simp.getInfoProperty().split("\n");
		int ccr2index=-1;
		String ccr2linest="CCR2Frames: ";
		for(int i=0;i<info.length;i++) {
			if(info[i].startsWith(ccr2linest)) {ccr2index=i; break;}
		}
		if(input.isEmpty()) {
			ccr2frames=null;
			IJ.showStatus("CCR2 frames cleared");
			if(ccr2index>-1) {
				String[] newinfo=new String[info.length-1];
				for(int i=0;i<info.length;i++) {
					if(i<ccr2index)newinfo[i]=info[i];
					else if(i>ccr2index)newinfo[i-1]=info[i];
				}
				simp.setProperty("Info", String.join("\n", newinfo));
			}
		}else{
			String newline=ccr2linest+input;
			if(getCCR2Frames(newline)==null)return;
			ccr2frames=getCCR2Frames(newline);
			if(ccr2index>-1) {
				info[ccr2index]=newline;
				simp.setProperty("Info", String.join("\n", info));
			}
		}
		IJ.showStatus("CCR2 frames set to: "+input);
		results.addPostData(8);
	}

	public static int[] getCCR2Frames(ImagePlus imp){
		return getCCR2Frames(imp.getInfoProperty());
	}

	public static int[] getCCR2Frames(String info) {
		int[] result=null;
		if(info==null || info.isEmpty()) return null;
		String[] lines=info.split("\n");
		for(String line : lines) {
			String linestarter="CCR2Frames: ";
			if(line.startsWith(linestarter)) {
				String cline=line.substring(linestarter.length()).trim();
				if(cline.isEmpty())return null;
				if(cline.contains(",")) {
					String[] parts=cline.split(",");
					result=new int[parts.length];
					for(int i=0;i<parts.length;i++) {
						try {
							result[i]=Integer.parseInt(parts[i].trim());
						}catch(NumberFormatException e) {
							IJ.log("Error parsing CCR2Frames part: "+parts[i]);
							return null;
						}
					}
				}else if(cline.contains("-")) {
					String[] parts=cline.split("-");
					if(parts.length!=2) {
						IJ.log("Error parsing CCR2Frames range: "+cline);
						return null;
					}
					try {
						int start=Integer.parseInt(parts[0].trim());
						int end=Integer.parseInt(parts[1].trim());
						result=new int[end-start+1];
						for(int i=0;i<result.length;i++) {
							result[i]=start+i;
						}
					}catch(NumberFormatException e) {
						IJ.log("Error parsing CCR2Frames range part: "+cline);
						return null;
					}
				}else {
					try {
						int single=Integer.parseInt(cline.trim());
						result=new int[] {single};
					}catch(NumberFormatException e) {
						IJ.log("Error parsing CCR2Frames single value: "+cline);
						return null;
					}
				}
				return result;
			}
		}
		return null;
	}

	private void saveMDInfo() {
		String Rpath="\"C:\\Program Files\\R\\R-4.5.2\\bin\\R.exe\"";
		String Rdata="\"C:\\Users\\aaron\\OneDrive - Beth Israel Lahey Health\\Lab\\R\\macrophages\\.RData\"";
		String spathconv=spath.replaceAll("\\\\", "/");
		String command=Rpath+" --arch x64 BATCH --workspace="+Rdata+" --no-init-file --no-save -q --no-echo -e \"path=\\\""+spathconv+"\\\"; print(aj.getNamesFromPaths(path)); aj.parseExpName(aj.getNamesFromPaths(path))\"";
		String result="";
		Runtime r=Runtime.getRuntime();
		try {
			Process p= r.exec(command);
			p.waitFor();
			BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String line = "";
			while ((line = b.readLine()) != null) {
				result+=line+"\n";
			}
			b.close();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		IJ.log("R output:\n"+result);
		String[] mdinfo=new String[]{title,"CSD","","pin","","","","","",""};
		if(!result.contentEquals("")) {
			String[] lines=result.split("\n");
			if(lines[0].startsWith("[1] \""))mdinfo[0]=lines[0].split("\"")[1];
			if(lines.length>2) {
				lines[2]=lines[2].replaceAll("\\s+"," ");
				String[] bits=lines[2].split(" ");
				for(int i=1;i<bits.length && i<mdinfo.length;i++) {
					if(bits[i].contentEquals("NA"))continue;
					if(bits[i].contentEquals("<NA>"))continue;
					mdinfo[i]=bits[i];
				}
			}
		}

		GenericDialog gd=new GenericDialog("Save MDInfo");
		String path=spath;
		String ccr2frms="None";
		gd.addMessage("Path = "+spath);
		gd.addStringField("Name", mdinfo[0], 30);
		gd.addStringField("Drug", mdinfo[1], 30);
		gd.addStringField("Genotype", mdinfo[2], 30);
		gd.addStringField("CsdType", mdinfo[3], 30);
		gd.addStringField("Age", mdinfo[4], 30);
		gd.addStringField("Sex", mdinfo[5], 30);
		gd.addStringField("Dose", mdinfo[6], 30);
		gd.addStringField("Year", mdinfo[7], 30);
		gd.addStringField("Mouse", mdinfo[8], 30);
		gd.addStringField("Weight", mdinfo[9], 30);
		gd.addStringField("CCR2 Frames", ccr2frms, 30);
		gd.showDialog();
		if(gd.wasCanceled())return;
		mdinfo[0]=gd.getNextString();
		mdinfo[1]=gd.getNextString();
		mdinfo[2]=gd.getNextString();
		mdinfo[3]=gd.getNextString();
		mdinfo[4]=gd.getNextString();
		mdinfo[5]=gd.getNextString();
		mdinfo[6]=gd.getNextString();
		mdinfo[7]=gd.getNextString();
		mdinfo[8]=gd.getNextString();
		mdinfo[9]=gd.getNextString();
		ccr2frms=gd.getNextString();
		for(int i=0;i<mdinfo.length;i++) {
			if(mdinfo[i].contentEquals("")){
				mdinfo[i]="NA";
				if(i==4 || i==7 || i==9)mdinfo[i]="<NA>";
			}
		}
		path=path+"MDInfo-"+mdinfo[0]+".txt";
		try {
			FileWriter fw=new FileWriter(path);
			fw.append("\"\",\"Drug\",\"Genotype\",\"CsdType\",\"Age\",\"Sex\",\"Dose\",\"Year\",\"Mouse\",\"Weight\",\"CCR2Frames\""+"\n");
			fw.append("\"1\",\""+mdinfo[1]+"\",\""+mdinfo[2]+"\",\""+mdinfo[3]+"\","+mdinfo[4]+",\""+mdinfo[5]+"\",\""+mdinfo[6]+"\","+mdinfo[7]+","+mdinfo[8]+","+mdinfo[9]+",\""+ccr2frms+"\"\n");
			fw.close();
			IJ.showStatus("MDInfo saved to "+path);
		} catch (Exception e) {
			e.printStackTrace();
			IJ.showStatus("Error saving MDInfo: "+e.getMessage());
		}
	}

	private void setPointIndex(int i) {
		pointIndex=i;
		curX=-1; curY=-1;
		tctpanel.setTextLine(TctLines.CURRENTPOINT, "Point:"+(pointIndex+1)+" x:"+curX+" y:"+curY+" z:"+simp.getZ()+" fr:"+simp.getT());
		tctpanel.resetWP();
	}

	private void updateCurWands(boolean updateThresh, boolean justIfNotProbable, final boolean forceCurrentFrame) {
		final boolean checkZmaxf=this.checkZmax;
		final int frame=simp.getFrame();
		final int ch=simp.getChannel();
		final int sl=simp.getSlice();
		if(DEBUG)log("updateCurWands called with positions f:"+frame+" c:"+ch+" z:"+sl+" updateThresh:"+updateThresh+" justIfNotProbable:"+justIfNotProbable+" forceCurrentFrame:"+forceCurrentFrame);

		if(updateCurWandThreads!=null) {
			shouldStop.set(true);
			try {
				for(Thread t : updateCurWandThreads){
					if(t!=null) t.join();
				}
			} catch (InterruptedException e) {
				IJ.log(e.getMessage());
			}
		}
		shouldStop.set(false);

		int ats=Math.max(1, Runtime.getRuntime().availableProcessors()-3);
		ats=Math.min(ats, simp.getNFrames());
		int frmspp=(int)Math.ceil((double)simp.getNFrames()/ats);
		if(frmspp>1){
			ats=(int)Math.ceil((double)simp.getNFrames()/frmspp);
		}
		updateCurWandThreads=new Thread[ats+1];
		for(int i=0; i<ats; i++) {
			final int start=i*frmspp;
			final int end=Math.min(simp.getNFrames(), (i+1)*frmspp);
			if((start+1)==frame && end==frame)continue;
			updateCurWandThreads[i+1]=new Thread(new Runnable() {
				public void run() {
					for(int j=start; j<end; j++) {
						if(shouldStop.get()) {
							if(DEBUG)log("broke updateCurWand thread "+(start+1)+"-"+end);
							return;
						}
						if(j==(frame-1))continue;
						if(DEBUG)log("updatingCurWand"+(j+1)+" on thread "+(start+1)+"-"+end);
						if(updateThresh) {
							if(curWand[j]!=null) curWand[j].setThresh(pointIndex,xys.get(pointIndex).thresh);
							continue;
						}
						if(curWand[j]==null) {
							curWand[j]=new AutoWandRoi(curWand[frame-1], checkZmaxf, ch, sl, j+1);
						}else{
							if(!curWand[j].accepted && (!curWand[j].probable || !justIfNotProbable)){
								curWand[j].setSliceChannel(sl, ch);
								curWand[j].resetPoints();
								curWand[j].updateWandRoi();
							}
						}
						//IJ.log("curWand"+j+" "+curWand[j]);
					}
				}
			});
			//updateCurWandThreads[i].start();
		}
		
		updateCurWandThreads[0]=new Thread(new Runnable() {
			public void run() {
				if(DEBUG)log("updatingCurWand"+(frame)+" on thread 0");
				if(updateThresh && curWand[frame-1]!=null) {
					curWand[frame-1].setThresh(pointIndex,xys.get(pointIndex).thresh);
				}else{
					if(curWand[frame-1]==null){
						curWand[frame-1]=new AutoWandRoi(null, false, ch, sl, frame);
					}else{
						if(forceCurrentFrame) {
							curWand[frame-1].setSliceChannel(sl, ch);
							curWand[frame-1].resetPoints();
							curWand[frame-1].updateWandRoi();
							curWand[frame-1].probable=true;
						}
					}
				}
				if(frame==simp.getT()) {
					curWand[frame-1].showBoth();
				}
				if(shouldStop.get())return;
				for(int i=1; i<updateCurWandThreads.length; i++) {
					if(updateCurWandThreads[i]!=null) updateCurWandThreads[i].start();
				}
			}
		});
		updateCurWandThreads[0].start();
		if(inThreadCurwand.get()) {
			try{
				updateCurWandThreads[0].join();
			}catch(InterruptedException e) {
				IJ.log(e.getMessage());
			}
		}

	}
	
	private void log(String string) {
		java.awt.EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				IJ.log(string);
			}
		});
	}

	
	private AutoWandRoi getLastAWR(int frame) {
		AutoWandRoi prevCw=null;
		for(int i=((frame>1)?(frame-2):0);i>=0;i--) {
			if(curWand[i]!=null && curWand[i].probable) {prevCw=curWand[i]; break;}
		}
		if(prevCw==null){
			for(int i=frame;i<simp.getNFrames();i++) {
				if(curWand[i]!=null && curWand[i].probable) {prevCw=curWand[i]; break;}
			}
		}
		return prevCw;
	}
	

	private void autoAcceptAll() {
		for(int i=0;i<simp.getNFrames();i++) {
			AutoWandRoi cw=curWand[i];
			if(cw.probable && !cw.accepted) {
				cw.accept();
				postFirstAccept[0]=true;
			}
		}
		goToNext(1);
	}

	private void goToNext(int start) {
		for(int i=(start-1);i<simp.getNFrames();i++) {
			if(curWand[i]==null || !curWand[i].accepted) {simp.setPosition(simp.getC(), simp.getZ(), i+1);return;}
		}
		for(int i=0;i<start;i++) {
			if(curWand[i]==null || !curWand[i].accepted) {simp.setPosition(simp.getC(), simp.getZ(), i+1);return;}
		}
		int fr=simp.getT()+1;
		if(fr>simp.getNFrames())fr=1;
		simp.setPosition(simp.getC(), simp.getZ(), fr);
	}

	public void finish(){
		done=true;
	}

	public void addDirectRoi() {
		GenericDialog gd=new GenericDialog("New Direct Roi");
		gd.addChoice("New Roi should be:", DirectRoiTypes.getStrings(), DirectRoiTypes.SUBTRACTIVE.getString());
		gd.showDialog();
		if(gd.wasCanceled()) {
			addDirectRoi=false;
			return;
		}
		int choice=gd.getNextChoiceIndex();
		DirectRoiTypes rtype=DirectRoiTypes.values()[choice];
		
		beginDirectRoi(rtype);
		WaitForUserDialog wd=new WaitForUserDialog("Add Direct Roi", "Draw Direct Roi then hit Enter");
		wd.show();
		Roi addRoi=simp.getRoi();
		if(addRoi==null) {
			IJ.log("No Roi detected, no direct Roi added");
		}
		completeDirectRoi(rtype,xys.get(pointIndex).getDirectRoiSize(),addRoi, false);
		Roi.setColor(DEF_ROI_COLOR);
	}
	
	private void beginDirectRoi(DirectRoiTypes rtype) {
		Overlay temp=new Overlay();
		Roi curRoi=simp.getRoi();
		if(curRoi!=null) {
			curRoi.setStrokeColor(addColor);
			temp.add(curRoi);
		}
		simp.deleteRoi();
		simp.setOverlay(temp);
		//Color def=Roi.getColor();
		Roi.setColor((rtype==DirectRoiTypes.SUBTRACTIVE)?subColor:((rtype==DirectRoiTypes.ADDITIVE)?addColor:onlyColor));
		if(DEBUG)IJ.log("Beginning Direct Roi add");
	}
	
	private void completeDirectRoi(DirectRoiTypes rtype, int index, Roi addRoi, boolean deleteIfNull) {
		Roi.setColor(DEF_ROI_COLOR);
		acceptedROI.set(false);
		simp.deleteRoi();
		Overlay temp=simp.getOverlay();
		Roi curRoi=temp.get(0);
		simp.setOverlay(soverlay);
		
		int fr=simp.getT()-1;
		if(addRoi==null) {
			if(curRoi!=null)simp.setRoi(curRoi);
			else simp.deleteRoi();
			if(deleteIfNull)deleteDirectRoi(index);
			if(curWand[fr]!=null)curWand[fr].showBoth();
		}else {
			addDirectRoi(rtype, index, addRoi);
		}
		addDirectRoi=false;
		if(DEBUG)IJ.log("Complete Direct Roi add");
	}
	
	public void addDirectRoi(DirectRoiTypes rtype, int index, Roi addRoi) {
		if(addRoi==null)return;
		addRoi.setName(rtype.getString());
		Roi oldroi=null;
		ArrayList<Roi> directRois=xys.get(pointIndex).directRois;
		if(directRois==null) {
			directRois=new ArrayList<Roi>();
			xys.get(pointIndex).directRois=directRois;
		}
		if(index>=xys.get(pointIndex).getDirectRoiSize() || index<0)xys.get(pointIndex).addDirectRoi(addRoi);
		else {
			if(rtype!=DirectRoiTypes.ONLY_WITHIN && directRois.get(index).getName().equals(DirectRoiTypes.ONLY_WITHIN.getString())) {
				directRois.add(addRoi);
			}else {
				oldroi=directRois.set(index, addRoi);
			}
		}
		addRoiToOverlay(addRoi, oldroi, 0, 0, 0);
		
		int fr=simp.getT()-1;
		if(curWand[fr]!=null){
			curWand[fr].updateWandRoi();
			curWand[fr].showBoth();
			updateCurWands(false, false, false);
		}
	}

	/*
	public void deleteDirectRoi() {
		deleteDirectRoi=false;
		int drn=directRois.size();
		if(drn==0)return;
		int ind=drn-1;
		//Roi roi=directRois.get(ind);
		//if("sub".equals(roi.getName()))roi.setStrokeColor(subColor);
		//simp.setRoi(roi);
		
		if(drn>1) {
			Roi curRoi=simp.getRoi();
			String[] items=new String[directRois.size()];
			for(int i=0;i<items.length;i++)items[i]=""+(i+1);
			GenericDialog gd=new GenericDialog("Delete Direct Roi");
			gd.addChoice("Which Direct Roi to delete?",items, items[items.length-1]);
			Choice choice=(Choice)gd.getChoices().get(0);
			choice.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					Roi roi=directRois.get(choice.getSelectedIndex());
					if("sub".equals(roi.getName()))roi.setStrokeColor(subColor);
					simp.setRoi(roi);
					simp.updateAndDraw();
				}
			});
			gd.showDialog();
			if(curRoi!=null)simp.setRoi(curRoi);
			if(gd.wasCanceled()) {
				deleteDirectRoi=false;
				return;
			}else {
				ind=gd.getNextChoiceIndex();
			}
		}
		deleteDirectRoi(ind);
	}
	*/

	public void deleteDirectRoi(){
		ArrayList<Roi> drs=xys.get(pointIndex).directRois;
		if(drs!=null && drs.size()>0){
			int lasti=drs.size()-1;
			deleteDirectRoi(lasti);
		}
	}
	
	public void deleteDirectRoi(int ind) {
		ArrayList<Roi> directRois=xys.get(pointIndex).directRois;
		if(directRois==null || directRois.size()==0) {
			IJ.showStatus("There are no Direct Rois to delete");
			return;
		}
		if(ind>=directRois.size() || ind<0) {
			IJ.showStatus("Bad index for Direct Roi deletion");
			return;
		}
		soverlay.remove(directRois.get(ind));
		directRois.remove(ind);
		int fr=simp.getT()-1;
		if(curWand[fr]!=null){
			curWand[fr].updateWandRoi();
			curWand[fr].showBoth();
		}
		updateCurWands(false, false, false);
	}
	
	public void editRoi() {
		editRoi=false;
		NonBlockingGenericDialog gd=new NonBlockingGenericDialog("Which Roi");
		gd.addNumericField("Which Roi to edit (Label="+cellLabel+")?", celln-1, 0);
		gd.showDialog();
		if(gd.wasCanceled())return;
		int cell=(int)gd.getNextNumber();
		//YesNoCancelDialog ync=new YesNoCancelDialog(null, "Really?","This will delete any progress on the current cell, ok?");
		//if(!ync.yesPressed())return;
		editRoi(cell);
	}

	private void editRoi(int cell){
		int index=results.getLineIndex(cellLabel, cell, 1);
		if(index==-1) {
			IJ.showMessage("Error","No saved data for "+cellLabel+" cell "+cell);
			editing=false;
			return;
		}
		celln=cell;
		editing=true;
		curWand=new AutoWandRoi[simp.getNFrames()];
		Calibration cal=simp.getCalibration();
		int zcur1=0;
		for(int i=0;i<simp.getNFrames();i++) {
			index = results.getLineIndex(cellLabel, cell, i+1);
			int zcur=(int)results.rt.getValueAsDouble(HEADINGS.SLICE.getIndex(), index);
			zcur=Math.max(zcur,1);
			Roi roi=getDrawnRoi(cellLabel, cell, i+1);
			double x=(int)results.rt.getValueAsDouble(HEADINGS.X.getIndex(), index), 
				   y=(int)results.rt.getValueAsDouble(HEADINGS.Y.getIndex(), index);
			if(x>0 && y>0) {
				Point centroid=new Point((int)(x/cal.pixelWidth),(int)(y/cal.pixelHeight));
				curWand[i]=new AutoWandRoi(roi, results.rt.getValueAsDouble(HEADINGS.THRESH.getIndex(), index), centroid, simp.getC(), zcur, i+1);
				if(i==0)zcur1=zcur;
				if(roi!=null) {
					ImageProcessor tip=timp.getStack().getProcessor(timp.getStackIndex(1, labelsl, i+1));
					tip.setColor(Color.BLACK);
					tip.fill(roi);
					tip=timp.getStack().getProcessor(timp.getStackIndex(2, labelsl, i+1));
					tip.setColor(Color.BLACK);
					tip.fill(new Roi(centroid.x-5,centroid.y-22,25,20));
				}
				setSrcRectAtPoint(centroid);
			}
		}
		simp.setPosition(simp.getC(),zcur1,1);
		timp.setPosition(timp.getC(),timp.getZ(),1);
		if(curWand[0]!=null)curWand[0].showBoth();
		tctpanel.setTextLine(TctLines.CURRENTCELL, "Editing "+cellLabel+" Cell: "+celln);
	}

	private void setSrcRectAtPoint(Point p) {
		if(p==null)return;
		Rectangle sr=simp.getCanvas().getSrcRect();
		if(sr.x!=0 || sr.y!=0 || sr.width!=simp.getWidth() || sr.height!=simp.getHeight()) {
			sr.x=Math.max(0,p.x-(sr.width/2)); sr.y=Math.max(0,p.y-(sr.height/2));
			simp.getCanvas().setSourceRect(sr);
			simp.updateAndDraw();
			timp.getCanvas().setSourceRect(sr);
			timp.updateAndDraw();
			if(ajtctcpimp!=null) {
				ajtctcpimp.getCanvas().setSourceRect(sr);
				ajtctcpimp.updateAndDraw();
			}
		}
	}

	public static Roi selectCell(int cell){
		ImagePlus[] imps=getSourceAndTarget();
		return selectCell(cell, imps[0], imps[1]);
	}

	public static Roi selectCell(int cell, ImagePlus simp, ImagePlus timp){
		if(simp==null || timp==null)return null;
		ImageProcessor tip=timp.getProcessor();
		Roi roi=getDrawnRoi(tip, cell);
		String[] text=timp.getInfoProperty().split("\n");
		int frame=timp.getFrame();
		if(roi!=null) {
			timp.setRoi(roi);
			simp.setPosition(simp.getNChannels()>=greench?greench:1, getZforCellFrame(text, simp.getNFrames(), timp.getStack().getSliceLabel(timp.getCurrentSlice()).split("\n")[0], cell, frame), frame);
			simp.setRoi(roi);
			simp.updateAndDraw();
		}
		return roi;
	}

	//public static int[] getCellZLine(ImagePlus imp, int ch, Roi roi, ImageProcessor tip){
	//	return getCellZLine(imp, ch, imp.getT(), roi, tip);
	//}

	public int[] getCellZLine(ImagePlus imp, int ch, int frame, Roi roi){
		return getCellZLine(imp, ch, frame, roi, null, 1, imp.getNSlices());
	}

	public int[] getCellZLine(ImagePlus imp, int ch, int frame, Roi roi, ImageProcessor tip, int zst, int zend){
		if(imp==null)return null;
		if(roi==null)return null;
		int sls=imp.getNSlices();
		int[] zvs=new int[sls];
		for(int z=zst;z<=zend;z++) {
			if(shouldStop.get())return null;
			ImageProcessor ip=imp.getStack().getProcessor(imp.getStackIndex(ch, z, frame));
			int yres=0;
			for(int x=0;x<roi.getBounds().width;x++) {
				int xres=0;
				int ipx=x+roi.getBounds().x;
				int n=0;
				for(int y=roi.getBounds().y;y<roi.getBounds().y+roi.getBounds().height;y++) {
					if(roi.contains(ipx, y)) {
						xres+=ip.get(ipx, y);
						n++;
					}
				}
				yres+=n>0?xres/n:0;
				if(tip!=null && x<tip.getWidth() && z<=tip.getHeight())tip.set(x,(z-1), n>0?xres/n:0);
			}
			zvs[z-1]=yres/roi.getBounds().width;
		}
		return zvs;
	}

	public static final int MIN_PROMINENCE=5;

	public static int[] getLocalMaxima(int[] zvs){
		if(zvs==null)return null;
		ArrayList<Integer> maxList=new ArrayList<Integer>();
		for(int i=1;i<zvs.length-1;i++) {
			int prev=zvs[i-1];
			int next=zvs[i+1];
			int prominence=zvs[i]-Math.max(prev, next);
			if(prominence>=MIN_PROMINENCE) maxList.add(i);
			if(prominence==0){
				int plateauWidth=1;
				while(i+plateauWidth<zvs.length && zvs[i+plateauWidth]==zvs[i])plateauWidth++;
				if(i+plateauWidth<zvs.length) {
					prominence=zvs[i]-Math.max(prev, zvs[i+plateauWidth]);
					if(prominence>=MIN_PROMINENCE) {
						maxList.add(i+(plateauWidth/2));
						i+=plateauWidth-1;
					}
				}
			}
		}
		if(maxList.size()==0){
			int maxIndex=0;
			for(int i=1;i<zvs.length;i++) {
				if(zvs[i]>zvs[maxIndex])maxIndex=i;
			}
			maxList.add(maxIndex);
		}
		int[] maxs=new int[maxList.size()];
		for(int i=0;i<maxs.length;i++)maxs[i]=maxList.get(i);
		return maxs;
	}

	public int getDuraCellZ(ImagePlus imp, Roi roi){
		if(roi==null)roi=imp.getRoi();
		int[] zvs=getCellZLine(imp, imp.getC(), imp.getT(), roi);
		int[] maxs=getLocalMaxima(zvs);
		return (maxs[0]+1);
	}

	public int getClosestMaxZ(){
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null || imp.getRoi()==null)return -1;
		return getClosestMaxZ(imp, imp.getRoi());
	}

	public int getClosestMaxZ(ImagePlus imp, Roi roi){
		if(imp==null || imp.getRoi()==null)return -1;
		if(roi==null)roi=imp.getRoi();
		return getClosestMaxZ(imp, roi, imp.getC(), imp.getZ(), imp.getT());
	}

	public int getClosestMaxZ(ImagePlus imp, Roi roi, int channel, int slice, int frame){
		if(imp==null)return -1;
		if(roi==null)return -1;
		int[] zvs=getCellZLine(imp, channel, frame, roi);
		if(zvs==null)return -1;
		int[] maxs=getLocalMaxima(zvs);
		if(maxs==null || maxs.length==0)return -1;
		int zcur=slice-1; // Adjust for 0-based indexing
		int closestMax=-1;
		int closestDist=Integer.MAX_VALUE;
		for(int max:maxs) {
			int dist=Math.abs(max-zcur);
			if(dist<closestDist) {
				closestDist=dist;
				closestMax=max;
			}
		}
		return closestMax+1;
	}
	
	private Roi getDrawnRoi(String label, int cell, int frame) {
		if(!cellLabels.contains(label)) {IJ.log("getDrawnRoi error invalid label");return null;}
		int slice=cellLabels.indexOf(label)+1;
		ImageProcessor tip=timp.getStack().getProcessor(timp.getStackIndex(1, slice, frame));
		Roi result = getDrawnRoi(tip, cell);
		if(result!=null)result.setPosition(1, slice, frame);
		return result;
	}

	public static Roi getDrawnRoi(ImageProcessor tip, int cell){
		tip.setThreshold(cell, cell);
		ThresholdToSelection tts=new ThresholdToSelection();
		Roi result=tts.convert(tip);
		return result;
	}

	public static int getZforCellFrame(String[] text, int frms, String elabel, int cell, int frame) {
		int stindex=0;
		if(!text[0].startsWith(elabel))while(stindex<text.length && !text[stindex].startsWith(elabel))stindex++;
		if(stindex==text.length)return -1;
		String parts[]=text[stindex+(cell-1)*frms+(frame-1)].split("\t");
		return AJ_Utils.parseIntTP(parts[HEADINGS.SLICE.getIndex()]);
	}

	private void showAllRois(){
		if(showAllRoi!=null) soverlay.remove(showAllRoi);
		ImageProcessor tip=timp.getStack().getProcessor(timp.getStackIndex(1, labelsl, 1));
		showAllRoi=getAllRois(tip);
		if(ajtctcpimpAllroi!=null){
			soverlay.remove(ajtctcpimpAllroi);
			soverlay.add(ajtctcpimpAllroi);
		}
		if(showAllRoi!=null){
			showAllRoi.setStrokeColor(Color.cyan);
			soverlay.add(showAllRoi);
			simp.updateAndDraw();
		}
	}

	private Roi getAllRois(ImageProcessor ip){
		ip.setThreshold(1, ip.getBitDepth()==8?255:65535);
		ThresholdToSelection tts=new ThresholdToSelection();
		Roi result=tts.convert(ip);
		ip.resetThreshold();
		return result;
	}

	public static void masksToRois(){
		ArrayList<Roi> rois=masksToRois(null);
		if(rois==null || rois.isEmpty())return;
		RoiManager rm=RoiManager.getRoiManager();
		for(Roi roi:rois){
			if(roi!=null)rm.addRoi(roi);
		}
	}

	public static ArrayList<Roi> masksToRois(ImageProcessor ip){
		return masksToRois(ip, false, new ArrayList<Roi>(), 1);
	}

	public static ArrayList<Roi> masksToRois(ImageProcessor ip, boolean inBackground, final ArrayList<Roi> rois, int start){
		if(ip==null){
			ImagePlus imp=WindowManager.getCurrentImage();
			if(imp==null)return null;
			ip=imp.getProcessor();
		}
		if(ip==null)return null;
		if(rois==null){
			IJ.error("Must supple an ArrayList<Roi> to store results");
			return null;
		}
		int max=0;
		for(int y=0;y<ip.getHeight();y++){
			for(int x=0;x<ip.getWidth();x++){
				int v=ip.get(x, y);
				if(v>max)max=v;
			}
		}
		for(int i=start;i<=max;i++){
			ip.setThreshold(i, i);
			ThresholdToSelection tts=new ThresholdToSelection();
			Roi roi=tts.convert(ip);
			if(roi!=null){
				rois.add(roi);
				start=i+1;
				break;
			}
		}
		final int startf=start+1;
		final int maxf=max;
		final ImageProcessor fip=ip;
		Thread thread=new Thread(new Runnable() {
			public void run() {
				for(int i=startf;i<=maxf;i++){
					fip.setThreshold(i, i);
					ThresholdToSelection tts=new ThresholdToSelection();
					Roi roi=tts.convert(fip);
					if(roi!=null)rois.add(roi);
					IJ.showProgress(((double)i+1.0)/(double)maxf);
				}
				fip.resetThreshold();
				fip.setLut(LUT_GLASBEY_INV);
				fip.setMinAndMax(0, maxf);
			}
		});
		thread.start();
		if(!inBackground) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		return rois;
	}

	public synchronized void mouseWheelMoved(MouseWheelEvent e) {
		if(addDirectRoi)return;
		int rotation = e.getWheelRotation();
		//int amount = e.getScrollAmount();
		boolean ctrl = (e.getModifiersEx() & MouseEvent.CTRL_DOWN_MASK)!=0;

		// horizontal scroll wheel on mice will deliver a scrollwheel event with the shift mask
		boolean isHorizontalScroll=((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK)!=0);
		if(isHorizontalScroll) {
			if(simp.getNFrames()>1) IJ.setKeyDown(KeyEvent.VK_CONTROL);
			if (rotation>0)
				IJ.run(simp, "Next Slice [>]", "");
			else if (rotation<0)
				IJ.run(simp, "Previous Slice [<]", "");
			if(simp.getNFrames()>1) IJ.setKeyUp(KeyEvent.VK_CONTROL);
			return;
		}

		if (!ctrl) {
			changeThresh(rotation);
		} else{
			simp.getWindow().mouseWheelMoved(e);
		}
	}
	
	private void changeThresh(int upOrDown) {
		if(upOrDown==0)return;
		upOrDown=upOrDown/Math.abs(upOrDown);
		if(pointIndex>=xys.size()){
			double cthresh=250;
			if(pointIndex>1)cthresh=xys.get(pointIndex-1).thresh;
			xys.add(new WandPoint(null, cthresh, false));
		}
		double cthresh=xys.get(pointIndex).thresh;
		//cthresh=cthresh*(threshMultiplier==null?1.0:threshMultiplier[simp.getT()-1]);
		if(cthresh<20)wheelfactori=0;
		if(wheelfactori==0 && cthresh==20)wheelfactori=1;
		cthresh+=(upOrDown*WHEELFACTORS[wheelfactori]);
		if(cthresh<1.0)cthresh=1.0;
		xys.get(pointIndex).thresh=cthresh;
		//double cthreshm=cthresh*(threshMultiplier==null?1.0:threshMultiplier[simp.getT()-1]);
		//tctpanel.setTextLine(TctLines.THRESH, "Thresh: "+cthreshm+(threshMultiplier==null?"":" ("+cthresh+"x"+threshMultiplier[simp.getT()-1]+")"));
		ImageProcessor sip=simp.getProcessor();
		sip.setThreshold(cthresh, (double) 65535, MYLUT);
		if(!simp.isHyperStack())simp.updateAndDraw();
		threshChanged.set(true);
	}

	public void actionPerformed(ActionEvent event){ 
		if(DEBUG)IJ.log("Button pressed"); 
	} 

	public void mousePressed(MouseEvent e) { 
		if(IJ.spaceBarDown() || addDirectRoi) return;
		// || e.getButton()==MouseEvent.BUTTON2
		if((IJ.altKeyDown() || IJ.shiftKeyDown() && e.getButton()==MouseEvent.BUTTON1)) {
			if(IJ.altKeyDown() )altWasDown.set(true);
			if(IJ.shiftKeyDown())shiftWasDown.set(true);
			IJ.setKeyUp(KeyEvent.VK_ALT);
			IJ.setKeyUp(KeyEvent.VK_SHIFT);
			DirectRoiTypes rtype=DirectRoiTypes.ADDITIVE;
			// || e.getButton()==MouseEvent.BUTTON2
			if(shiftWasDown.get() && altWasDown.get())rtype=DirectRoiTypes.ONLY_WITHIN;
			else if(altWasDown.get())rtype=DirectRoiTypes.SUBTRACTIVE;
			beginDirectRoi(rtype);
			MouseEvent newe=new MouseEvent(simp.getCanvas(), e.getID(), e.getWhen(), e.getModifiersEx() & ~(InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK), e.getX(), e.getY(), e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), false, e.getButton());
			simp.getCanvas().mousePressed(newe);
			e.consume();
			return;
		}
		int flags = e.getModifiersEx();
		if(DEBUG) IJ.log("flags: "+flags+" Mask1: "+InputEvent.BUTTON1_DOWN_MASK+" Mask2: "+InputEvent.BUTTON2_DOWN_MASK+" Mask3: "+InputEvent.BUTTON3_DOWN_MASK);
		if(DEBUG) IJ.log("buttonPressed: "+(((flags&InputEvent.BUTTON1_DOWN_MASK)!=0)?"1":"")+(((flags&InputEvent.BUTTON2_DOWN_MASK)!=0)?"2":"")+(((flags&InputEvent.BUTTON3_DOWN_MASK)!=0)?"3":""));
		if((flags & InputEvent.BUTTON1_DOWN_MASK) !=0){
			Point xy = simp.getCanvas().getCursorLoc(); //because x,y position isn't exactly x,y in image
			curX=xy.x; curY=xy.y;
			if(DEBUG) IJ.log("Position: "+xy.x+" "+xy.y);
			buttonpress[0]=true;
		}
		if((flags & InputEvent.BUTTON2_DOWN_MASK) !=0) {
			buttonpress[1]=true;
			simp.resetRoi();
			timp.resetRoi();
			if(importingFromAllRois) {
				acceptedROI.set(true);
			}
		}
		if((flags & InputEvent.BUTTON3_DOWN_MASK) !=0) {buttonpress[2]=true; acceptedROI.set(true);}
	}
	
	public void mouseDragged(MouseEvent e) {
		// || e.getButton()==MouseEvent.BUTTON2
		if((altWasDown.get() || shiftWasDown.get() && e.getButton()==MouseEvent.BUTTON1)) {
			MouseEvent newe=new MouseEvent(simp.getCanvas(), e.getID(), e.getWhen(), e.getModifiersEx() & ~(InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK), e.getX(), e.getY(), e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), false, e.getButton());
			simp.getCanvas().mouseDragged(newe);
			e.consume();
		}
	}
	
	public void mouseReleased(MouseEvent e) {
		if(addDirectRoi) return;
		/**
		if(IJ.altKeyDown() || IJ.shiftKeyDown()) {
			int fr=simp.getT();
			if(curWand[fr-1]!=null && simp.getRoi()!=null) {
				Roi roi=simp.getRoi();
				//roi=ij.plugin.RoiEnlarger.enlarge(ij.plugin.RoiEnlarger.enlarge(roi,3),-3);
				curWand[fr-1].setRoi(roi);
				curWand[fr-1].showBoth();
			}
			return;
		}
		**/
		// || e.getButton()==MouseEvent.BUTTON2
		if(altWasDown.get() || shiftWasDown.get() && e.getButton()==MouseEvent.BUTTON1) {
			if(DEBUG) IJ.log("alt or shift release");
			MouseEvent newe=new MouseEvent(simp.getCanvas(), e.getID(), e.getWhen(), e.getModifiersEx() & ~(InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK), e.getX(), e.getY(), e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), false, e.getButton());
			simp.getCanvas().mouseReleased(newe);
			Roi roi=simp.getRoi();
			if(xys.size()<=pointIndex || xys.get(pointIndex)==null) {
				xys.add(new WandPoint(null, 0, false));
				if(DEBUG) IJ.log("added new wand point to xys for direct roi");
			}
			int index=xys.get(pointIndex).getDirectRoiSize()-1;
			if(index<0)index=0;
			DirectRoiTypes rtype=DirectRoiTypes.ADDITIVE;
			// || e.getButton()==MouseEvent.BUTTON2
			if(shiftWasDown.get() && altWasDown.get())rtype=DirectRoiTypes.ONLY_WITHIN;
			else if(altWasDown.get())rtype=DirectRoiTypes.SUBTRACTIVE;
			completeDirectRoi(rtype,index,roi, true);
			altWasDown.set(false);
			shiftWasDown.set(false);
		}
		if(DEBUG) IJ.log("buttonReleased: "+e.getModifiersEx()+" button: "+e.getButton());
		if(e.getButton()==MouseEvent.BUTTON1)buttonpress[0]=false;
		if(e.getButton()==MouseEvent.BUTTON2) {
		//	IJ.run("Previous Slice [<]");
			buttonpress[1]=false;
		}
		if(e.getButton()==MouseEvent.BUTTON3) {
			buttonpress[2]=false;
			//else{acceptedROI=true;}
		}
	} 
	public void mouseExited(MouseEvent e) {} 
	public void mouseClicked(MouseEvent e) {}	
	public void mouseEntered(MouseEvent e) {} 


	public void keyPressed(KeyEvent e) {
		int keyCode = e.getKeyCode();
		//char keyChar = e.getKeyChar();
		//int flags = e.getModifiers();
		//IJ.log("keyPressed: keyCode=" + keyCode + " (" + KeyEvent.getKeyText(keyCode) + ")");
		if(keyCode==KeyEvent.VK_SPACE) { //space key
			spacepress.set(true);
		}else if(e.getKeyChar()=='z' || e.getKeyChar()=='Z') {
			goToZmax.set(true);
			e.consume();
		}else {
			IJ.getInstance().keyPressed(e);
		}
	}

	public void keyReleased(KeyEvent e) {
		int keyCode = e.getKeyCode();
		//char keyChar = e.getKeyChar();
		if(keyCode==KeyEvent.VK_SPACE) { //space key
			if(!buttonpress[0]){ //mouse button while spacebar pressed
				acceptedROI.set(true);
			}
			spacepress.set(false);
		}else if(keyCode==KeyEvent.VK_F1){
			changeLutType();
		}else if(keyCode==KeyEvent.VK_F2){
			editRoi();
		}else if(keyCode==KeyEvent.VK_F3){
			askCellLabel();
		}else if(keyCode==KeyEvent.VK_F4){
			gocellcomplete=true;
		}else if(keyCode==KeyEvent.VK_UP || keyCode==KeyEvent.VK_DOWN){
			changeWheelFactor((keyCode==KeyEvent.VK_UP)?1:-1);
			e.consume();
		}else if(keyCode==KeyEvent.VK_LEFT || keyCode==KeyEvent.VK_RIGHT){
			changeThresh((keyCode==KeyEvent.VK_RIGHT)?1:-1);
			e.consume();
		}else if(keyCode==KeyEvent.VK_ESCAPE) {
			done=true;
		}else if(keyCode==KeyEvent.VK_NUMPAD0) {
			if(xys.size()>1) {
				int npi=pointIndex+1;
				if(npi>=xys.size())npi=0;
				setPointIndex(npi);
			}
		}else if(e.getKeyChar()=='z' || e.getKeyChar()=='Z') {
			e.consume();
		}else {
			IJ.getInstance().keyReleased(e);
		}
	}

	public void keyTyped(KeyEvent e) {}

	private void changeLutType() {
		MYLUT++;
		if(MYLUT>3)MYLUT=0;
		if(MYLUT==2) {
			ImageProcessor sip=simp.getProcessor();
			sip.setMinAndMax(sip.getMin(),sip.getMax());
		}
	}

	/**
	 * upOrDown must be 1 or -1
	 * @param upOrDown
	 */
	private void changeWheelFactor(int upOrDown) {
		upOrDown=upOrDown/Math.abs(upOrDown);
		wheelfactori+=upOrDown;
		if(wheelfactori>=WHEELFACTORS.length)wheelfactori=0;
		if(wheelfactori<0)wheelfactori=WHEELFACTORS.length-1;
	}

	private static final int FUZZD=3;

	public static boolean areRoisTouching(Roi one, Roi two){
		return areRoisTouching(one, two, FUZZD);
	}
	public static boolean areRoisTouching(Roi one, Roi two, int fuzz){
		if(one==null || two==null)return false;
		Rectangle ob=one.getBounds();
		Rectangle tb=two.getBounds();

		//First, quickly rule out rois that are not close based on bounds
		boolean left,above;
		left=(ob.x+ob.width)<(tb.x+tb.width);
		above=(ob.y+ob.height)<(tb.y+tb.height);
		//IJ.log("OB: "+ob.x+","+ob.y+","+ob.width+","+ob.height+" TB: "+tb.x+","+tb.y+","+tb.width+","+tb.height+" "+(left?"Left,":"Right,")+(above?"Above":"Below"));
		if(left?((ob.x+ob.width+fuzz)<(tb.x)):((tb.x+tb.width+fuzz)<ob.x))return false;
		if(above?((ob.y+ob.height+fuzz)<(tb.y)):((tb.y+tb.height+fuzz)<ob.y))return false;
		//IJ.log("Crossed bounds");
		if(ob.width<=FUZZD || ob.height<=FUZZD || tb.width<=FUZZD || tb.height<=FUZZD)return false; //if either roi is very small

		/* In case rois are PolygonRoi instead of ShapeRoi
		Polygon onep=one.getPolygon();
		Polygon twop=two.getPolygon();
		for(int i=0;i<onep.npoints;i++) {
			int x=onep.xpoints[i]+(left?FUZZD:-FUZZD), y=onep.ypoints[i]+(above?FUZZD:-FUZZD);
			for(int j=0;j<twop.npoints;j++) {
				int xc=twop.xpoints[j],yc=twop.ypoints[j];
				//IJ.log(((i==0&&j==0)?"":"\\Update:")+"One: "+x+","+y+"  Two: "+xc+","+yc);
				if((left?(x>=xc):(xc>=x)) && (above?(y>=yc):(yc>=y)))return true;
			}
		}
		 */

		//Then check if their areas are really within FUZZD
		ShapeRoi sroi=(enlargeRoi(one,fuzz)).and(enlargeRoi(two,fuzz));
		return (sroi!=null && (sroi.getPolygon().npoints>0));
	}

	public static ShapeRoi enlargeRoi(Roi roi, int enlarge) {
		if(roi==null)return null;
		return (new ShapeRoi(RoiEnlarger.enlarge((Roi)roi.clone(),enlarge)));
	}

	/**
	 * Splits a non-contiguous ShapeRoi and returns the biggest one
	 * according to the bounding rectangle (not actual area).
	 * @param roi
	 * @return
	 */
	public static Roi biggestNGRoi(Roi roi) {
		Roi result=roi;
		if((roi instanceof ShapeRoi)) {
			Roi[] rois=((ShapeRoi)roi).getRois();
			if(rois.length>1) {
				int maxarea=0, index=-1;
				for(int i=0;i<rois.length;i++) {
					Rectangle b=rois[i].getBounds();
					int area=b.width*b.height;
					if(area>maxarea) {maxarea=area; index=i;}
				}
				result=rois[index];
			}
		}
		return result;
	}

	private void addRoiToOverlay(Roi roi, Roi oldroi, int ch, int sl, int fr) {
		if(roi==null) return;
		if(oldroi!=null && soverlay!=null)
			soverlay.remove(oldroi);
		Color strokecolor=addColor;
		if(DirectRoiTypes.SUBTRACTIVE.getString().equals(roi.getName()))strokecolor=subColor;
		else if(DirectRoiTypes.ONLY_WITHIN.getString().equals(roi.getName()))strokecolor=onlyColor;
		roi.setStrokeColor(strokecolor);
		roi.setPosition(ch,sl,fr);
		if(soverlay!=null) soverlay.add(roi);
	}

	private Roi addRoi(Roi roi, Roi addroi) {
		if(roi==null) {roi=addroi; return roi;}
		if(addroi==null) {return roi;}
		if(! (addroi instanceof ShapeRoi))addroi=new ShapeRoi(addroi);
		if(! (roi instanceof ShapeRoi))roi=new ShapeRoi(roi);
		roi=((ShapeRoi)roi).or((ShapeRoi)addroi);
		return roi;
	}
	
	private Roi andRoi(Roi roi, Roi androi) {
		if(roi==null) {return androi;}
		if(androi==null) {return roi;}
		if(! (androi instanceof ShapeRoi))androi=new ShapeRoi(androi);
		if(! (roi instanceof ShapeRoi))roi=new ShapeRoi(roi);
		roi=((ShapeRoi)roi).and((ShapeRoi)androi);
		return roi;
	}

	private Roi subtractRoi(Roi roi, Roi subroi) {
		if(roi==null)return roi;
		if(subroi==null)return roi;
		if(! (subroi instanceof ShapeRoi))subroi=new ShapeRoi(subroi);
		if(! (roi instanceof ShapeRoi))roi=new ShapeRoi(roi);
		roi=((ShapeRoi)roi).not((ShapeRoi)subroi);
		return roi;
	}

	class AutoWandRoi{
		private ImageProcessor[] mask=new ImageProcessor[MAX_POINTS];
		private Roi[] maskRoi=new Roi[MAX_POINTS];
		private int ch,sl,fr;
		private int prevsl=0;
		private Calibration cal=simp.getCalibration();
		private Roi[] rois=new Roi[MAX_POINTS];
		private ArrayList<WandPoint> wandPoints=new ArrayList<WandPoint>();

		public Roi roi;
		public Roi troi=null;
		public double area, mean, min;
		public Point centroid=new Point(0,0);
		public HashMap<HEADINGS, Object> output=new HashMap<HEADINGS, Object>();

		public boolean probable=false;
		public boolean accepted=false;
		public boolean wasClose=false;
		//public boolean maskChanged=false;

		public AutoWandRoi(Roi roi, double thresh, int channel, int slice, int frame) {
			this(roi, thresh, new Point((int)roi.getXBase(),(int)roi.getYBase()), channel, slice, frame);
		}
		
		public AutoWandRoi(Roi roi, double thresh, Point point, int channel, int slice, int frame) {
			this.roi=roi;
			this.fr=frame;
			this.ch=channel;
			this.sl=slice;
			this.prevsl=slice;
			cal=simp.getCalibration();
			fillStats();
			if(thresh<=0)thresh=min;
			if(thresh<=0)thresh=xys.get(0).thresh;
			if(thresh<=0)thresh=250;
			wandPoints.add(new WandPoint(point, thresh, false));
			updateOutput(-1);
			probable=true;
		}

		public AutoWandRoi(int channel, int slice, int frame) {
			this.fr=frame;
			this.sl=slice;
			this.prevsl=sl;
			this.ch=channel;
			resetPoints();
			updateWandRoi();
			probable=true;
		}

		public AutoWandRoi(AutoWandRoi prevAWI, boolean withZCheck, int channel, int slice, int frame) {
			this.fr=frame;
			this.sl=slice;
			this.prevsl=sl;
			this.ch=channel;
			findRoiCloseTo(prevAWI, withZCheck);
		}

		public void setSliceChannel(int slice, int channel) {
			if(this.sl==slice && this.ch==channel)return;
			this.sl=slice;
			prevsl=slice;
			this.ch=channel;
			updateMasks();
			fillStats();
			updateOutput(-1);
			wasClose=false;
		}

		private void setRoi(Roi roi){
			setRoi(roi, false);
		}

		private void setRoi(Roi roi, boolean applyDirectRois) {
			if(roi==this.roi)return;
			if(roi==null)return;
			if(applyDirectRois && xys.size()>0 && xys.get(0).getDirectRoiSize()>0) {
				for(Roi directRoi : xys.get(0).directRois) {
					if(directRoi!=null) {
						if(DirectRoiTypes.SUBTRACTIVE.getString().equals(directRoi.getName())) {
							roi=subtractRoi(roi, directRoi);
						}else if(DirectRoiTypes.ONLY_WITHIN.getString().equals(directRoi.getName())) {
							roi=andRoi(roi, directRoi);
						}else { //additive
							roi=addRoi(roi, directRoi);
						}
					}
				}
			}
			this.roi=roi;
			fillStats();
			updateOutput(-1);
		}

		public void setPrevRoi(){
			AutoWandRoi law=getLastAWR(fr);
			if(law==null) {IJ.showMessage("No previous roi to copy"); return;}
			setRoi(law.getRoi(), true);
			IJ.showStatus("Copied roi from previous frame ("+law.sl+")");
		}

		private Roi doWandRoi(int index) {
			Point p=wandPoints.get(index).point;
			double thresh=wandPoints.get(index).thresh;

			if(p==null)return null;
			int xw=p.x, yw=p.y;
			if(xw==-1 || yw==-1) return null;

			if(mask[index]==null) {
				updateMasks();
			}
			
			if(shouldStop.get())return null;
			Wand w=new Wand(mask[index]);
			w.autoOutline(xw,yw,thresh,(double)65535);
			Roi sel=new ShapeRoi(new PolygonRoi(w.xpoints, w.ypoints, w.npoints, Roi.POLYGON));

			Rectangle sbs=sel.getBounds();
			//if(sel!=null && ( (simp.getWidth()<300 || simp.getHeight()<300) || (sbs.getWidth()<(simp.getWidth()/2) && sbs.getHeight()<(simp.getHeight()/2))) && sbs.getX()>0 && sbs.getY()>0 && sbs.getWidth()>5 && sbs.getHeight()>5){
			if(sbs.getX()<0 || sbs.getY()<0) ((ShapeRoi)sel).and(new ShapeRoi(new Roi(0,0,sbs.getWidth()+sbs.getX(),sbs.getHeight()+sbs.getY())));
			if(sel!=null && sbs.getWidth()>5 && sbs.getHeight()>5) {
				sel=ij.plugin.RoiEnlarger.enlarge(ij.plugin.RoiEnlarger.enlarge(sel,3),-3);
			}
			if(xys.get(index).directRois!=null){
				for(Roi directRoi : xys.get(index).directRois) {
					if(directRoi!=null) {
						if(DirectRoiTypes.SUBTRACTIVE.getString().equals(directRoi.getName())) {
							sel=subtractRoi(sel, directRoi);
						}else if(DirectRoiTypes.ONLY_WITHIN.getString().equals(directRoi.getName())) {
							//don't need to do because it is already masked
							//sel=andRoi(sel, directRoi);
						}else { //additive
							sel=addRoi(sel, directRoi);
						}
					}
				}
			}

			return sel;
		}

		private void fillStats() {
			if(roi==null) return;
			if(roi.getBounds().width<1 || roi.getBounds().height<1) return;
			ImageProcessor ip=simp.getStack().getProcessor(simp.getStackIndex(greench, sl, fr));
			ip.setRoi(roi);
			roi.setImage(simp);
			ImageStatistics imgstat=ImageStatistics.getStatistics(ip, 127, cal);
			area=imgstat.area;
			mean=imgstat.mean;
			min=imgstat.min;
			centroid=new Point((int)(imgstat.xCentroid/cal.pixelWidth),(int)(imgstat.yCentroid/cal.pixelHeight));
		}

		private void updateOutput(int roin){
			output.put(HEADINGS.LABEL, cellLabel);
			output.put(HEADINGS.CELL, celln);
			output.put(HEADINGS.AREA, area);
			output.put(HEADINGS.X, centroid.x*cal.pixelWidth);
			output.put(HEADINGS.Y, centroid.y*cal.pixelHeight);
			output.put(HEADINGS.MEAN, mean);
			output.put(HEADINGS.SLICE, sl);
			output.put(HEADINGS.FRAME, fr);
			output.put(HEADINGS.THRESH, getThresh(0));
			output.put(HEADINGS.TIME, times[fr-1]);

			if(roin<0)return;

			double perimeter,circularity;
			roi.setImage(simp);
			perimeter=roi.getLength();
			circularity = perimeter==0.0?0.0:4.0*Math.PI*(area/(perimeter*perimeter));
			output.put(HEADINGS.ROI, roin);
			output.put(HEADINGS.PERIMETER, perimeter);
			output.put(HEADINGS.CIRCULARITY, circularity);
			output=fillLipoData(sl,fr,roi,output);
			Roi bvRoi=null;
			Roi axonRoiduraonly=null;
			if(cellLabel.contentEquals("Dura")){
				bvRoi=duraBVRoi;
				axonRoiduraonly=axonRoi;
			}
			else if(cellLabel.contentEquals("Pia"))bvRoi=piaBVRoi;
			output=fillRoiData(axonRoiduraonly, roi, output, HEADINGS.AXONDISTANCE);
			output=fillRoiData(bvRoi, roi, output, HEADINGS.BVDISTANCE);
			output.put(HEADINGS.CCR2FRAME, ""+isCCR2Frame(fr));
		}

		private void updateWandRoi(boolean withZCheck) {
			if(shouldStop.get())return;
			if(withZCheck) {
				updateWandRoiWithZCheck();
			}else {
				updateWandRoi();
			}
		}

		private void updateWandRoi(){
			//if(ch!=simp.getC() || ip==null) {
			//	ch=simp.getC();
			//	ip=simp.getStack().getProcessor(simp.getStackIndex(ch, sl, fr));
			//}
			updateWandRoi(null);
		}

		private void updateWandRoi(ImageProcessor ip) {
			if(shouldStop.get())return;
			if(wandPoints.size()==0) {
				if(xys.size()==0)return;
				resetPoints();
			}
			if(wandPoints.size()==0) return;
			updateMasks(ip);
			buildRoi();
			if(roi==null) return;
			fillStats();
			updateOutput(-1);
			if(this==curWand[simp.getT()-1]) showBoth();
		}

		private void updateWandRoiWithZCheck(){
			updateWandRoi();
			if(shouldStop.get())return;
			if(roi!=null && roi.getBounds().width>5 && roi.getBounds().height>5) {
				int zmax=getClosestMaxZ(simp, roi, ch, sl, fr);
				if(zmax>0 && zmax<=simp.getNSlices() && zmax!=sl && Math.abs(zmax-sl)<=5) {
					Roi prevroi=roi;
					sl=zmax;
					updateWandRoi();
					if(!areRoisTouching(prevroi, roi)) {
						sl=prevsl;
						setRoi(prevroi);
					}
				}
			}
		}

		private void buildRoi() {
			for(int i=0;i<wandPoints.size();i++) {
				rois[i]=doWandRoi(i);
			}
			roi=null;
			for(int i=0;i<wandPoints.size();i++) {
				if(rois[i]!=null) {
					if(wandPoints.get(i).isNeg) {rois[i].setName(DirectRoiTypes.SUBTRACTIVE.getString()); roi=subtractRoi(roi, rois[i]);}
					else roi=addRoi(roi, rois[i]);
				}
			}
		}

		public void addPoint(WandPoint wp) {
			addPoint(wp.point, wp.thresh, wp.isNeg);
		}

		public void addPoint(Point point, double thresh, boolean isNeg) {
			wandPoints.add(new WandPoint(point, thresh, isNeg));
			updateMasks();
		}

		public void setPoint(int index, Point point, double thresh) {
			if(index>=wandPoints.size()) {resetPoints();}
			if(index>=wandPoints.size()) {
				addPoint(point, thresh, false);
			} else {
				wandPoints.set(index, new WandPoint(point, thresh, wandPoints.get(index).isNeg));
			}
		}

		public void updatePoint(int index, Point point, double thresh) {
			if(index>=wandPoints.size()) {IJ.showMessage("updatePoint index out of range");return;}
			setPoint(index,point,thresh);
			updateWandRoi();
		}

		public void updatePoint() {
			if(pointIndex>=wandPoints.size()) {
				for(int i=wandPoints.size();i<=pointIndex;i++) {addPoint(xys.get(i).point, xys.get(i).thresh, xys.get(i).isNeg);}
			}else wandPoints.set(pointIndex, new WandPoint(xys.get(pointIndex).point, xys.get(pointIndex).thresh, xys.get(pointIndex).isNeg));
			while(wandPoints.size()>xys.size()) {wandPoints.remove(wandPoints.size()-1);}
			updateMasks();
			updateWandRoi();
		}

		public void deletePoint(int index) {
			if(index<wandPoints.size()) {
				wandPoints.remove(index);
			}
			maskRoi[index]=null;
			mask[index]=null;
		}

		public void updateMasks(){
			updateMasks(null);
		}

		public void updateMasks(ImageProcessor ip) {
			//if(!maskChanged && mask[0]!=null && ip==null) return;
			if(ip==null) {
				ip=simp.getStack().getProcessor(simp.getStackIndex(ch, sl, fr));
				if(DEBUG)log("Updating masks with ip for ch "+ch+" sl "+sl+" fr "+fr);
			}
			mask=new ImageProcessor[MAX_POINTS];
			maskRoi=new Roi[MAX_POINTS];
			for(int i=0;i<wandPoints.size();i++) {
				Roi tempMaskRoi=null;
				if(xys.get(i).directRois!=null) {
					ArrayList<Roi> directRois=xys.get(i).directRois;
					for(int j=0;j<directRois.size();j++) {
						Roi temp=directRois.get(j);
						if(temp!=null) {
							if(DEBUG)IJ.log("Updatemask fr"+fr+" pt"+(i+1)+" roi"+(j+1)+" "+temp.getName());
							if(DirectRoiTypes.ONLY_WITHIN.getString().equals(temp.getName())) {
								tempMaskRoi=andRoi(tempMaskRoi,temp);
								if(DEBUG)IJ.log("Updatemask updated tempMask");
							}
						}
					}
				}
				if(tempMaskRoi!=null){
					maskRoi[i]=tempMaskRoi;
					mask[i]=ip.createProcessor(ip.getWidth(), ip.getHeight());
					if(mask[i]==null) {
						IJ.log("Error creating mask["+i+"] in frame "+fr);
						continue;
					}
					Rectangle mb=maskRoi[i].getBounds();
					if(DEBUG)IJ.log("Updated "+fr+" maskRoi "+(i+1)+" to "+mb.x+","+mb.y+","+mb.width+","+mb.height);
					Point[] pts=maskRoi[i].getContainedPoints();
					for(int pi=0; pi<pts.length; pi++) {
						Point p=pts[pi];
						if(shouldStop.get()){
							mask[i]=null;
							return;
						}
						mask[i].set(p.x, p.y, ip.get(p.x, p.y));
					}
				}else{ 
					mask[i]=ip;
					//if(DEBUG)IJ.log("Updated maskRoi mask to full ip");
				}
			}
			for(int i=xys.size();i<MAX_POINTS;i++) {
				if(mask[i]==null) mask[i]=ip;
			}
		}

		public void resetPoints() {
			wandPoints=new ArrayList<WandPoint>();
			for(int i=0;i<xys.size();i++) {
				WandPoint wp=xys.get(i);
				wandPoints.add(new WandPoint(wp.point, wp.thresh, wp.isNeg));
			}
			updateMasks();
			//maskRoi=new Roi[MAX_POINTS];
			//mask=new ImageProcessor[MAX_POINTS];
		}

		public Roi getRoi() {return roi;}
		public Roi getTRoi() {return troi;}

		private double getThresh(int index) {
			double thresh=wandPoints.get(index).thresh;
			return thresh*(threshMultiplier==null?1.0:threshMultiplier[fr-1]);
		}

		private void findRoiCloseTo(AutoWandRoi prev, boolean checkZmax) {
			if(wandPoints.size()!=xys.size()) {
				resetPoints();
			}
			if(prev==null || prev.roi==null || prev.area==0){
				updateWandRoi(checkZmax);
				return;
			}
			if(wandPoints.size()==0)return;
			findRoiCloseTo(prev);
			if(checkZmax){
				if(roi!=null && roi.getBounds().width>5 && roi.getBounds().height>5 && probable) {
					int zmax=getClosestMaxZ(simp, roi, ch, sl, fr);
					if(zmax>0 && zmax<=simp.getNSlices() && zmax!=sl && Math.abs(zmax-sl)<=5) {
						Roi prevroi=roi;
						sl=zmax;
						findRoiCloseTo(prev);
						if(!probable) {
							sl=prevsl;
							setRoi(prevroi);
						}
					}
				}
			}
		}

		private void findRoiCloseTo(AutoWandRoi prev){
			probable=false;
			if(xys.get(0).point==null) return;
			if(prev.roi==null) {
				if(DEBUG)IJ.log("Previous roi is null for findRoiCloseTo");
				updateWandRoi();
				return;
			}
			Point c=new Point(xys.get(0).point.x,xys.get(0).point.y);
			ArrayList<Double> ranks=new ArrayList<Double>();
			ArrayList<Point> pts=new ArrayList<Point>();
			for(int i=0;i<10;i++) {
				if(shouldStop.get())return;
				updateWandRoi();
				if(roi!=null && !areRoisTouching(roi, prev.roi)) {
						roi=null;
				}
				if(roi!=null) {
					double distDiff=Math.hypot(((double)centroid.x*cal.pixelWidth-(double)prev.centroid.x*cal.pixelWidth),((double)centroid.y*cal.pixelHeight-(double)prev.centroid.y*cal.pixelHeight));
					double radDiff=Math.abs((Math.sqrt(area/Math.PI))-(Math.sqrt(prev.area/Math.PI)));
					double rad=(Math.sqrt(prev.area/Math.PI));
					if(distDiff<rad && radDiff<(rad/3)){
						wasClose=true;
						probable=true; 
						if(DEBUG)IJ.log("frct"+fr+": acc d"+IJ.d2s(distDiff,3)+" r"+IJ.d2s(radDiff,3)+" rad"+IJ.d2s(rad,3)+" rank"+(distDiff*3/rad+radDiff/rad));
						return;
					}
					ranks.add(distDiff*3/rad+radDiff/rad);
					pts.add((Point)c.clone());
					//if(DEBUG) {
						//if(roi!=null)IJ.log("frct:"+fr+" rej "+(Math.hypot((centroid.x-prev.centroid.x),(centroid.y-prev.centroid.y))>(Math.sqrt(prev.area/Math.PI)/cal.pixelWidth)?"too far  ":"")+
						//		((area/prev.area > (1+AREATOLERANCE))?"too big   ":"")+((area/prev.area<AREATOLERANCE)?"too small  ":""));
						//else IJ.log("frct:"+fr+" rej null roi");
					//}
				}else {

				}
				if(i==5) {c.x=prev.centroid.x; c.y=prev.centroid.y;} 
				else if(i==6 && ajtctcpimp!=null && cellLabel.equals("Dura")) {updateWandRoi(ajtctcpimp.getStack().getProcessor(ajtctcpimp.getStackIndex(1, 1, fr)));}
				else {c.x-=10; c.y-=10;}
				setPoint(0, c, xys.get(0).thresh);
				setSliceChannel(prevsl, ch);
			}
			int index=0;
			double minRank=65535.0;
			for(int i=0; i<ranks.size();i++) { if(ranks.get(i)<minRank) {minRank=ranks.get(i); index=i;}}
			if(index<pts.size())
				setPoint(0,pts.get(index), xys.get(0).thresh);
			else 
				setPoint(0,c, xys.get(0).thresh);
			if(shouldStop.get())return;
			updateWandRoi();
			wasClose=true;
			//if(DEBUG)IJ.log("findRoiCloseTo: "+attempts);
		}

		public void setThresh(int index, double thresh) {
			if(index>=wandPoints.size()) {
				resetPoints();
			}
			if(index<wandPoints.size()) {
				wandPoints.get(index).setThresh(thresh);
				updateMasks();
			}
			updateWandRoi();
		}

		public void showBoth() {
			if(roi!=null) {
				if(simp.getRoi()!=roi){
					simp.setRoi(roi);
					timp.setRoi((Roi)roi.clone());
				}
				tctpanel.setTextLine(TctLines.CURRENTPOINT, "Selection Info--  Roi changed: "+((roi.equals(curWand[fr-1].troi))?"no":"YES")+
						" Accepted: "+accepted+" Probable: "+probable+" Points: "+wandPoints.size());
				tctpanel.setTextLine(TctLines.CURRENTWAND, getInfoString());
			}
		}
		
		public void accept() {
			accept(true, results.roin);
		}

		public void accept(boolean drawTarget, int roin) {
			if(roi==null) {IJ.log("TCT Error: No Selection"); return;}
			probable=true;
			if(drawTarget) {
				Overlay tov=timp.getOverlay();
				if(tov==null) {tov=new Overlay(); tov.setFillColor(new Color(255,100,0)); timp.setOverlay(tov);}
				if(troi!=null) {
					tov.remove(troi);
					timp.deleteRoi();
				}
				troi=(Roi)roi.clone();
				troi.setImage(timp);
				troi.setPosition(1, labelsl, fr);
				troi.setFillColor(new Color(255,100,0));
				//timp.setPosition(1,labelsl,fr);
				tov.add(troi);
				timp.updateAndRepaintWindow();
			}
			fillStats();
			updateOutput(roin);
			if(tctpanel!=null)tctpanel.setTextLine(TctLines.LASTACCEPTED, getInfoString());
			accepted=true;
		}

		public String getOutputString() {
			return buildLine(output);
		}

		public String getInfoString(){
			if(output==null)return "";
			return ""+output.get(HEADINGS.LABEL)+" Cell: "+(int)output.get(HEADINGS.CELL)+" Fr: "+(int)output.get(HEADINGS.FRAME)+" Sl: "+(int)output.get(HEADINGS.SLICE)+
				" Area: "+IJ.d2s((double)output.get(HEADINGS.AREA),2)+" Mean: "+IJ.d2s((double)output.get(HEADINGS.MEAN),2)+
				" X: "+IJ.d2s((double)output.get(HEADINGS.X),2)+" Y: "+IJ.d2s((double)output.get(HEADINGS.Y),2)+
				" thresh: "+output.get(HEADINGS.THRESH);
		}
		
		public int getHeadingIndexOf(String string) {return Thresh_Cell_Transfer.HEADINGS.indexOf(string);}

		public void draw() {
			ImageStack ist=timp.getImageStack();
			ImageProcessor tip1=ist.getProcessor(timp.getStackIndex(1, labelsl, fr));
			ImageProcessor tip2=ist.getProcessor(timp.getStackIndex(2, labelsl, fr));
			if(troi!=null) {
				if(celln>255 && timp.getBitDepth()==8) {
					IJ.showMessage("Converting AJTCT to 16-bit image because > 255 cells");
					(new StackConverter(timp)).convertToGray16();
					timp.setProperty("Info", results.getText(true));
				}
				tip1.setRoi(troi);
				ImageStatistics imgstat=ImageStatistics.getStatistics(tip1, 127, timp.getCalibration());
				tip1.setColor(celln);
				tip1.fill(troi);
				tip1.setColor(0);
				tip1.draw(ij.plugin.RoiEnlarger.enlarge(troi,1));
				tip1.setColor(255);

				String cl=""+celln;
				if(drawCellLabel)cl=cl.concat(" "+cellLabel);
				if(DEBUG)IJ.log("Drawing: "+fr+" "+(imgstat.xCentroid)+" "+(imgstat.yCentroid)+"    "+cl);
				tip2.setColor(255);
				tip2.drawString(cl, (int)imgstat.xCentroid-5, (int)imgstat.yCentroid-10);
			}
		}

	}

	class WandPoint {
		Point point=null;
		double thresh;
		boolean isNeg;
		ArrayList<Roi> directRois=null;

		public WandPoint(Point point, double thresh, boolean isNeg) {
			if(point!=null)this.point=(Point)point.clone();
			this.thresh=thresh;
			this.isNeg=isNeg;
		}

		public int getX() {return point.x;}
		public int getY() {return point.y;}

		public void setPoint(Point point) {
			if(point!=null)this.point=(Point)point.clone();
			else this.point=null;
		}
		public void setThresh(double thresh) {this.thresh=thresh;}

		public void setDirectRois(ArrayList<Roi> directRois) {this.directRois=directRois;}
		public void addDirectRoi(Roi directRoi) {
			if(this.directRois==null)this.directRois=new ArrayList<Roi>();
			this.directRois.add(directRoi);
		}
		public int getDirectRoiSize() {return this.directRois==null?0:this.directRois.size();}
	}

	public void ptReslice() {
		int width=30;
		String torl="Left";
		//torl="Top";
		boolean doubleview=true;
		boolean justdrawcircles=false;
		boolean markcells=true;
		boolean full=false;
		boolean recalcZ=false;

		ImagePlus imp = WindowManager.getCurrentImage();
		String title=imp.getTitle();
		Calibration cal = imp.getCalibration();
		int w=imp.getWidth(), h=imp.getHeight(), chs=imp.getNChannels(), sls=imp.getNSlices(), frms=imp.getNFrames();
		double px=cal.pixelWidth, py=cal.pixelHeight, pz=cal.pixelDepth;

		boolean resultpts=false;
		int stcell=0, endcell=1;
		String tctitle="ThreshCellTransfer-"+title+".csv";
		Window tctwin=WindowManager.getWindow(tctitle);
		int nResults=0;
		ResultsTable rt=ResultsTable.getResultsTable("Results");
		if(rt!=null) nResults=rt.getCounter();
		if(tctwin==null) {
			tctitle="ThreshCellTransfer-"+title+".txt";
			tctwin=WindowManager.getWindow(tctitle);
		}
		if(tctwin==null) {
			Frame[] wins=WindowManager.getNonImageWindows();
			ArrayList<String> winTitles=new ArrayList<String>();
			for(int i=0;i<wins.length;i++){
				if(wins[i].getTitle().startsWith("ThreshCellTransfer-")){
					winTitles.add(wins[i].getTitle());
				}
			}
			
			if(nResults>0 && nResults%frms==0){
				winTitles.add(0, "Use Measured Results");
			}
			if(winTitles.size()>0){
				String[] winTitlesArr=new String[winTitles.size()];
				winTitlesArr=winTitles.toArray(winTitlesArr);
				GenericDialog gd=new GenericDialog("Select TCT Window");
				gd.addChoice("Get point data from: ", winTitlesArr, winTitlesArr[0]);
				gd.showDialog();
				if(gd.wasCanceled())return;
				tctitle=gd.getNextChoice();
				if(!tctitle.contentEquals("Use Measured Results"))tctwin=WindowManager.getWindow(tctitle);
				if(tctitle.contentEquals("Use Measured Results"))resultpts=true;
			}
			if(!resultpts && tctwin==null){IJ.error("Need to measure points or have TCT results"); return;}
		}

		int cellind=-1, xind=-1, yind=-1, sliceind=-1, areaind=-1, frind=-1;
		if(tctwin!=null){
			rt=ResultsTable.getResultsTable(tctitle);
			if(rt==null) return;
		}
		String[] headings=rt.getHeadings();
		for(int i=0;i<headings.length;i++){
			if(headings[i].startsWith("Cell"))cellind=i;
			if(headings[i].startsWith("X"))xind=i;
			if(headings[i].startsWith("Y"))yind=i;
			if(headings[i].startsWith("Slice"))sliceind=i;
			if(headings[i].startsWith("Area"))areaind=i;
			if(headings[i].startsWith("Frame"))frind=i;
		}
		if(!resultpts){
			if(cellind==-1 || xind==-1 || yind==-1 || areaind==-1) {IJ.error("Incorrect Header"); return;}
			if((rt.getCounter())%frms!=0){IJ.error("TCT does not match"); return;}
			endcell=(int)rt.getValueAsDouble(cellind, rt.getCounter()-1);
			int lastslice=(int)rt.getValueAsDouble(sliceind, rt.getCounter()-1);
			if(frind>-1){
				int tctfrms=(int)rt.getValueAsDouble(frind, rt.getCounter()-1);
				if(tctfrms!=frms) {
					IJ.error("TCT frame number does not match image"); return;
				}
			}
			if(lastslice>sls) sliceind=-1;
		} else{
			endcell=nResults/frms;
		}
		LUT[] luts=null;
		if(imp instanceof CompositeImage){
			CompositeImage ci=(CompositeImage)imp;
			luts=ci.getLuts();
		}
		
		int yminus=65535, shei=h, xminus=width, swid=width*2;
		if(torl=="Top"){xminus=65535; swid=w; yminus=width; shei=width*2;}
		if(!full){yminus=150; shei=300; xminus=11; swid=22;}
		int ch=imp.getC(), fr=imp.getT();
		int cf=fr-1;
		int cell=0;
		Roi roi=imp.getRoi();
		if(roi!=null && roi.getType()==0) {
			Rectangle r=roi.getBounds();
			int xb=r.x, yb=r.y;
			swid=r.width; shei=r.height;
			if(torl=="Top" && swid<shei) torl="Left";
			int xm=xb+swid/2, ym=yb+shei/2;
			int dist=65535;
			double xum, yum;
			for(int i=0;i<(endcell);i++){
				xum=rt.getValue("X",cf+(frms*i));
				yum=rt.getValue("Y",cf+(frms*i));
				int x=(int)(xum/px), y=(int)(yum/py);
				int cdist=(int)Math.sqrt(Math.pow(xm-x,2)+Math.pow(ym-y,2));
				if(cdist<dist){dist=cdist; yminus=y-yb; xminus=x-xb; cell=i;}
			}
			if(dist>20){
				YesNoCancelDialog ync=new YesNoCancelDialog(IJ.getInstance(), "TCT Cell Match", "Matched to cell "+(cell+1)+" but it is far away ("+dist+" pixels). Try again?");
				if(ync.yesPressed() || ync.cancelPressed()){return;}
			}
			IJ.log("Matched to cell "+(cell+1));
		}
		
		GenericDialog gd=new GenericDialog("Which cells");
		gd.addStringField("Do cells: ",""+1+"-"+endcell);
		gd.addCheckbox("Recalculate Z?", recalcZ);
		gd.showDialog();
		String cstr=gd.getNextString();
		recalcZ=gd.getNextBoolean();
		String[] cstrsp=cstr.split("-");
		if(cstrsp.length==1 || cstrsp.length==2){endcell=Integer.parseInt(cstrsp[cstrsp.length-1]);stcell=Integer.parseInt(cstrsp[0])-1;}
		else {IJ.error("Incorrect cell string format"); return;}

		ImagePlus endimp=null;
		double all=(endcell-stcell+1)*frms;
		for(cell=stcell;cell<endcell;cell++){
			int currentz=0;
			for(int i=0;i<frms;i++){
				IJ.showProgress(((cell-stcell)*frms+i+1)/all);
				double area=0;
				int x=0,y=0,z=0,r=0;
				boolean skip=false;
				if(rt.getStringValue("X",i+(frms*cell)).contentEquals("NA")) skip=true;
				else{
					x=(int)(rt.getValue("X",i+(frms*cell))/px);
					y=(int)(rt.getValue("Y",i+(frms*cell))/py);
					if(sliceind>-1 || resultpts) z=(int)rt.getValue("Slice",i+(frms*cell)); else z=1;
					area=rt.getValue("Area",i+(frms*cell));
				}
				r=(int)Math.sqrt((area)/Math.PI/px/py);
				if(r>x)r=x;
				if(r>y)r=y;

				int frm=i+1;
				if(justdrawcircles || skip){
					if(!skip){
						ImageProcessor ip = imp.getStack().getProcessor(imp.getStackIndex(1, z, frm));
						ip.setColor(Color.WHITE);
						ip.fill(new OvalRoi(x-r, y-r, 2*r, 2*r));
					}
				}else{
					int dv=1; if(doubleview)dv=3;
					if(recalcZ){
						if(i==0)currentz=z;
						Roi ovalroi=new OvalRoi(x-r, y-r, 2*r, 2*r);
						currentz=getClosestMaxZ(imp, ovalroi, chs>=greench?greench:1, currentz, frm);
						if(currentz<0 || currentz>sls) currentz=z;
						IJ.log("Recalculated Z for cell "+(cell+1)+" frame "+frm+": "+currentz);
						rt.setValue(sliceind, i+(frms*cell), currentz);
						rt.updateResults();
					}else currentz=z;
					for(int pass=0;pass<dv;pass++){
						int xs=0, ys=0, ws=swid, hs=shei;
						if(pass==0){
							xs=Math.min(w-swid,Math.max(0,x-xminus));
							ys=Math.min(h-shei,Math.max(0,y-yminus));
						}else if(pass==1){
							xs=Math.min(w-shei,Math.max(0,x-yminus));
							ys=Math.min(h-swid,Math.max(0,y-xminus));
							ws=shei; hs=swid;
						}else{ 
							xs=Math.min(w-shei,Math.max(0,x-yminus));
							ys=Math.min(h-shei,Math.max(0,y-yminus));
							ws=shei; hs=shei;
						}
						Roi crop = new Roi(xs,ys,ws,hs);

						ImagePlus rsimp=null;
						
						if(doubleview){if(pass==0)torl="Left"; else torl="Top";}
						if(pass==2){
							imp.setRoi(crop);
							Duplicator dup = new Duplicator();
							ImagePlus dimp=dup.run(imp, 1, chs, Math.max(1,currentz-2), Math.min(sls,currentz+2), frm, frm);
							ZProjector zprojector=new ZProjector(dimp);
							zprojector.setMethod(ZProjector.AVG_METHOD);
							zprojector.setStartSlice(1);
							zprojector.setStopSlice(dimp.getNSlices());
							zprojector.doHyperStackProjection(true);
							rsimp=zprojector.getProjection();
							dimp.close();
						}else
							rsimp=AJ_Misc_Plugins.resliceProject(imp, crop, torl, ZProjector.AVG_METHOD, false, 1.0, 1, chs, frm, frm);
						int rzw=rsimp.getWidth(), rzh=rsimp.getHeight();
						if(i==0 && stcell==cell && pass==0){
							int endh=rzh, endw=rzw, endchs=chs;
							if(doubleview){endh=Math.max(2*rzh,shei); endw=2*shei;}
							if(markcells)endchs=chs+1;
							endimp=IJ.createImage(title+"-ptReslice", "16-bit composite-mode", endw, endh, endchs, endcell-stcell, frms);
							endimp.setCalibration(cal);
							endimp.show();
							if(endchs>1 && luts!=null){
								((CompositeImage)endimp).setMode(CompositeImage.COMPOSITE);
								if(luts.length<endchs){
									LUT[] newluts=new LUT[endchs];
									for(int j=0;j<endchs;j++){
										if(j<luts.length)newluts[j]=luts[j];
										else newluts[j]=LUT.createLutFromColor(Color.RED);
									}
									luts=newluts;
								}
								((CompositeImage)endimp).setLuts(luts);
							}
						}
						for(ch=1;ch<=chs;ch++){
							ImageProcessor rsip=rsimp.getStack().getProcessor(rsimp.getStackIndex(ch, 1, 1));
							ImageProcessor ip=endimp.getStack().getProcessor(endimp.getStackIndex(ch, cell-stcell+1, frm));
							int xoffset=0, yoffset=0;
							if(pass==1)yoffset=rzh;
							else if(pass==2)xoffset=shei;
							for(int zy=0;zy<rzh;zy++){
								for(int zx=0;zx<rzw;zx++){
									ip.putPixel(zx+xoffset, zy+yoffset, rsip.getPixel(zx, zy));
								}
							}
						}
						if(markcells){
							int xmark=x-xs, ymark=y-ys, zmark=(int)(currentz*pz/px);
							ImageProcessor ip = endimp.getStack().getProcessor(endimp.getStackIndex(chs+1, cell-stcell+1, frm));
							ip.setColor(Color.WHITE);
							if(pass==0) {ip.fill(new OvalRoi(ymark-r/2,zmark-r/2,r,r));}
							else if(pass==1) {ip.fill(new OvalRoi(xmark-r/2,rzh+zmark-r/2,r,r));}
							else{ip.fill(new OvalRoi(shei+xmark-r/2,ymark-r/2,r,r));}
						}
						rsimp.close();
						endimp.updateAndRepaintWindow();
					}
				}
			}
		}
		imp.resetRoi();
		endimp.resetRoi();
		WindowManager.setCurrentWindow(endimp.getWindow());
	}

}
