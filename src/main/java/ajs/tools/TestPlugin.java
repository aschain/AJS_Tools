package ajs.tools;

import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.awt.Rectangle;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Line;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;

/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class TestPlugin implements PlugIn {
	/**
	 * This method gets called by ImageJ / Fiji.
	 *
	 * @param arg can be specified in plugins.config
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	private static final double START_MULT=2.0;
	private volatile boolean end=false;
	private Rectangle bounds=null;
	 
	 
	@Override
	public void run(String arg) {
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage();return;}
		GenericDialog gd=new GenericDialog("Mult");
		gd.addNumericField("Multiplier:", START_MULT);
		gd.showDialog();
		if(gd.wasCanceled())return;
		final double mult=gd.getNextNumber();
		Roi roi=imp.getRoi();
		if(roi!=null){
			bounds=roi.getBounds();
			imp.resetRoi();
		}else bounds=new Rectangle(0,0,imp.getWidth(),imp.getHeight());
		final int xb=bounds.x, yb=bounds.y, wb=bounds.width, hb=bounds.height;
		final ImageProcessor ip=imp.getProcessor();
		ip.snapshot();
		IJ.setTool("Line");
		Thread thread=new Thread() {
			public void run() {
				int[] prevxs=new int[] {0,0}, prevys=new int[] {0,0};
				while(!end) {
					Roi croi=imp.getRoi();
					if(croi!=null && croi.getType()==Roi.LINE) {
						Line line=(Line)croi;
						int[] xs=line.getPolygon().xpoints;
						int[] ys=line.getPolygon().ypoints;
						if(!(xs[0]==prevxs[0] && xs[1]==prevxs[1] && ys[0]==prevys[0] && ys[1]==prevys[1])) {
							ip.reset();
							multsmooth(ip,line, mult);
							imp.updateAndDraw();
							prevxs[0]=xs[0]; prevxs[1]=xs[1]; prevys[0]=ys[0]; prevys[1]=ys[1];
						}
					}
				}
			}
		};
		thread.start();
		WaitForUserDialog wfu=new WaitForUserDialog("Draw Line in direction of increasing mult");
		wfu.setLocation((int)imp.getWindow().getLocationOnScreen().getX()+imp.getWindow().getWidth()+4, (int)imp.getWindow().getLocationOnScreen().getY());
		wfu.setVisible(true);
		end=true;
		roi=imp.getRoi();
		if(wfu.escPressed() || roi==null || roi.getType()!=Roi.LINE) {ip.reset(); imp.resetRoi(); imp.setRoi(roi); return;}
		if(imp.getStackSize()==1)return;
		ip.reset();
		for(int i=0;i<imp.getStackSize();i++) {
			ImageStack imst=imp.getStack();
			multsmooth(imst.getProcessor(i+1),(Line)roi,mult);
			IJ.showProgress(((double)i+1.0)/imp.getStackSize());
		}
		imp.updateAndDraw();
	}
	
	public void multsmooth(ImageProcessor ip, Line line, double mult) {
		int[] xs=line.getPolygon().xpoints;
		int[] ys=line.getPolygon().ypoints;
		double angle=line.getAngle();
		double len=line.getRawLength();
		double wb=(double)bounds.width;
		double hb=(double)bounds.height;
		double xf=(len*Math.cos(Math.PI/180.0*Math.abs(angle))/wb);
		double yf=(len*Math.sin(Math.PI/180.0*Math.abs(angle))/hb);
		for(double x=bounds.x; x<bounds.width; x++) {
			int xc=(int)x;
			if(xs[0]>xs[1])x=wb-x-1;
			double xmult=(mult-1)*xf*(x/wb);
			for(double y=bounds.y;y<hb;y++) {
				int yc=(int)y;
				if(ys[0]>ys[1])y=hb-y-1;
				double xymult=1+((mult-1)*yf*(y/hb)+xmult);
				ip.putPixel(xc,yc,(int)(ip.get(xc,yc)*xymult));
			}
		}
	}
}