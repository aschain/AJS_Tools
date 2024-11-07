package ajs.tools;

import ij.*;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.gui.StackWindow;
import ij.plugin.PlugIn;
import ij.plugin.RoiEnlarger;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;

public class CellFinder implements PlugIn, KeyListener {
	
	ArrayList<Cell> cells=new ArrayList<Cell>();
	ImagePlus imp;
	final public static boolean DEBUG=true;
	public static int FUZZD=2;
	public static int PASSES=3;
	public static int MIN_CELL_SIZE=200;
	public static int MAX_CELL_SIZE=150000;
	public static int MAX_ROI_SIZE=60000;
	private long st;
	public volatile boolean stop=false;

	@Override
	public void run(String arg) {
		imp=WindowManager.getCurrentImage();
		//String title=imp.getTitle();
		RoiManager rm=RoiManager.getRoiManager();
		Roi[] rois=rm.getRoisAsArray();
		if(rois==null || rois.length==0) {
			if(IJ.showMessageWithCancel("AP", "Running Analyze Particles first")) {
				IJ.run("Analyze Particles...", "  show=[Bare Outlines] include add stack");
				rois=rm.getRoisAsArray();
			}else
				return;
		}
		
		/*
		GenericDialog gd = new GenericDialog("CellFinder");
		gd.addMessage("Using image "+title);
		gd.addNumericField("Fuzziness:",FUZZD, 0);
		if(rois!=null && rois.length>0)gd.addCheckbox("Rois in Manager, re-run Particle Analysis anyway?", false);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		FUZZD=((int) gd.getNextNumber());
		boolean runap=true;
		if(rois!=null && rois.length>0)runap=gd.getNextBoolean();
		
		
		if(runap){rm.removeAll(); IJ.wait(500); IJ.run("Analyze Particles...", "  show=[Bare Outlines] include add stack"); IJ.wait(2000);}
		
*/
		//ImagePlus drawing=WindowManager.getImage("Drawing of "+title);
		//if(drawing==null) IJ.showMessage("No Drawing");
		boolean[] roiTaken=new boolean[rois.length];
		IJ.log("Converting to ShapeRois...");
		st=System.currentTimeMillis();
		for(int i=0;i<rois.length;i++) {
			rois[i]=getShapeRoi(rois[i]);
		}
		if(DEBUG) IJ.log("Done converting  "+(System.currentTimeMillis()-st)+"ms");
		if(DEBUG) IJ.log("Cellrois");
		
		//imp.getCanvas().addKeyListener(this);
		
		//First frame
		st=System.currentTimeMillis();
		int i=0;
		int n=0;
		for(i=0;i<rois.length;i++) {
			if(stop)return;
			Roi roi=rois[i];
			if(getZTPos(roi)>1)break;
			if(!isRoiWithinBounds(roi,0,MAX_ROI_SIZE)) {
				if(DEBUG) IJ.log("Roi "+i+" excluded because of size");
				roiTaken[i]=true;
			}
			if(!roiTaken[i]) {
				Cell cell=new Cell(roi);
				roiTaken[i]=true; n++;
				if(DEBUG) IJ.log("New cell "+n+" with roi "+i);
				//if(!IJ.showMessageWithCancel("CellFinder","New cell roi"+i))return;
				for(int p=0;p<PASSES;p++) {
					for(int j=i+1;j<rois.length;j++) {
						if(stop)return;
						if(roiTaken[j])continue;
						if(!isRoiWithinBounds(rois[j],0,MAX_ROI_SIZE)) {if(DEBUG) IJ.log("Roi "+j+" excluded because of size"); roiTaken[j]=true; continue;}
						if(getZTPos(rois[j])>1)break;
						if(cell.addIfTouching((ShapeRoi)rois[j])){
							roiTaken[j]=true;
							if(DEBUG) IJ.log("Added roi "+j+" to cell "+n+" at pass "+p);
						}
					}
				}
				if(cell.isCellOk(1)) {
					cell.smooth(1);
					cells.add(cell);
				}
				//finalize cell with enlarge and shrink to get rid of lines
			}
		}
		IJ.log("First frame took "+(System.currentTimeMillis()-st)+"ms");
		
		ImagePlus out=IJ.createImage("Output", "RGB white", imp.getWidth(), imp.getHeight(), imp.getNFrames());
		StackWindow sw=new StackWindow(out);
		sw.setVisible(true);
		out.getCanvas().addKeyListener(this);
		//imp.getCanvas().removeKeyListener(this);
		IJ.setForegroundColor(0, 0, 0);
		for(int c=0;c<cells.size();c++) {
			drawCell(c, 1, out);
		}
		//out.show();
		IJ.log("Found "+cells.size()+" cells");
		//Interactive part here combining cells, removing parts
		
		//stop=true;
		if(stop)return;
		
		//2-end frames
		IJ.log("Next frames");
		for(int f=2;f<=imp.getNFrames();f++) {
			ArrayList<Roi> conflictedRois=new ArrayList<Roi>();
			int starti=i;
			for(int c=0;c<cells.size();c++) {
				if(stop)return;
				Cell cell=cells.get(c);
				i=starti;
				IJ.log("\\Update:Working on frame: "+f+" cell"+(c+1));
				for(;i<rois.length;i++) {
					if(stop)return;
					Roi roi=rois[i];
					if(getZTPos(roi)>f)break;
					if(!isRoiWithinBounds(roi,0,MAX_ROI_SIZE)) {
						roiTaken[i]=true;
						continue;
					}
					boolean taken=roiTaken[i];
					roiTaken[i]=cell.addIfTouching((ShapeRoi)roi);
					if(roiTaken[i] && taken && !conflictedRois.contains(roi))conflictedRois.add(roi);
				}
				//if(cell.isCellOk(f)) {
					cell.smooth(f);
					IJ.setForegroundColor(0, 0, 0);
					drawCell(c, f, out);
					out.updateAndDraw();
				//}else {
				//	cell.deleteFr(f);
				//}
			}

			IJ.log("Identifying conflicted Rois");
			IJ.setForegroundColor(255, 0, 0);
			IJ.log("No conflicted rois");
			for(int j=0;j<conflictedRois.size();j++) {
				Roi roi=conflictedRois.get(j);
				if(out.isHyperStack())out.setPosition(out.getC(), out.getZ(), f);
				else out.setSlice(f);
				out.getProcessor().draw(roi);
				out.updateAndDraw();
				IJ.log("\\Update:Conflicted roi "+j);
			}
		}
		
	}
	
