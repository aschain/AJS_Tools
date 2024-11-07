package ajs.tools;

import ij.plugin.*;
import ij.plugin.filter.ThresholdToSelection;
import ij.gui.*;
import ij.io.FileInfo;
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


/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class Thresh_Cell_Transfer implements PlugIn, MouseListener, KeyListener, MouseWheelListener {

	private final static String version="1.4.0";
	private final static String HEADING="Label"+"\t"+"ROI"+"\t"+"Cell#"+"\t"+"Area"+"\t"+"Perimeter"+"\t"+"Circularity"+"\t"+"X"+"\t"+"Y"+"\t"+"Mean"+"\t"+"Slice"+"\t"+"Frame"+"\t"+"Thresh"+"\t"+"Time";
	//private final static double AREATOLERANCE=0.2;
	private final static boolean DEBUG=false;
	private final static boolean ALWAYS_UPDATE=false;
	private final static int MAX_POINTS=8;
	private final static Color addColor=new Color(128,64,255);
	private final static Color subColor=new Color(255,0,0);
	private final static int[] WHEELFACTORS= new int[] {1,10,30};
	
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
	private boolean done=false, gocellcomplete=false, updatedLabel=false;
	private boolean addDirectRoi=false, editRoi=false;
	private boolean drawCellLabel=false;
	private boolean autoAccept=false;
	private boolean[] buttonpress=new boolean[3];
	private AtomicBoolean altWasDown=new AtomicBoolean(false), shiftWasDown=new AtomicBoolean(false), spacepress=new AtomicBoolean(false), acceptedROI=new AtomicBoolean(false);
	private boolean[] postFirstAccept=new boolean[MAX_POINTS];
	//private boolean showEachPointRoi=true;
	private boolean autoSave=true;
	private int pointIndex=0;
	private ArrayList<Point> xys=new ArrayList<Point>();
	private ArrayList<Roi> directRois=new ArrayList<Roi>();
	private boolean[] pointIsNeg=new boolean[MAX_POINTS];
	private int curX=-1, curY=-1;
	private int labelsl=0, celln=1;
	private double[] currentThresh=new double[MAX_POINTS];
	private int wheelfactori = 1;
	private int MYLUT=ImageProcessor.RED_LUT;
	private ImagePlus simp, timp;
	private Overlay soverlay;
	private String title, endtitle, basetitle;
	private ArrayList<String> cellLabels=new ArrayList<String>();
	private String cellLabel="Unlabeled";
	private AutoWandRoi[] curWand;
	private double[] threshMultiplier=null;
	private TCTPanel tctpanel;
	private Thread updateCurWandThread=null;
	private Thread sucwt=null;
	private TctTextWindow results;
	private enum TctLines{
		INFO, OUTPUT, WAND, CURRENTPOINT, THRESH, FRAMESLEFT;
	}
	private double[] times;
	private AtomicBoolean shouldStop=new AtomicBoolean();
	private PlotWindow plotWindow=null;
	private static LUT LUT_GLASBEY_INV=null;
	private final Color DEF_ROI_COLOR=Roi.getColor();
	private String spath="";
	private boolean editing=false;
	//Wand w;

	public void run(String arg) {
		TCT(WindowManager.getCurrentImage());
	}

	public void TCT(ImagePlus imp) {
		if(LUT_GLASBEY_INV==null) {
			IndexColorModel cm=ij.plugin.LutLoader.getLut("glasbey inverted");
			if(cm!=null)LUT_GLASBEY_INV=new LUT(cm, 0, 255);
			else IJ.log("Please install the LUT: glasbey inverted");
		}
		
		simp=imp;
		if (simp==null) {IJ.log("noImage"); return;}
		if(!setup())return;
		soverlay=new Overlay();
		simp.setOverlay(soverlay);

		results=new TctTextWindow(basetitle);
		if("Unlabeled".equals(cellLabel)) askCellLabel();

		tctpanel=new TCTPanel();
		tctpanel.addWindowListener(new WindowAdapter(){  
			public void windowClosing(WindowEvent e) {  
				done=true;
				tctpanel.dispose();  
			}  
		});  
		tctpanel.setVisible(true);

		int sl=simp.getSlice(), fr=simp.getFrame();
		int frms=simp.getNFrames();
		int prevsl=sl, prevfr=fr;
		int prevcelln=-1;
		curX=-1; curY=-1;
		currentThresh[0]=250;
		int xprev=curX, yprev=curY;
		//boolean justfirst;
		String frmsleft="";
		boolean ignoreCellComplete=false;
		//int lastfr=-1;
		
		Overlay tov1=timp.getOverlay();
		boolean importFromOverlay=false;
		if(tov1!=null && tov1.size()>0) {
			YesNoCancelDialog ync=new YesNoCancelDialog(null, "AJTCT-Overlay-Import", "Import from AJTCT Overlay?");
			if(ync.yesPressed()) importFromOverlay=true;
		}

		//-------loop-------------

		while(!done){
			simp.setT(fr);
			if(editRoi)editRoi();
			if(celln!=prevcelln){
				frmsleft=""; for(int i=0;i<frms;i++) frmsleft+=(i+1)+" ";
				tctpanel.setTextLine(TctLines.FRAMESLEFT, "Frames left: "+frmsleft);
				xys.clear();
				directRois.clear();
				pointIndex=0;
				tctpanel.resetWP();
				for(int i=0;i<MAX_POINTS;i++) {
					postFirstAccept[i]=false;
					if(i>0)currentThresh[i]=currentThresh[0];
				}
				if(editing) {
					if(curWand[0]!=null && curWand[0].cxys!=null && curWand[0].cxys.size()>0) {
						curX=curWand[0].cxys.get(0).x;
						curY=curWand[0].cxys.get(0).y;
						xys.add(new Point(curX,curY));
						currentThresh[0]=curWand[0].getThresh(0);
					}
					for(int i=0;i<frms;i++) {
						if(curWand[i]!=null)curWand[i].accept();
					}
					ignoreCellComplete=true;
				}else {
					curX=-1; curY=-1;
					curWand=new AutoWandRoi[frms];
					tctpanel.setTextLine(TctLines.INFO, "Working on "+cellLabel+" Cell: "+celln);
				}
				xprev=curX; yprev=curY;
				//lastfr=-1;
				simp.getProcessor().setThreshold(currentThresh[0], (double) 65535, MYLUT);
				prevsl=simp.getSlice(); prevfr=simp.getFrame();
				prevcelln=celln;
				soverlay.clear();
				if(importFromOverlay) {
					timp.setOverlay(new Overlay());
					for(int i=0;i<tov1.size();i++) {
						Roi tovroi=tov1.get(i);
						if(tovroi!=null) {
							int frtov=tovroi.getTPosition();
							curWand[frtov]=new AutoWandRoi(tovroi,frtov,currentThresh[0]);
							curWand[frtov].accept();
						}
					}
					importFromOverlay=false;
				}
				simp.updateAndDraw();
			}
			acceptedROI.set(false);
			while(!acceptedROI.get() && !gocellcomplete) {
				//do{
					fr=simp.getFrame(); sl=simp.getSlice();
					timp.setPosition(1,labelsl,fr);
					if(sl!=prevsl || fr!=prevfr) {
						//tctpanel.setTextLine(5, "Sl"+sl+" psl"+prevsl+" fr"+fr+" pfr"+prevfr);
						if(curWand[fr-1]!=null && curWand[fr-1].roi!=null) {
							if(curWand[fr-1].sl!=sl) {
								curWand[fr-1].updateWandRoi();
								updateCurWand(false);
							}
							curWand[fr-1].showBoth();
						}
						//justfirst=true;
					}
					prevsl=sl; prevfr=fr;
					IJ.wait(10);
				//}while(sl!=prevsl || fr!=prevfr);

				if(addDirectRoi)addDirectRoi();
				if(editRoi)acceptedROI.set(true);

				if(((curX!=xprev||curY!=yprev) /* || justfirst*/) && curX!=-1 && !(altWasDown.get() || shiftWasDown.get())){
					xprev=curX;yprev=curY;
					Point c=new Point(curX,curY);
					if(pointIndex>=xys.size()) {xys.add(c);
					}else {
						xys.set(pointIndex, c);
					}

					if(curWand[fr-1]==null)curWand[fr-1]=new AutoWandRoi(fr);
					else {
						curWand[fr-1].updatePoint();
					}
					curWand[fr-1].showBoth();
					if(!postFirstAccept[pointIndex])updateCurWand(false);

					//if(justfirst && lastfr!=-1 && xys[lastfr-1]!=null ){
					//	//test if new center is farther than the area's equivalent radius away from the old center
					//	sel=doWandRoiByPt(fr);
					//}
					//justfirst=false;
					tctpanel.setTextLine(TctLines.CURRENTPOINT, "Point:"+(pointIndex+1)+" x:"+curX+" y:"+curY+" z:"+sl+" fr:"+fr);
				}
				if(WindowManager.getWindow(title)==null || WindowManager.getWindow(endtitle)==null) {IJ.log("Window Closed"); done=true;}
				if(done) {cleanup(); return;}
				if(((spacepress.get()) || buttonpress[2]))IJ.wait(10);
				//if(DEBUG)IJ.log("\\Update:"+System.nanoTime()/1000000+"  xy:"+xy.x+" "+xy.y+" aa"+autoAccept+" probable"+(curWand[fr-1]==null?"NA":""+curWand[fr-1].probable)+" rc:"+buttonpress[2]+" gb:"+goback);
				if(curX!=-1 && curY!=-1 && autoAccept && curWand[fr-1].probable && ((spacepress.get() && !buttonpress[0]) || buttonpress[2])){acceptedROI.set(true);}
				//if((auto||!firsthit) && x!=-1 && y!=-1 && !firsttime)acceptedROI=true; else IJ.wait(200);
			}
			if(updatedLabel) {prevcelln=celln; updatedLabel=false;}
			/*if(WindowManager.getCurrentImage()!=simp && WindowManager.getCurrentWindow()!=plotWindow){
				GenericDialog gd = new GenericDialog("Cancel");
				gd.addMessage("Stop TCT?");
				gd.enableYesNoCancel("Yes", "No");
				gd.showDialog();
				if (gd.wasCanceled()){
					cleanup(); return;}
				else if (gd.wasOKed()){
					cleanup();return;}
			}*/

			if(curWand[fr-1]==null){
				IJ.log("No selection");
			}else if(!gocellcomplete){
				curWand[fr-1].setRoi(simp.getRoi());
				curWand[fr-1].accept();
				tctpanel.printOutput(curWand[fr-1].output);
				if(!postFirstAccept[0]) {
					ImageProcessor tip=timp.getStack().getProcessor(timp.getStackIndex(1,labelsl,fr));
					tip.setRoi(curWand[fr-1].troi);
					ImageStatistics im=ImageStatistics.getStatistics(tip);
					if(im.max>0) {
						IJ.showMessage("Warning-- Current selection overlaps with another cell (Cell"+im.max+")");
					}
				}
				postFirstAccept[pointIndex]=true;
				updateCurWand(true);
			}

			long startpresstime=System.nanoTime();
			long waittime=200;
			while(spacepress.get() && !gocellcomplete) {
				IJ.wait(20);
				long presstime=System.nanoTime()-startpresstime;
				//if(!firsthit)waittime=100;
				//IJ.log("wait: "+presstime);
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
			tctpanel.setTextLine(TctLines.FRAMESLEFT, "Frames left: "+frmsleft);
			if((cellcomplete && !ignoreCellComplete) || gocellcomplete){
				gocellcomplete=false;
				ignoreCellComplete=false;
				//firsthit=true;
				GenericDialog gd = new GenericDialog("Cell Complete");
				String message="All done with cell #"+celln+"?";
				if(!cellcomplete)message=message+"\nWarning: Some frames have no data!!";
				gd.addMessage(message);
				gd.enableYesNoCancel("Yes", "No");
				gd.showDialog();
				spacepress.set(false); buttonpress[0]=false; buttonpress[1]=false; buttonpress[2]=false;
				if (gd.wasOKed()){
					int lastCompleteCelln=results.getLastCellForLabel(cellLabel);
					timp.setColor(Color.white);
					String totoutput="";
					for(int i=0;i<frms;i++) {
						String output="";
						if(curWand[i]!=null && curWand[i].accepted) {
							curWand[i].draw();
							output=curWand[i].output;
						}else{
							output=cellLabel+"\t0\t"+celln+"\tNA\tNA\tNA\tNA\tNA\tNA\tNA\t"+(i+1)+"\t"+currentThresh[0]+"\t"+times[i];
						}
						if(editing && celln<=lastCompleteCelln)results.updateLine(cellLabel, celln, i+1, output);
						else {totoutput=totoutput+output+((i==(frms-1))?"":"\n");}
					}
					results.appendForLabel(cellLabel,totoutput);
					timp.setProperty("Info", results.getText());
					timp.setPosition(1,labelsl,fr);
					Overlay tov=timp.getOverlay();
					if(tov!=null)tov.clear();
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
						boolean dosave=false;
						if(cellLabel.equals("Dura") && celln==1 && !editing) {
							if((new java.io.File(spath+timp.getTitle())).exists()) {
								YesNoCancelDialog ync=new YesNoCancelDialog(null,"File Exists","AJTCT file already exists, ok to overwrite?\n(Otherwise autosave will be turned off)");
								if(!ync.yesPressed()) {
									autoSave=false;
									tctpanel.autosavecb.setState(autoSave);
								}else dosave=true;
							}
						}else dosave=true;
						if(dosave) {
							IJ.save(timp, spath+timp.getTitle());
							results.save();
							IJ.showStatus("TCT saved at "+spath+timp.getTitle());
						}
					}
					if(editing)celln=results.getLastCellForLabel(cellLabel)+1;
					else celln++;
					editing=false;
					fr=0;
				}else {
					if(!gd.wasCanceled())ignoreCellComplete=true;
				}
			}
			if(fr<frms) fr++; else fr=lowsl;
			results.roin++;
		}
		cleanup();
	}

	private boolean setup() {

		title=simp.getTitle();
		basetitle=title.endsWith(".tif")?title.substring(0,title.length()-4):title;
		if(basetitle.endsWith("-AJTCT")){
			timp=simp;
			if(!title.endsWith(".tif"))timp.setTitle(title+".tif");
			endtitle=title;
			title=endtitle.substring(0,endtitle.length()-10);
			
			simp=WindowManager.getImage(title);
			if(simp==null)simp=WindowManager.getImage(title+".tif");
			if(simp==null){IJ.showMessage("Can't find original image: "+title); return false;}
			if(!simp.getTitle().endsWith(".tif"))simp.setTitle(simp.getTitle()+".tif");
			title=simp.getTitle();
			basetitle=title.substring(0,title.length()-4);
		}
		if(timp==null){
			endtitle=basetitle+"-AJTCT.tif";
			timp=WindowManager.getImage(endtitle);
			if(timp==null) {
				timp=WindowManager.getImage(basetitle+"-AJTCT");
				if(timp!=null)timp.setTitle(endtitle);
			}
		}
		if(timp!=null)convertTCTtoGlasbey(timp, simp.getCalibration().pixelWidth);
		times=Time_Extractor.extractTimes(simp, false, Time_Extractor.SubTime.EVENT_SET);
		ImageWindow siw=simp.getWindow();

		if(timp==null) {
			//timp=IJ.createImage(endtitle,"8-bit composite", simp.getWidth(),simp.getHeight(), 2, 1, simp.getNFrames());
			timp=IJ.createHyperStack(endtitle, simp.getWidth(), simp.getHeight(), 2, 1, simp.getNFrames(), 8);
			if(timp==null) { IJ.log("Error - timp did not get created");return false;}
			timp.setDisplayMode(IJ.COMPOSITE);
			timp.show();
			if(LUT_GLASBEY_INV!=null)((CompositeImage)timp).setChannelLut(LUT_GLASBEY_INV, 1);
			//timp.getProcessor().invertLut();
			//Rectangle tbounds=timp.getWindow().getBounds();
			Rectangle sbounds=siw.getBounds();
			//double sizefactor=sbounds.getWidth()/tbounds.getWidth();
			timp.getWindow().setLocationAndSize((int) (sbounds.getX()+sbounds.getWidth()+5),(int) sbounds.getY(),(int) sbounds.getWidth(),65535);
		}
		if(timp==simp){IJ.log("Same window exiting"); done=true; return false;}
		if(timp==null){IJ.log("No target window"); done=true; return false;}
		
		FileInfo fi = simp.getOriginalFileInfo();
		if (fi!=null && fi.directory!=null) spath= fi.directory;
		if(spath=="") {IJ.error("Please save source image");return false;}

		double cthresh=simp.getProcessor().getMinThreshold();
		if(cthresh>0)currentThresh[0]=cthresh;

		WindowManager.setWindow(timp.getWindow());
		WindowManager.setWindow(siw);
		preStrip();
		ImageCanvas ic = simp.getCanvas();
		ic.disablePopupMenu(true);
		ic.addMouseListener(this);
		ic.addKeyListener(this);
		siw.removeMouseWheelListener(siw);
		siw.addMouseWheelListener(this);
		IJ.setForegroundColor(255,255,255);
		IJ.setBackgroundColor(0,0,0);
		IJ.setTool(ij.gui.Toolbar.FREEROI);
		simp.setPosition(simp.getNChannels()==3?2:1,simp.getZ(),simp.getT());
		return true;
	}

	private void preStrip(){
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
			soverlay.clear();
			simp.updateAndDraw();
			ImageCanvas ic=simp.getCanvas();
			if(ic!=null){
				ImageWindow siw=simp.getWindow();
				ic.removeKeyListener(this);
				ic.removeMouseListener(this);
				siw.removeMouseWheelListener(this);
				siw.addMouseWheelListener(siw);
				ic.disablePopupMenu(false);
			}
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
		double curthresh=currentThresh[0];
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
		double[] cells=results.getColumn("Cell#");
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
		
		ImageStack ist=imp.getImageStack();
		ImageProcessor ip=ist.getProcessor(imp.getStackIndex(1, 1, 1));
		java.awt.image.ColorModel cm=ip.getColorModel();
		//check if LUT is already glasbey inverted
		if(cm.getRed(255)==248 && cm.getGreen(255)==248 && cm.getBlue(255)==232) {IJ.showStatus("AJTCT already converted"); return;}
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

	class TctTextWindow{
		TextWindow rtw;
		public int roin;
		public String twtitle=null;

		public TctTextWindow(String basetitle) {
			String[] restitles=new String[] {"ThreshCellTransfer-"+basetitle+".xls", "ThreshCellTransfer-"+basetitle+".txt", "ThreshCellTransfer-"+basetitle+".csv"};
			for(String restitle : restitles) {
				rtw=(TextWindow) WindowManager.getWindow(restitle);
				if(rtw!=null)break;
			}
			if(rtw==null){
				twtitle=restitles[2];
				rtw=new TextWindow(twtitle,HEADING,"",800,400);
				if(timp!=null && timp.getInfoProperty()!=null) {
					String tresults=timp.getInfoProperty();
					if(tresults!=null && !"".contentEquals(tresults)) {
						if(tresults.startsWith("\n"))tresults=tresults.substring(1);
						rtw.append(tresults);
					}
				}
			}else {
				TextPanel panel=rtw.getTextPanel();
				twtitle=rtw.getTitle();
				if(twtitle.endsWith(".xls") || twtitle.endsWith(".txt")) {
					twtitle=restitles[2];
					rtw.setTitle(twtitle);
				}
				String phs=panel.getColumnHeadings();
				if(!phs.contentEquals(HEADING)){
					TextWindow temp=new TextWindow(restitles[0],HEADING,"",800,400);
					ResultsTable restable=panel.getResultsTable();
					ResultsTable ttable=temp.getTextPanel().getResultsTable();
					String[] phss=phs.split("\t");
					String[] hs=HEADING.split("\t");
					int n;
					for(int i=0;i<phss.length;i++) {
						n=-1;
						for(int j=0;j<hs.length;j++) {
							if(phss[i].contentEquals(hs[j])) {n=j; break;}
						}
						if(n>-1) for(int j=0;j<restable.size();j++)ttable.setValue(n, j, restable.getValueAsDouble(i, j));
					}
					rtw.close();
					rtw=temp;
				}
			}
			TextPanel panel=rtw.getTextPanel();
			//IJ.log(""+panel.getLineCount());
			if(panel.getLineCount()>0){
				int troin=0;
				celln=0;
				String[] oldline;
				for(int i=0;i<panel.getLineCount();i++) {
					oldline=panel.getLine(i).split("\t");
					if(i==0){cellLabels.add(oldline[0]);}
					else{ if(!cellLabels.contains(oldline[0])) {cellLabels.add(oldline[0]);}}
					if(!oldline[1].startsWith("NA"))troin=(int)Float.parseFloat(oldline[1]);
					if(!oldline[2].startsWith("NA"))celln=(int) Float.parseFloat(oldline[2]);
					if(troin>roin)roin=troin;
					//newline=""+troin+"\t"+celln+"\t"+oldline[2]+"\t"+oldline[3]+"\t"+oldline[4]+"\t"+oldline[5]+"\t"+(int)Float.parseFloat(oldline[6])+"\t"+(int)Float.parseFloat(oldline[7]);
					//panel.setLine(i,newline);
				}
				if(cellLabels.size()>0)cellLabel=cellLabels.get(cellLabels.size()-1);
				celln++;roin++;
				labelsl=cellLabels.size();
				IJ.log("Using open window, roin: "+roin+" celln: "+celln+" Label: "+cellLabel);
			}
		}

		public void append(String text) {rtw.append(text);}
		public String getText() {
			String[] temp=rtw.getTextPanel().getText().split("\n");
			String result="";
			if(temp.length>1)result=temp[1];
			for(int i=2; i<temp.length;i++)result+="\n"+temp[i];
			return result;
		}
		public ResultsTable getResultsTable() {return rtw.getResultsTable();}
		public String[] getColumnAsStrings(int col) {
			if(HEADING.split("\t").length<(col+1) || col<0) {
				IJ.log("Selected column "+col+" out of range.");
				return null;
			}
			String[] temp=rtw.getTextPanel().getText().split("\n");
			if(temp.length<2) {
				//IJ.log("Textpanel had no data");
				return null;
			}
			String[] result=new String[temp.length-1];
			for(int i=1; i<temp.length;i++)result[i-1]=temp[i].split("\t")[col];
			return result;
		}
		public double[] getColumn(int col) {
			String[] temp=getColumnAsStrings(col);
			if(temp!=null) {
				double[] res=new double[temp.length];
				for(int i=0;i<temp.length;i++)res[i]=AJ_Utils.parseDoubleTP(temp[i]);
				return res;
			}
			return null;
		}
		public int getHeadingIndex(String col) {
			String[] hds=HEADING.split("\t");
			for(int i=0;i<hds.length;i++)if(hds[i].contentEquals(col))return i;
			return -1;
		}
		public String[] getColumnAsStrings(String col) {
			return getColumnAsStrings(getHeadingIndex(col));
		}
		public double[] getColumn(String col) {
			return getColumn(getHeadingIndex(col));
		}
		
		public int findLine(String elabel, int cell, int frame) {
			String[] text=getText().split("\n");
			int i=-1; 
			String[] lines=null;
			do {
				i++;
				if(i<text.length)lines=text[i].split("\t");
			}while(i<text.length && lines.length>10 && !(lines[0].contentEquals(elabel) && lines[2].contentEquals(""+cell) && lines[10].contentEquals(""+frame)));
			if(lines[0].contentEquals(elabel) && lines[2].contentEquals(""+cell) && lines[10].contentEquals(""+frame))return i;
			return -1;
		}

		public void updateLine(String elabel, int cell, int frame, String updatedLine) {
			int i=findLine(elabel, cell, frame);
			rtw.getTextPanel().setLine(i, updatedLine);
		}
		
		public void appendForLabel(String elabel, String lines) {
			if("".equals(lines))return;
			String[] text=getText().split("\n");
			if(text.length<2 || text[text.length-1].startsWith(elabel)) {append(lines); return;}
			int inserti=getlastLineForLabel(elabel)+1;
			if(inserti==text.length) {append(lines); return;}
			rtw.getTextPanel().setColumnHeadings("");
			rtw.getTextPanel().setColumnHeadings(HEADING);
			for(int i=0;i<text.length;i++) {
				if(i==inserti) {
					append(lines);
				}
				append(text[i]);
			}
		}
		
		private int getlastLineForLabel(String elabel) {
			String[] text=getText().split("\n");
			if(text.length<2)return -1;
			int i=-1; 
			String[] iline=null;
			do {
				i++;
				if(i<text.length)iline=text[i].split("\t");
			}while(i<text.length && iline.length>0 && !iline[0].contentEquals(elabel));
			while(i<text.length && iline[0].contentEquals(elabel)) {
				i++;
				if(i<text.length) iline=text[i].split("\t");
			}
			i--;
			return i;
		}
		
		public int getLastCellForLabel(String elabel) {
			String[] text=getText().split("\n");
			if(text.length<2)return 0;
			int inserti=getlastLineForLabel(elabel);
			if(inserti<0)return 0;
			String[] iline=text[inserti].split("\t");
			return AJ_Utils.parseIntTP(iline[2]);
		}
		
		public void save() {
			rtw.getTextPanel().saveAs(spath+twtitle);
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
		public Checkbox autosavecb;

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
						boolean clear=false;
						for(int i=0;i<simp.getNFrames();i++)if(curWand[i]!=null && curWand[i].accepted) {clear=true; break;}
						updateCurWand(clear);
						break;
					case "autoAccept":
						autoAccept=!autoAccept;
						((Button)e.getSource()).setLabel("Auto Accept - "+(autoAccept?"yes":"no"));
						break;
					case "autoacceptall":
						autoAcceptAll();
						break;
					case "addDirectRoi":
						addDirectRoi=true;
						break;
					case "deleteDirectRoi":
						deleteDirectRoi(directRois.size()-1);
						break;
					case "editRoi":
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
			c.gridx=0;c.gridy=0;
			c.gridwidth=1; c.gridheight=1;
			Button b=new Button();
			b.setFocusable(false);
			b.setActionCommand("LUT");
			b.setLabel("LUT");
			b.addActionListener(l);
			if(simp.isComposite())b.setEnabled(false);
			add(b,c);
			c.gridx++; //1
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("editRoi");
			b.setLabel("Edit Existing Roi");
			b.addActionListener(l);
			add(b,c);
			c.gridx++; //2
			//b=new Button();
			//b.setFocusable(false);
			//b.setActionCommand("askCellLabel");
			//b.setLabel("New Cell Label");
			//b.addActionListener(l);
			//add(b,c);
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
				}
			});
			add(whichlabel,c);
			c.gridx++; //3
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("gocellcomplete");
			b.setLabel("Cell complete");
			b.addActionListener(l);
			add(b,c);
			c.gridx++; //4
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("changeWheelFactor");
			b.setLabel("Wheel - "+WHEELFACTORS[wheelfactori]);
			b.addActionListener(l);
			add(b,c);
			c.gridy++; //1
			c.gridx=0;
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("updateCurWand");
			b.setLabel("Update curWand");
			b.addActionListener(l);
			add(b,c);
			c.gridx++; //1
			b=new Button();
			b.setFocusable(false);
			b.setActionCommand("autoAccept");
			b.setLabel("Auto Accept - "+(autoAccept?"yes":"no"));
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
						if(cs.contentEquals("New Negative Point") && xys.size()==0) {
							IJ.showMessage("First point must not be negative");
							whichPoint.select(0);
							return;
						}
						pointIsNeg[xys.size()]=cs.contentEquals("New Negative Point");
						setPointIndex(xys.size());
					}else if(cs.contentEquals("Delete Point")) {
						//String[] items=new String[xys.size()];
						//for(int i=0;i<items.length;i++)items[i]=""+(i+1);
						//GenericDialog gd=new GenericDialog("Delete Point");
						//gd.addChoice("Which Point to delete?",items	, items[items.length-1]);
						//gd.showDialog();
						//if(gd.wasCanceled()) {whichPoint.select(0); return;}
						//int ind=gd.getNextChoiceIndex();
						int ind=xys.size()-1;
						if(ind>0) {
							xys.remove(ind);
							if(pointIndex>=xys.size())pointIndex=xys.size()-1;
							resetWP();
							for(int i=0;i<curWand.length;i++) {
								if(curWand[i]!=null) {
									curWand[i].deletePoint(ind);
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
			autosavecb=new Checkbox("Auto Save");
			autosavecb.setFocusable(false);
			autosavecb.setState(autoSave);
			autosavecb.addItemListener(new ItemListener() {
					@Override
				public void itemStateChanged(ItemEvent e) {
					autoSave=autosavecb.getState();
				}
			});
			add(autosavecb,c);
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

		public void printOutput(String output) {
			if(output==null)return;
			String[] tl=output.split("\t");
			if(tl.length<12)return;
			setTextLine(TctLines.OUTPUT, "Last Accepted-- Lbl: "+tl[0]+" Cell: "+tl[2]+" Fr: "+tl[10]+" Area: "+AJ_Utils.parseDoubleTP(tl[3],2)+" Mean: "+AJ_Utils.parseDoubleTP(tl[8],2)+
					" X: "+AJ_Utils.parseDoubleTP(tl[6],2)+" Y: "+AJ_Utils.parseDoubleTP(tl[7],2)+" thresh: "+tl[11]);
		}

		public void resetWP() {
			whichPoint.removeAll();
			whichPoint.add("Which Point: "+(pointIndex+1));
			for(int i=0;i<Math.max((pointIndex+1),xys.size());i++)whichPoint.add(""+(i+1)+(pointIsNeg[i]?"-":""));
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

	private void setPointIndex(int i) {
		pointIndex=i;
		curX=-1; curY=-1;
		tctpanel.setTextLine(TctLines.CURRENTPOINT, "Point:"+(pointIndex+1)+" x:"+curX+" y:"+curY+" z:"+simp.getZ()+" fr:"+simp.getT());
		tctpanel.resetWP();
	}


	private void simpleUpdateCurWand() {
		if(xys.size()==0 || xys.get(0).x==-1)return;

		int cfr=simp.getFrame();
		int i=cfr-1;
		if(curWand[i]==null)curWand[i]=new AutoWandRoi(i+1);
		else curWand[i].setThresh(pointIndex,currentThresh[pointIndex]);
		curWand[i].showBoth();
		if(sucwt!=null) {
			shouldStop.set(true);
			try {
				sucwt.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		Runnable task=new Runnable() {
			public void run() {
				for(int i=0;i<simp.getNFrames();i++) {
					if(shouldStop.get()){
						if(DEBUG)log("broke simpleUpdateCurWand");
						break;
					}
					if(i==(cfr-1))continue;
					if(curWand[i]!=null) curWand[i].setThresh(pointIndex,currentThresh[pointIndex]);
				}
			}
		};
		shouldStop.set(false);
		Thread sucwt=new Thread(task);
		sucwt.start();
	}
	
	private void log(String string) {
		java.awt.EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				IJ.log(string);
			}
		});
	}
	
	private void updateCurWand(boolean clear) {
		updateCurWand(clear, false);
	}

	private void updateCurWand(boolean clear, boolean updateCurrentFrame) {
		final int fr=simp.getFrame()-1;

		if(updateCurrentFrame && (curWand[fr]!=null && (!curWand[fr].accepted || ALWAYS_UPDATE))){
			curWand[fr].updateWandRoi();
			curWand[fr].showBoth();
		}
		
		if(updateCurWandThread!=null) {
			shouldStop.set(true);
			try {
				updateCurWandThread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		updateCurWandThread=new Thread(new Runnable() {

			public void run() {
				for(int i=0; i<simp.getNFrames(); i++) {
					if(shouldStop.get()) {
						if(DEBUG)log("broke updateCurWand");
						break;
					}
					if(i==fr)continue;
					if(DEBUG)log("updatingCurWand"+i);
					if(curWand[i]==null) {
						curWand[i]=new AutoWandRoi(getLastAWR(i+1), i+1);
					}else if(!curWand[i].accepted || ALWAYS_UPDATE) {// 
						if(clear)curWand[i].resetPoints();
						curWand[i].findRoiCloseTo(getLastAWR(i+1));
					}
					//IJ.log("curWand"+i+" "+curWand[i]);
				}
			}
		});
		shouldStop.set(false);
		updateCurWandThread.start();
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
		completeDirectRoi(rtype,directRois.size(),addRoi, false);
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
		Roi.setColor((rtype==DirectRoiTypes.SUBTRACTIVE)?subColor:addColor);
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
		if(index>=directRois.size() || index<0)directRois.add(addRoi);
		else {
			oldroi=directRois.set(index, addRoi);
		}
		addRoiToOverlay(addRoi, oldroi, 0, 0, 0);
		updateCurWand(false, true);
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
	
	public void deleteDirectRoi(int ind) {
		if(ind>=directRois.size() || ind<0) {
			IJ.showStatus("There are no Direct Rois to delete");
			return;
		}
		soverlay.remove(directRois.get(ind));
		directRois.remove(ind);
		updateCurWand(false, true);
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
		celln=cell;
		editing=true;
		curWand=new AutoWandRoi[simp.getNFrames()];
		Calibration cal=simp.getCalibration();
		int zcur=0;
		for(int i=0;i<simp.getNFrames();i++) {
			String[] line=results.rtw.getTextPanel().getLine(results.findLine(cellLabel, cell, i+1)).split("\t");
			if(zcur<=0)zcur=AJ_Utils.parseIntTP(line[9]);
			Roi roi=getDrawnRoi(cellLabel, cell, i+1);
			double x=AJ_Utils.parseDoubleTP(line[6]), y=AJ_Utils.parseDoubleTP(line[7]);
			if(x>0 && y>0) {
				Point centroid=new Point((int)(x/cal.pixelWidth),(int)(y/cal.pixelHeight));
				int awz=AJ_Utils.parseIntTP(line[9]);
				curWand[i]=new AutoWandRoi(roi, i+1, AJ_Utils.parseDoubleTP(line[11]), centroid, awz);
				if(i==simp.getT()-1||zcur==0)zcur=awz;
				if(roi!=null) {
					ImageProcessor tip=timp.getStack().getProcessor(timp.getStackIndex(1, labelsl, i+1));
					tip.setColor(Color.BLACK);
					tip.fill(roi);
					tip=timp.getStack().getProcessor(timp.getStackIndex(2, labelsl, i+1));
					tip.setColor(Color.BLACK);
					tip.fill(new Roi(centroid.x-5,centroid.y-22,25,20));
				}
			}
		}
		simp.setPosition(simp.getC(),zcur,1);
		timp.setPosition(timp.getC(),timp.getZ(),1);
		if(curWand[0]!=null)curWand[0].showBoth();
		tctpanel.setTextLine(TctLines.INFO, "Editing "+cellLabel+" Cell: "+celln);
	}
	
	private Roi getDrawnRoi(String label, int cell, int frame) {
		if(!cellLabels.contains(label)) {IJ.log("getDrawnRoi error invalid label");return null;}
		int slice=cellLabels.indexOf(label)+1;
		ImageProcessor tip=timp.getStack().getProcessor(timp.getStackIndex(1, slice, frame));
		tip.setThreshold(cell, cell);
		ThresholdToSelection tts=new ThresholdToSelection();
		Roi result=tts.convert(tip);
		if(result!=null)result.setPosition(1, slice, frame);
		return result;
	}
	
	public void editRoi2() {
		editRoi=false;
		//IJ.setTool("wand");
		//WaitForUserDialog wfu=new WaitForUserDialog("Select Roi to edit on the AJTCT stack:");
		//wfu.show();
		NonBlockingGenericDialog gd=new NonBlockingGenericDialog("Set Roi");
		gd.addNumericField("Which Roi (Please also go to correct Frame)?", celln-1, 0);
		gd.showDialog();
		if(gd.wasCanceled())return;
		int cell=(int)gd.getNextNumber();
		
		ImageProcessor tip=timp.getProcessor();
		if(cell<=0 || cell>celln){timp.resetRoi(); return;}
		tip.setThreshold(cell, cell);
		ThresholdToSelection tts=new ThresholdToSelection();
		Roi roi=tts.convert(tip);
		if(roi==null){timp.resetRoi(); return;}
		else timp.setRoi(roi);
		Roi newroi=null;
		boolean roiaccepted=false;
		while(!roiaccepted) {
			WaitForUserDialog wfu=new WaitForUserDialog("Adjust ROI now:");
			wfu.show();
			newroi=timp.getRoi();
			if(newroi==null) {
				YesNoCancelDialog ync=new YesNoCancelDialog(null, "Really?","You have erased the ROI, shall I leave it blank?");
				if(ync.cancelPressed()){timp.resetRoi(); return;}
				if(ync.yesPressed())roiaccepted=true;
			}else if(newroi.equals(roi)) {
				YesNoCancelDialog ync=new YesNoCancelDialog(null, "Really?","The Roi will not be changed, ok?");
				if(ync.cancelPressed() || ync.yesPressed()){timp.resetRoi(); return;}
				else roiaccepted=true;
			}else roiaccepted=true;
		}
		timp.setRoi(roi);
		tip.setRoi(roi);
		tip.setColor(0);
		tip.fill(roi);
		timp.setRoi(newroi);
		tip.setRoi(newroi);
		tip.setColor(cell);
		tip.fill(newroi);
		
		int efr=timp.getT();
		String elabel=timp.getStack().getSliceLabel(timp.getCurrentSlice());
		String[] text=results.getText().split("\n");
		int i=-1; 
		String[] line=null;
		do {
			i++;
			line=text[i].split("\t");
		}while(!(line[0].contentEquals(elabel) && line[2].contentEquals(""+cell) && line[10].contentEquals(""+efr)));
		
		simp.setPosition(simp.getStackIndex(2, AJ_Utils.parseIntTP(line[9]), efr));
		Roi snewroi=(Roi)newroi.clone();
		simp.setRoi(snewroi);
		ImageProcessor sip=simp.getProcessor();
		sip.setRoi(snewroi);
		ImageStatistics im=ImageStatistics.getStatistics(sip,127,simp.getCalibration());
		double area=im.area;
		double mean=im.mean;
		double perimeter,circularity;
		perimeter=snewroi.getLength();
		circularity = perimeter==0.0?0.0:4.0*Math.PI*(area/(perimeter*perimeter));
		
		line[1]=line[1]+"e";
		line[3]=""+area;
		line[4]=""+perimeter;
		line[5]=""+circularity;
		line[6]=""+im.xCentroid;
		line[7]=""+im.yCentroid;
		line[8]=""+mean;
		
		String nline=line[0];
		for(int j=1;j<line.length;j++)nline=nline+"\t"+line[j];
		String ntext=text[0];
		for(int j=0;j<text.length;j++) {
			if(j==0 && i==0)ntext=nline;
			else if(j==i)ntext=ntext+"\n"+nline;
			else ntext=ntext+"\n"+text[j];
		}
		results.rtw.getTextPanel().setColumnHeadings("");
		results.rtw.getTextPanel().setColumnHeadings(HEADING);
		results.append(ntext);
		timp.resetRoi();
		simp.resetRoi();
	}

	public synchronized void mouseWheelMoved(MouseWheelEvent e) {
		if(addDirectRoi)return;
		int rotation = e.getWheelRotation();
		//int amount = e.getScrollAmount();
		boolean ctrl = (e.getModifiersEx() & MouseEvent.CTRL_DOWN_MASK)!=0;
		/*
			IJ.log("mouseWheelMoved: "+e);
			IJ.log("  type: "+e.getScrollType());
			IJ.log("  ctrl: "+ctrl);
			IJ.log("  rotation: "+rotation);
			IJ.log("  amount: "+amount);
		 */
		if (!ctrl) {
			changeThresh(rotation);
		} else{
			simp.getWindow().mouseWheelMoved(e);
		}
	}
	
	private void changeThresh(int upOrDown) {
		if(upOrDown==0)return;
		upOrDown=upOrDown/Math.abs(upOrDown);
		double cthresh=currentThresh[pointIndex]*(threshMultiplier==null?1.0:threshMultiplier[simp.getT()-1]);
		ImageProcessor sip=simp.getProcessor();
		if(cthresh<20)wheelfactori=0;
		if(wheelfactori==0 && cthresh==20)wheelfactori=1;
		currentThresh[pointIndex]+=(upOrDown*WHEELFACTORS[wheelfactori]);
		if(currentThresh[pointIndex]<1.0)currentThresh[pointIndex]=1.0;
		cthresh=currentThresh[pointIndex]*(threshMultiplier==null?1.0:threshMultiplier[simp.getT()-1]);
		sip.setThreshold(cthresh, (double) 65535, MYLUT);
		tctpanel.setTextLine(TctLines.THRESH, "Thresh: "+cthresh+(threshMultiplier==null?"":" ("+currentThresh[pointIndex]+"x"+threshMultiplier[simp.getT()-1]+")"));
		simp.updateAndDraw();
		simpleUpdateCurWand();
	}

	public void actionPerformed(ActionEvent event){ 
		if(DEBUG)IJ.log("Button pressed"); 
	} 

	public void mousePressed(MouseEvent e) { 
		if(IJ.spaceBarDown() || addDirectRoi) return;
		if(IJ.altKeyDown() || IJ.shiftKeyDown() && e.getButton()==MouseEvent.BUTTON1) {
			if(IJ.altKeyDown() )altWasDown.set(true);
			if(IJ.shiftKeyDown())shiftWasDown.set(true);
			IJ.setKeyUp(KeyEvent.VK_ALT);
			IJ.setKeyUp(KeyEvent.VK_SHIFT);
			DirectRoiTypes rtype=DirectRoiTypes.ADDITIVE;
			if(shiftWasDown.get() && altWasDown.get())rtype=DirectRoiTypes.ONLY_WITHIN;
			else if(altWasDown.get())rtype=DirectRoiTypes.SUBTRACTIVE;
			beginDirectRoi(rtype);
			//if(DEBUG) 
			MouseEvent newe=new MouseEvent(simp.getCanvas(), e.getID(), e.getWhen(), e.getModifiers() & ~(Event.SHIFT_MASK | Event.ALT_MASK), e.getX(), e.getY(), e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), false, e.getButton());
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
		if((flags & InputEvent.BUTTON2_DOWN_MASK) !=0)buttonpress[1]=true;
		if((flags & InputEvent.BUTTON3_DOWN_MASK) !=0) {buttonpress[2]=true; acceptedROI.set(true);}
	}
	
	public void mouseDragged(MouseEvent e) {
		if(altWasDown.get() || shiftWasDown.get() && e.getButton()==MouseEvent.BUTTON1) {
			MouseEvent newe=new MouseEvent(simp.getCanvas(), e.getID(), e.getWhen(), e.getModifiers() & ~(Event.SHIFT_MASK | Event.ALT_MASK), e.getX(), e.getY(), e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), false, e.getButton());
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
		if(altWasDown.get() || shiftWasDown.get() && e.getButton()==MouseEvent.BUTTON1) {
			if(DEBUG) IJ.log("alt or shift release");
			MouseEvent newe=new MouseEvent(simp.getCanvas(), e.getID(), e.getWhen(), e.getModifiers() & ~(Event.SHIFT_MASK | Event.ALT_MASK), e.getX(), e.getY(), e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), false, e.getButton());
			simp.getCanvas().mouseReleased(newe);
			Roi roi=simp.getRoi();
			int index=directRois.size()-1;
			if(index<0)index=0;
			DirectRoiTypes rtype=DirectRoiTypes.ADDITIVE;
			if(shiftWasDown.get() && altWasDown.get())rtype=DirectRoiTypes.ONLY_WITHIN;
			else if(altWasDown.get())rtype=DirectRoiTypes.SUBTRACTIVE;
			completeDirectRoi(rtype,index,roi, true);
			altWasDown.set(false);
			shiftWasDown.set(false);
		}
		if(DEBUG) IJ.log("buttonReleased: "+e.getModifiersEx()+" button: "+e.getButton());
		if(e.getButton()==MouseEvent.BUTTON1)buttonpress[0]=false;
		if(e.getButton()==MouseEvent.BUTTON2) {
			IJ.run("Previous Slice [<]");
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
		}
		if(keyCode==KeyEvent.VK_F1){
			changeLutType();
		}
		if(keyCode==KeyEvent.VK_F2){
			editRoi();
		}
		if(keyCode==KeyEvent.VK_F3){
			askCellLabel();
		}
		if(keyCode==KeyEvent.VK_F4){gocellcomplete=true;}
		if(keyCode==KeyEvent.VK_UP || keyCode==KeyEvent.VK_DOWN){
			changeWheelFactor((keyCode==KeyEvent.VK_UP)?1:-1);
		}
		if(keyCode==KeyEvent.VK_LEFT || keyCode==KeyEvent.VK_RIGHT){
			changeThresh((keyCode==KeyEvent.VK_RIGHT)?1:-1);
		}
		if(keyCode==KeyEvent.VK_ESCAPE) {done=true;}
		if(keyCode==KeyEvent.VK_NUMPAD0) {
			if(xys.size()>1) {
				int npi=pointIndex+1;
				if(npi>=xys.size())npi=0;
				setPointIndex(npi);
			}
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
		if(one==null || two==null)return false;
		Rectangle ob=one.getBounds();
		Rectangle tb=two.getBounds();

		//First, quickly rule out rois that are not close based on bounds
		boolean left,above;
		left=(ob.x+ob.width)<(tb.x+tb.width);
		above=(ob.y+ob.height)<(tb.y+tb.height);
		//IJ.log("OB: "+ob.x+","+ob.y+","+ob.width+","+ob.height+" TB: "+tb.x+","+tb.y+","+tb.width+","+tb.height+" "+(left?"Left,":"Right,")+(above?"Above":"Below"));
		if(left?((ob.x+ob.width+FUZZD)<(tb.x)):((tb.x+tb.width+FUZZD)<ob.x))return false;
		if(above?((ob.y+ob.height+FUZZD)<(tb.y)):((tb.y+tb.height+FUZZD)<ob.y))return false;
		//IJ.log("Crossed bounds");

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
		ShapeRoi sroi=(enlargeRoi(one,FUZZD)).and(enlargeRoi(two,FUZZD));
		return (sroi!=null && (sroi.getPolygon().npoints>0));
	}

	public static ShapeRoi enlargeRoi(Roi roi, int enlarge) {
		return (new ShapeRoi(RoiEnlarger.enlarge(roi,enlarge)));
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
		if(roi!=null) {
			if(oldroi!=null)
				soverlay.remove(oldroi);
			roi.setStrokeColor(DirectRoiTypes.SUBTRACTIVE.getString().equals(roi.getName())?subColor:addColor);
			roi.setPosition(ch,sl,fr);
			soverlay.add(roi);
		}
	}

	class AutoWandRoi{
		private ImageProcessor ip;
		private int ch,sl,fr;
		private Calibration cal=simp.getCalibration();
		private Roi[] rois=new Roi[MAX_POINTS];
		private ArrayList<Double> threshs=new ArrayList<Double>();
		public ArrayList<Point> cxys=new ArrayList<Point>();
		private boolean[] cPointIsNeg=new boolean[MAX_POINTS];

		public Roi roi;
		public Roi troi=null;
		public double area;
		public double mean;
		public Point centroid;
		public String output;
		public String pointFail="";

		public boolean probable=false;
		public boolean accepted=false;

		public AutoWandRoi(Roi roi, int frame, double thresh) {
			this(roi, frame, thresh, new Point((int)roi.getXBase(),(int)roi.getYBase()), simp.getZ());
		}
		
		public AutoWandRoi(Roi roi, int frame, double thresh, Point point, int slice) {
			this.roi=roi;
			this.fr=frame;
			this.ch=simp.getC();
			this.sl=slice;
			this.ip=simp.getImageStack().getProcessor(simp.getStackIndex(ch, sl, frame));
			cal=simp.getCalibration();
			cxys.add(point);
			threshs.add(thresh);
			fillStats();
			probable=true;
		}

		public AutoWandRoi(int frame) {
			this.ip=simp.getImageStack().getProcessor(simp.getStackIndex(simp.getC(), simp.getZ(), frame));
			this.fr=frame;
			resetPoints();
			probable=true;
		}

		public AutoWandRoi(AutoWandRoi prevAWI, int frame) {
			this.fr=frame;
			this.ip=simp.getImageStack().getProcessor(simp.getStackIndex(simp.getC(), simp.getZ(), frame));
			findRoiCloseTo(prevAWI);
		}

		private Roi doWandRoi(Point p, double thresh){
			int xw=p.x, yw=p.y;
			if(xw==-1 || yw==-1) return null;

			Wand w=new Wand(ip);
			w.autoOutline(xw,yw,thresh,(double)65535);
			Roi sel=new ShapeRoi(new PolygonRoi(w.xpoints, w.ypoints, w.npoints, Roi.POLYGON));

			Rectangle sbs=sel.getBounds();
			//if(sel!=null && ( (simp.getWidth()<300 || simp.getHeight()<300) || (sbs.getWidth()<(simp.getWidth()/2) && sbs.getHeight()<(simp.getHeight()/2))) && sbs.getX()>0 && sbs.getY()>0 && sbs.getWidth()>5 && sbs.getHeight()>5){
			if(sbs.getX()<0 || sbs.getY()<0) ((ShapeRoi)sel).and(new ShapeRoi(new Roi(0,0,sbs.getWidth()+sbs.getX(),sbs.getHeight()+sbs.getY())));
			if(sel!=null && sbs.getWidth()>5 && sbs.getHeight()>5) {
				sel=ij.plugin.RoiEnlarger.enlarge(ij.plugin.RoiEnlarger.enlarge(sel,3),-3);
			}
			return sel;
		}

		private Roi doWandRoi(int index) {
			return doWandRoi(cxys.get(index), getThresh(index));
		}

		private void fillStats() {
			if(roi!=null) { 
				ip.setRoi(roi);
				ImageStatistics imgstat=ImageStatistics.getStatistics(ip, 127, cal);
				area=imgstat.area;
				mean=imgstat.mean;
				centroid=new Point((int)(imgstat.xCentroid/cal.pixelWidth),(int)(imgstat.yCentroid/cal.pixelHeight));
			}else {

			}
		}

		/*
    	private ImageStatistics getStats(Roi roi) {
    		ip.setRoi(roi);
    		return ImageStatistics.getStatistics(ip, 127, cal);
    	}
		 */

		private void updateWandRoi() {updateWandRoi(null);}

		private void updateWandRoi(AutoWandRoi prev) {
			if(ch!=simp.getC() || sl!=simp.getZ()) {
				ch=simp.getC(); sl=simp.getZ();
				ip=simp.getImageStack().getProcessor(simp.getStackIndex(ch, sl, fr));
			}
			if(cxys.size()==0) {
				if(xys.size()==0)return;
				resetPoints();
				return;
			}
			/* Are Rois added to soverlay?
			for(int i=0;i<rois.length;i++) {
				soverlay.remove(roi);
				if(rois[i]!=null) {
					//IJ.log("remove ov roi"+i+" T"+fr); 
					soverlay.remove(rois[i]);
				}
				rois[i]=null;
			}
			*/
			pointFail="";
			//boolean anyneg=false;
			for(int i=0;i<cxys.size();i++) {
				rois[i]=doWandRoi(i);
				if(i==0) {
					if(cxys.size()>1 && prev!=null && !areRoisTouching(roi, prev.roi)) {rois[0]=null; pointFail+=" "+(i+1);}
				}else {
					if(!cPointIsNeg[i]) {
						if(prev==null || areRoisTouching(rois[i],prev.roi)) {}//addRoi(rois[i]);
						else {pointFail+=" "+(i+1); rois[i]=null;}
					}else {
						//anyneg=true;
						if(prev==null || (prev.rois.length>i && areRoisTouching(rois[i],prev.rois[i]))) {}//subtractRoi(rois[i]);
						else {pointFail+=" "+(i+1); rois[i]=null;}
					}
				}
			}
			buildRoi();
			/*
			 * can't just keep biggest roi because sometimes added points are not contiguous
			if(anyneg) {
				roi=biggestNGRoi(roi);
			}
			 */
			fillStats();
			if(this==curWand[simp.getT()-1])showBoth();
		}

		private void buildRoi() {
			roi=null;
			for(int i=0;i<cxys.size();i++) {
				if(rois[i]!=null) {
					if(cPointIsNeg[i]) {rois[i].setName(DirectRoiTypes.SUBTRACTIVE.getString()); subtractRoi(rois[i]);}
					else addRoi(rois[i]);
					//if(showEachPointRoi)
					//addRoiToOverlay(rois[i], null, ch, sl, fr);
				}
			}
			for(int i=0;i<directRois.size();i++) {
				Roi temp=directRois.get(i);
				if(temp!=null) {
					boolean isNeg=DirectRoiTypes.SUBTRACTIVE.getString().equals(temp.getName());
					if(isNeg)subtractRoi(temp);
					else if(DirectRoiTypes.ONLY_WITHIN.getString().equals(temp.getName())) {
						andRoi(temp);
					}else addRoi(temp);
					//if(showEachPointRoi)addRoiToOverlay(temp, isNeg, ch, sl, fr);
				}
			}
		}

		//private void setRoi(Roi roi, int index) {
		//	rois[index]=roi;
		//	buildRoi();
		//	fillStats();
		//}

		private void setRoi(Roi roi) {
			if(roi==this.roi)return;
			for(int i=0;i<rois.length;i++) {
				if(rois[i]!=null) {soverlay.remove(rois[i]);}
			}
			this.roi=roi;
			fillStats();
			//roi.setPosition(ch,sl,fr);
			//soverlay.add(roi);
		}

		public void addPoint(Point point, double thresh, boolean isNeg) {
			cxys.add((Point)point.clone());
			threshs.add(thresh);
			//IJ.log("ACW"+fr+" cxys.size"+cxys.size()+" tsize"+threshs.size());
			cPointIsNeg[threshs.size()-1]=isNeg;
		}

		public void setPoint(int index, Point point, double thresh) {
			if(index>=cxys.size()) {resetPoints();}
				//IJ.showMessage("setPoint index out of range");return;}
			//if(index==cxys.size())addPoint(point, thresh);
			else {
				cxys.set(index, (Point)point.clone());
				threshs.set(index, thresh);
			}
		}

		public void updatePoint(int index, Point point, double thresh) {
			if(index>=cxys.size()) {IJ.showMessage("updatePoint index out of range");return;}
			setPoint(index,point,thresh);
			updateWandRoi();
		}

		public void updatePoint() {
			if(pointIndex>=cxys.size()) {
				for(int i=cxys.size();i<=pointIndex;i++) {addPoint(xys.get(i), currentThresh[i], pointIsNeg[i]);}
			}else setPoint(pointIndex, xys.get(pointIndex), currentThresh[pointIndex]);
			while(cxys.size()>xys.size()) {cxys.remove(cxys.size()-1); threshs.remove(threshs.size()-1);}
			updateWandRoi();
		}

		public void deletePoint(int index) {
			if(index<cxys.size() && index < threshs.size()) {
				cxys.remove(index); threshs.remove(index);
			}
			updateWandRoi();
		}

		public void resetPoints() {
			cxys=new ArrayList<Point>();
			threshs=new ArrayList<Double>();
			for(int i=0;i<xys.size();i++) {
				addPoint(xys.get(i), currentThresh[i], pointIsNeg[i]);
			}
			updateWandRoi();
		}

		private void addRoi(Roi addroi) {
			if(roi==null) {roi=addroi; return;}
			if(addroi==null)return;
			if(! (addroi instanceof ShapeRoi))addroi=new ShapeRoi(addroi);
			if(! (roi instanceof ShapeRoi))roi=new ShapeRoi(roi);
			roi=((ShapeRoi)roi).or((ShapeRoi)addroi);
		}
		
		private void andRoi(Roi androi) {
			if(roi==null) {return;}
			if(androi==null)return;
			if(! (androi instanceof ShapeRoi))androi=new ShapeRoi(androi);
			if(! (roi instanceof ShapeRoi))roi=new ShapeRoi(roi);
			roi=((ShapeRoi)roi).and((ShapeRoi)androi);
		}

		private void subtractRoi(Roi subroi) {
			if(roi==null)return;
			if(subroi==null)return;
			if(! (subroi instanceof ShapeRoi))subroi=new ShapeRoi(subroi);
			if(! (roi instanceof ShapeRoi))roi=new ShapeRoi(roi);
			roi=((ShapeRoi)roi).not((ShapeRoi)subroi);
		}

		public Roi getRoi() {return roi;}
		public Roi getTRoi() {return troi;}

		private double getThresh(int index) {return threshs.get(index)*(threshMultiplier==null?1.0:threshMultiplier[fr-1]);}

		private void findRoiCloseTo(AutoWandRoi prev) {
			probable=false;
			if(prev==null)return;
			if(prev.area==0)return;
			if(cxys.size()==0) {
				resetPoints();
				//updateWandRoi(prev);
			}else if(cxys.size()<xys.size()) {
				for(int i=cxys.size();i<xys.size();i++)addPoint(xys.get(i),currentThresh[i], pointIsNeg[i]);
				//updateWandRoi(prev);
			}
			updateWandRoi(prev);
			Point c=new Point(xys.get(0).x,xys.get(0).y);
			ArrayList<Double> ranks=new ArrayList<Double>();
			ArrayList<Point> pts=new ArrayList<Point>();
			for(int i=0;i<12;i++) {
				if(roi!=null) {
					double distDiff=Math.hypot(((double)centroid.x*cal.pixelWidth-(double)prev.centroid.x*cal.pixelWidth),((double)centroid.y*cal.pixelHeight-(double)prev.centroid.y*cal.pixelHeight));
					double radDiff=Math.abs((Math.sqrt(area/Math.PI))-(Math.sqrt(prev.area/Math.PI)));
					double rad=(Math.sqrt(prev.area/Math.PI));
					if(distDiff<rad && radDiff<(rad/3)){
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
				if(i==6) {c.x=prev.centroid.x; c.y=prev.centroid.y;} 
				else {c.x-=10; c.y-=10;}
				setPoint(0, c, currentThresh[0]);
				updateWandRoi(prev);
			}
			int index=0;
			double minRank=65535.0;
			for(int i=0; i<ranks.size();i++) { if(ranks.get(i)<minRank) {minRank=ranks.get(i); index=i;}}
			if(index<pts.size())
				setPoint(0,pts.get(index), currentThresh[0]);
			else 
				setPoint(0,c, currentThresh[0]);
			updateWandRoi();
			//if(DEBUG)IJ.log("findRoiCloseTo: "+attempts);
		}

		public void setThresh(int index, double thresh) {
			if(index>=cxys.size()) {
			//	if(DEBUG)IJ.log("TCT ERROR: Can't set thresh for index: "+index);
			//	return;
				resetPoints();
			}
			setPoint(index, cxys.get(index), thresh);
			updateWandRoi();
		}

		public void showBoth() {
			if(roi!=null) {
				if(simp.getRoi()!=roi){
					simp.setRoi(roi);
					timp.setRoi((Roi)roi.clone());
				}
				tctpanel.setTextLine(TctLines.WAND, "Selection Info--  Roi changed: "+((roi.equals(curWand[fr-1].troi))?"no":"YES")+
						" Accepted: "+accepted+" Probable: "+probable+" Points: "+cxys.size()+
						(pointFail.equals("")?"":" Failed point#: "+pointFail));
				tctpanel.printOutput(output);
			}
		}

		public void accept() {
			if(roi==null) {IJ.log("TCT Error: No Selection"); return;}
			probable=true;
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
			timp.setPosition(1,labelsl,fr);
			tov.add(troi);
			timp.updateAndRepaintWindow();

			double perimeter,circularity;
			perimeter=roi.getLength();
			circularity = perimeter==0.0?0.0:4.0*Math.PI*(area/(perimeter*perimeter));
			output=""+cellLabel+"\t"+results.roin+"\t"+celln+"\t"+area+"\t"+perimeter+"\t"+circularity+"\t"+centroid.x*cal.pixelWidth+"\t"+centroid.y*cal.pixelHeight+"\t"+mean+"\t"+sl+"\t"+fr+"\t"+getThresh(0)+"\t"+times[fr-1];
			accepted=true;
		}

		public void draw() {
			ImageStack ist=timp.getImageStack();
			ImageProcessor tip1=ist.getProcessor(timp.getStackIndex(1, labelsl, fr));
			ImageProcessor tip2=ist.getProcessor(timp.getStackIndex(2, labelsl, fr));
			if(troi!=null) {
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

}