	public void drawCell(int cell, int frame, ImagePlus impout) {
		ShapeRoi[] cellShapes=cells.get(cell).cellShapes;
		if(cellShapes[frame-1]==null)return;
		if(impout.isHyperStack())impout.setPosition(impout.getC(), impout.getZ(), frame);
		else impout.setSlice(frame);
		ImageProcessor ip=impout.getProcessor();
		ip.draw(cellShapes[frame-1]);
		double[] cen=cellShapes[frame-1].getContourCentroid();
		ip.drawString(""+(cell+1), (int)cen[0], (int)cen[1]+15);
	}

	public static boolean areRoisTouching(ShapeRoi one, ShapeRoi two){
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
	
	static public ShapeRoi getShapeRoi(Roi roi) {
		if(roi instanceof ShapeRoi)return (ShapeRoi)roi;
		ShapeRoi res=new ShapeRoi(roi);
		res.setPosition(roi.getCPosition(), roi.getZPosition(), roi.getTPosition());
		return res;
	}
	
	public static int getZTPos(Roi roi) {
		int res=roi.getTPosition();
		return res>0?res:roi.getZPosition();
	}
	
	public boolean isRoiWithinBounds(Roi roi, int min, int max) {
		if(roi==null)return false;
		Rectangle r=roi.getBounds();
		int area=r.width*r.height;
		if(area<0)IJ.log("area was negative");
		if(area==0)IJ.log("area was 0");
		if(area>max)IJ.log("area was greater than max");
		return (area>min && area<max);
	}
	
	public class Cell{
		public ArrayList<ArrayList<Roi>> cellRoisFrms;
		public ShapeRoi[] cellShapes;
		public ShapeRoi maxShape;
		public ShapeRoi minShape;
		
		public Cell(Roi roi) {
			cellRoisFrms=new ArrayList<ArrayList<Roi>>(imp.getNFrames());
			for(int i=0;i<imp.getNFrames();i++){
				cellRoisFrms.add(new ArrayList<Roi>());
			}
			//IJ.log("\\Update:cellRois.size "+cellRoisFrms.size());
			cellShapes=new ShapeRoi[imp.getNFrames()];
			addRoi(roi);
		}
		
		/**
		 * 
		 * @param roi
		 * @return true if it was touching and was added, false if not
		 */
		public boolean addIfTouching(ShapeRoi roi) {
			int fr=getZTPos(roi)-1;
			if(fr<0)IJ.log("Roi does not have Frame info: "+fr);
			if(fr==0 && cellShapes[fr]==null){IJ.log("Cell has not been initialized yet "+fr);return false;}
			if(fr>0)fr--; //First slice compare to same, others compare to one before
			ShapeRoi croi=cellShapes[fr];
			while(croi==null && fr>0)croi=cellShapes[--fr];
			if(areRoisTouching(croi,roi)) {
				addRoi(roi);
				return true;
			}
			return false;
		}
		
		public void addRoi(Roi roi) {
			int fr=getZTPos(roi)-1;
			//IJ.log("Pos "+roi.getCPosition()+" "+roi.getZPosition()+" "+roi.getTPosition());
			if(fr<0)IJ.log("Roi does not have Frame info: "+fr);
			ArrayList<Roi> rois=cellRoisFrms.get(fr);
			if(!rois.contains(roi)) {rois.add(roi);}
			if(rois.size()==1) {
				if(roi instanceof ShapeRoi) {cellShapes[fr]=(ShapeRoi)(roi.clone());}
				else {cellShapes[fr]=new ShapeRoi(roi);}
			}else {
				if(roi instanceof ShapeRoi)cellShapes[fr]=cellShapes[fr].or((ShapeRoi)roi);
				else cellShapes[fr]=cellShapes[fr].or(new ShapeRoi(roi));
			}
		}
		
		public void removeRoi(Roi roi) {
			int fr=getZTPos(roi)-1;
			ArrayList<Roi> rois=cellRoisFrms.get(fr);
			if(rois.remove(roi)) {
				if(rois.size()==0) {
					cellShapes[fr]=null;
				}else {
					if(roi instanceof ShapeRoi)cellShapes[fr]=cellShapes[fr].not((ShapeRoi)roi);
					else cellShapes[fr]=cellShapes[fr].not(new ShapeRoi(roi));
				}
			}
		}
		
		public void deleteFr(int frame) {
			cellRoisFrms.remove(frame-1);
			cellRoisFrms.add(frame-1, new ArrayList<Roi>());
			cellShapes[frame-1]=null;
		}
		
		public void remakeShape(int frame) {
			ArrayList<Roi> rois=cellRoisFrms.get(frame-1);
			for(int i=0;i<rois.size(); i++) {
				Roi roi=rois.get(i);
				if(i==0) {
					if(roi instanceof ShapeRoi)cellShapes[frame-1]=(ShapeRoi)(roi.clone());
					else cellShapes[frame-1]=new ShapeRoi(roi);
				}
				else {
					if(roi instanceof ShapeRoi)cellShapes[frame-1]=cellShapes[frame-1].or((ShapeRoi)roi);
					else cellShapes[frame-1]=cellShapes[frame-1].or(new ShapeRoi(roi));
				}
			}
			smooth(frame);
		}
		
		public void remakeShapes() {
			for(int i=0;i<imp.getNFrames();i++)remakeShape(i+1);
		}
		
		public boolean isCellOk(int frame) {
			Roi roi=cellShapes[frame-1];
			if(roi==null)return false;
			Rectangle r=roi.getBounds();
			int a=(r.width*r.height);
			if(a<MIN_CELL_SIZE || a>MAX_CELL_SIZE)return false;
			return true;
			
		}
		
		public void smooth(int frame) {
			if(cellShapes[frame-1]!=null) {
				cellShapes[frame-1]=enlargeRoi(enlargeRoi(cellShapes[frame-1],FUZZD),-FUZZD);
			}
		}
		
		public void buildMaxMinShapes() {
			maxShape=cellShapes[0];
			minShape=cellShapes[0];
			for(int i=0;i<cellShapes.length;i++) {
				if(cellShapes[i]==null)continue;
				maxShape=maxShape.or(cellShapes[i]);
				minShape=minShape.and(cellShapes[i]);
			}
		}
	}

	@Override
	public void keyTyped(KeyEvent e) {
		int keyCode=e.getKeyCode();
		if(keyCode==KeyEvent.VK_ESCAPE) {
			
		}
	}

	@Override
	public void keyPressed(KeyEvent e) {
	}

	@Override
	public void keyReleased(KeyEvent e) {
	}

}
