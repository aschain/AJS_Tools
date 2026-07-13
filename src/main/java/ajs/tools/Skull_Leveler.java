package ajs.tools;
import java.util.ArrayList;
import java.awt.AWTEvent;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.TextField;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.DialogListener;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.CurveFitter;
import ij.plugin.*;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

public class Skull_Leveler implements PlugIn {
		int mapBlurSigma=50;
		final String[] METHODS=new String[]{"Mean","Median","Mid","Top 1/3","MaxMean","X-Mean", "Set Threshold Below"};
		//Mean, Median, Mid, and Top 1/3 are calculated on each z-column to generate the threshold
		//MaxMean takes the mean of the z-max of channel 1
		//X-Mean just the mean of the current y-slice not the whole xy max proj of the skull
		//final String[] METHODS_MP=new String[]{"Min","Two-Mac"};
		//final String[] MAPTYPES=new String[]{"1st Channel - Skull", "2nd Channel - Mac bi-layer"};
		final String[] TMETHODS=new String[] {"First Frame", "Ave T-projection", "For Each Frame"};
        final String[] SkullLevelingMethods=new String[]{"Direct Flattening CPU", "Thin Plate Spline GPU", "Thin Plate Spline CPU", "Do not flatten"};
		enum SLM {DIRECT_CPU, TPS_GPU, TPS_CPU, NONE};
        final int MINXMEANVAL=50;
		final String MAP_SUFFIX="-SkullLeveler-map";
		//String maptype=MAPTYPES[0];
		String tmethod=TMETHODS[0];
		int rfd=3;
		int red_fac=1;
		int medFilterWidth=25;
		String method="X-Mean";
		int thresh=-1; //If defined, use this as the thresh instead of calculating
		int FIT=CurveFitter.POLY2;
		boolean reExpandSmoothMap=false;
		boolean fromTop=false;
        ImagePlus oimp=null, mapimp=null, finalimp=null, rsimp=null;
    
    public void run(String arg) {
        SkullLeveler(null);
    }



    public void SkullLeveler(ImagePlus imp){
		boolean doResliceROIs=false;
        oimp=imp;
		if(oimp==null)oimp=WindowManager.getCurrentImage();
		if(oimp==null) {IJ.noImage();return;}
		String title=oimp.getTitle();
		
		String[] imtitles=WindowManager.getImageTitles();
		if(title.contains(MAP_SUFFIX)) {
            String altTitle=title.substring(0,title.indexOf(MAP_SUFFIX));
            if(WindowManager.getImage(altTitle)!=null){
			    oimp=WindowManager.getImage(altTitle);
            }else if(WindowManager.getImage(altTitle+".tif")!=null){
			    oimp=WindowManager.getImage(altTitle+".tif");
            }
            title=oimp.getTitle();
		}
        String basetitle=title.endsWith(".tif")?title.substring(0,title.length()-4):title;
		ArrayList<String> mapnames=new ArrayList<String>();
		mapnames.add("Generate new map");
        mapnames.add("Import Points from CSV");
		for(int i=0;i<imtitles.length;i++) {
			if(imtitles[i].contains(MAP_SUFFIX)) {mapnames.add(imtitles[i]);}
		}
        Roi roi=oimp.getRoi();
        double pts[][]=null;
        if(roi!=null && roi.getType()==Roi.POINT && roi.getFloatPolygon().npoints>3) {
            pts=new double[roi.getFloatPolygon().npoints][3];
            for(int i=0;i<pts.length;i++) {
                pts[i][0]=roi.getFloatPolygon().xpoints[i];
                pts[i][1]=roi.getFloatPolygon().ypoints[i];
                int slice=((ij.gui.PointRoi)roi).getPointPosition(i);
                if(slice<=0)slice=oimp.getCurrentSlice();
                pts[i][2]=(double)oimp.convertIndexToPosition(slice)[1];
            }
            mapnames.add("Use ROI Points");
        }
		String mapname="";
		String defaultMapName=mapnames.get(0);
        if(WindowManager.getImage(basetitle+MAP_SUFFIX)!=null)defaultMapName=basetitle+MAP_SUFFIX;
        else if(WindowManager.getImage(title+MAP_SUFFIX)!=null)defaultMapName=title+MAP_SUFFIX;

		GenericDialog gd=new GenericDialog("SkullLeveler");
		gd.addMessage("Working on "+title);
		gd.addChoice("Use Open SkullMap:", (String[])mapnames.toArray(new String[mapnames.size()]), defaultMapName);
		gd.addMessage("Map Generation Options: ");
		gd.addChoice("Threshold Method:",METHODS,method);
		gd.addNumericField("Set Threshold:",thresh,0);
		if(oimp.getNFrames()>1)gd.addChoice("Map generation for frames?", TMETHODS, TMETHODS[2]);
		gd.addCheckbox("Decide position from top?", fromTop);
		gd.addNumericField("XY-Smoothing (downsize)",red_fac,0);
		gd.addNumericField("XY-Smoothing (median)",medFilterWidth,0);
		gd.addNumericField("Z-Smoothing",rfd,0);
		gd.addCheckbox("Re-expand and smooth map?", reExpandSmoothMap);
		gd.addNumericField("Gaussian Map Smooth Sigma",mapBlurSigma,0);
		//gd.addCheckbox("Nonflat zero?",nonflatzero);
        gd.addMessage("Skull-Leveling Options: ");
		gd.addChoice("Skulllevel stack?", SkullLevelingMethods, SkullLevelingMethods[0]);
        gd.addMessage("Visualization Options: ");
		gd.addCheckbox("Draw reslice ROIs?", doResliceROIs);
		//gd.addChoice("Use Channel and Type:",MAPTYPES,maptype);

        final Choice mapChoice = (Choice) gd.getChoices().get(0);
        final Choice thresholdChoice = (Choice) gd.getChoices().get(1);
        final Choice frameChoice = (oimp.getNFrames() > 1) ? (Choice) gd.getChoices().get(2) : null;
        final Checkbox fromTopCheckbox = (Checkbox) gd.getCheckboxes().get(0);
        final Checkbox reExpandCheckbox = (Checkbox) gd.getCheckboxes().get(1);
        @SuppressWarnings("unchecked")
        final java.util.Vector<TextField> numericFields = gd.getNumericFields();
        DialogListener toggleMapGenerationOptions = new DialogListener() {
            @Override
            public boolean dialogItemChanged(GenericDialog dialog, AWTEvent e) {
                boolean enableMapGenerationOptions = mapChoice.getSelectedItem().contentEquals("Generate new map");
                thresholdChoice.setEnabled(enableMapGenerationOptions);
                boolean settingThreshold = thresholdChoice.getSelectedItem().contentEquals("Set Threshold Below");
                if(frameChoice != null) frameChoice.setEnabled(enableMapGenerationOptions);
                fromTopCheckbox.setEnabled(enableMapGenerationOptions);
                reExpandCheckbox.setEnabled(enableMapGenerationOptions);
                for(int i = 0; i < 5 && i < numericFields.size(); i++) {
                    if(i==0)numericFields.get(i).setEnabled(enableMapGenerationOptions && settingThreshold);
                    else numericFields.get(i).setEnabled(enableMapGenerationOptions);
                }
                return true;
            }
        };
        gd.addDialogListener(toggleMapGenerationOptions);
        toggleMapGenerationOptions.dialogItemChanged(gd, null);
        
		gd.showDialog();
		if(gd.wasCanceled()){oimp=null; return;}

		mapname=gd.getNextChoice();
		method=gd.getNextChoice();
		thresh=(int)gd.getNextNumber();
		if(oimp.getNFrames()>1)tmethod=gd.getNextChoice();
		fromTop=gd.getNextBoolean();
		red_fac=(int)gd.getNextNumber();
		medFilterWidth=(int)gd.getNextNumber();
		rfd=(int)gd.getNextNumber();
		reExpandSmoothMap=gd.getNextBoolean();
		mapBlurSigma=(int)gd.getNextNumber();
        SLM skullLevelMethod=SLM.values()[gd.getNextChoiceIndex()];
		doResliceROIs=gd.getNextBoolean();
		//maptype=gd.getNextChoice();

        if(method.contentEquals("Set Threshold Below") && thresh<=0) {
            IJ.error("Please set a positive threshold value");
            return;
        }

		if(mapname.contains(MAP_SUFFIX))mapimp=WindowManager.getImage(mapname);
		if(mapname.contentEquals("Use ROI Points") && pts!=null) {
            if(skullLevelMethod==SLM.DIRECT_CPU) {
                mapimp=roisToMap(oimp);
            }
        }
        if(mapname.contentEquals("Import Points from CSV")){
            String path=IJ.getFilePath("Select CSV with control points (x,y,z) for TPS flattening");
            if(path==null)return;
            String[] lines=AJ_Misc_Plugins.readFile(path);
            if(lines==null || lines.length==0) {
                IJ.error("No lines read from file");
                return;
            }
            String labelFilter=null;
            if(lines[0].startsWith("Label")){
                ArrayList<String> labels=new ArrayList<String>();
                for(String line: lines) {
                    if(line.startsWith("Label")) continue;
                    String[] parts=line.split("[,\t ]+");
                    String label=parts[Thresh_Cell_Transfer.HEADINGS.LABEL.getIndex()].trim();
                    if(!labels.contains(label))labels.add(label);
                }
                labels.add("All");
                gd=new GenericDialog("Select label for TPS control points");
                gd.addChoice("Which Label:", labels.toArray(new String[labels.size()]), labels.get(0));
                gd.showDialog();
                if(gd.wasCanceled()){return;}
                labelFilter=gd.getNextChoice();
                if(labelFilter.contentEquals("All"))labelFilter=null;
            }
            pts=AJ_Misc_Plugins.getPointsFromCSV(lines, labelFilter, 1, oimp);
            AJ_Misc_Plugins.ptsToPointRoi(pts, oimp);
            if(skullLevelMethod==SLM.DIRECT_CPU) {
                mapimp=roisToMap(oimp);
            }
        }
        if(mapname.contentEquals("Generate new map"))generateSkullMap();
        if(skullLevelMethod==SLM.DIRECT_CPU){
            if(mapimp!=null)skullLevel();
            else return;
        } else if(skullLevelMethod!=SLM.NONE) {
            if(pts==null && mapimp!=null){
                pts=HeightmapToTPS.extractControlPointsAdaptive(
                    mapimp, mapimp.getWidth()/16, 200, 10
                );
                if(oimp.getWidth()!=mapimp.getWidth() || oimp.getHeight()!=mapimp.getHeight()) {
                    for(double[] pt : pts) {
                        pt[0] = pt[0] * oimp.getWidth() / (double) mapimp.getWidth();
                        pt[1] = pt[1] * oimp.getHeight() / (double) mapimp.getHeight();
                    }
                }
            }
            IJ.log("TPS Flattening with "+pts.length+" control points");
            TPS_Flatten tpsFlatten=new TPS_Flatten();
            tpsFlatten.flattenStack(oimp, pts, skullLevelMethod==SLM.TPS_GPU);
        }
        if(doResliceROIs && mapimp!=null) resliceROIs();
    }
        
	public ImagePlus generateSkullMap() {
        if(oimp==null)return null;
        
		int h=oimp.getHeight(), w=oimp.getWidth(), sls=oimp.getNSlices(), frms=oimp.getNFrames();
        String title=oimp.getTitle();
        String basetitle=title.endsWith(".tif")?title.substring(0,title.length()-4):title;
		String maptitle=basetitle+MAP_SUFFIX;
		ImageStack mapstack=null;
		FloatProcessor fp=null;
		float zmin=65535, zmax=0;

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
        IJ.showStatus("XY Smoothing  - downsize");
        dimp=AJ_Misc_Plugins.resizer(dimp, dimp.getWidth()/red_fac, dimp.getHeight()/red_fac, sls, ffrms);
        IJ.showStatus("Finished resizing");
        Calibration cal=dimp.getCalibration();
        cal.fps=1; cal.pixelWidth=1; cal.pixelHeight=1; cal.pixelDepth=1;
        dimp.setCalibration(cal);
        dimp.show();

        //"Despeckle" or median filter for xy image smoothing
        ij.plugin.filter.RankFilters rf=new ij.plugin.filter.RankFilters();
        ImageStack dst=dimp.getStack();
        if(medFilterWidth>0){
            IJ.showStatus("XY Smoothing  - median");
            for(int i=0;i<dst.getSize();i++) {
                IJ.showProgress((double)(i+1)/(double)dst.getSize());
                ImageProcessor ip=dst.getProcessor(i+1);
                rf.rank(ip, medFilterWidth/red_fac, ij.plugin.filter.RankFilters.MEDIAN);
            }
        }
        if(method.contentEquals("MaxMean") && thresh<0){
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

        dimp=AJ_Misc_Plugins.resizer(dimp, dimp.getWidth(), dimp.getHeight(), sls*10, ffrms);
        dst=dimp.getStack();
        dimp.show();
        IJ.showStatus("Finished resizing sls*10");
        int rh=dimp.getHeight(), rw=dimp.getWidth(), rfrms=dimp.getNFrames(), rsls=dimp.getNSlices();

        mapstack=new ImageStack(rw,rh);
        CurveFitter cf=null;
        double[] xline=new double[rsls];
        for(int i=0;i<xline.length;i++)xline[i]=(double)i;
        
        //IJ.log("rfrms"+rfrms+" dimpf"+dimp.getNFrames());
        for(int fr=0;fr<rfrms;fr++) {
            fp=new FloatProcessor(rw,rh);
            IJ.showStatus("Generating Skull Map Frame "+(fr+1)+" / "+rfrms);
            for(int y=0;y<rh;y++){
                IJ.showProgress((double)(y+fr*rh)/(double)(rh*rfrms));
                if(method.contentEquals("X-Mean") && thresh<0){
                    int mean=0;
                    for(int x=0;x<rw;x++){
                        for(int z=0;z<rsls;z++){
                            int val=dst.getProcessor(z+fr*rsls+1).get(x,y);
                            if(val>MINXMEANVAL)mean+=val;
                        }
                    }
                    mean/=(rw*rsls);
                    thresh=mean;
                }
                for(int x=0;x<rw;x++){
                    double[] zline=new double[rsls];
                    for(int z=0;z<rsls;z++){
                        zline[z]=(double)dst.getProcessor(z+fr*rsls+1).get(x,y);
                    }
                    float skullz=0;
                    if(method.contentEquals("CurveFit")) {
                        cf=new CurveFitter(xline,zline);
                        cf.doFit(FIT);
                        if(cf.getStatus()!=ij.measure.Minimizer.INITIALIZATION_FAILURE){
                            double[] params=cf.getParams();
                            if(FIT==CurveFitter.POLY2){
                                //for ax2+bx+c=y, params are returned [0] is c, [1] is b, [2] is a.
                                //and the minimum of a polynomial is -b/2a
                                //CurveFitter actually calls the parameters cx2+bx+a instead of ax2+bx+c
                                float ytmp=(float)(-params[1]/(2*params[2]));
                                if(ytmp>0 && ytmp<(h-1))skullz=ytmp/10f;
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
                        if(fromTop){
                            int i=0;
                            while(i<zlineThresh.length && zlineThresh[i]==0)i++;
                            while(i<zlineThresh.length && zlineThresh[i]>0)i++;
                            if(i<zlineThresh.length && zlineThresh[i]==0 && i>0)skullz=(float)(i-1)/10f;
                        }else{
                            for(int i=zlineThresh.length-1;i>=0;i--){
                                if(zlineThresh[i]>0){skullz=(float)i/10f; break;}
                            }
                        }
                    }
                    //skullz+=-rise;
                    fp.setf(x, y, skullz);
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
        dimp.changes=false;
        dimp.close();
        mapimp.setProperty("Info","Z-Range:"+zmin+":"+zmax+"\n"+"Method:"+tmethod+"\n");
        if(reExpandSmoothMap) {
            if(mapimp.getWidth()!=oimp.getWidth() || mapimp.getHeight()!=oimp.getHeight()) mapimp=AJ_Misc_Plugins.resizer(mapimp, oimp.getWidth(), oimp.getHeight(), 1, mapimp.getNFrames());
            mapstack=mapimp.getStack();
            if(mapBlurSigma>0){
                ij.plugin.filter.GaussianBlur gb=new ij.plugin.filter.GaussianBlur();
                for(int i=1;i<=mapstack.getSize();i++) {
                    gb.blurGaussian(mapstack.getProcessor(i), mapBlurSigma);
                }
            }
        }
        int miw=mapimp.getWidth();
        red_fac=w/miw;
        mapimp.show();
        return mapimp;
    }

    public ImagePlus skullLevel() {
        if(oimp==null)return null;
		if(mapimp==null) {
            IJ.error("No Skull Map");
            return null;
        }
        long startTime = System.currentTimeMillis();
        int h=oimp.getHeight(), w=oimp.getWidth(), chs=oimp.getNChannels(), sls=oimp.getNSlices(), frms=oimp.getNFrames();
        float mwf=(float)w/(float)mapimp.getWidth();
        IJ.showStatus("Skull-Leveling "+oimp.getTitle());
        FloatProcessor fp=(FloatProcessor)mapimp.getProcessor();
        ImageStack mapstack=mapimp.getStack();
        int zmin=65535, zmax=-65535;
        for(int fr=0;fr<mapstack.size();fr++) {
            ImageProcessor ip=mapstack.getProcessor(fr+1);
            int tmin=(int)ip.getMin();
            int tmax=(int)Math.ceil(ip.getMax());
            if(tmin<zmin)zmin=tmin;
            if(tmax>zmax)zmax=tmax;
        }
        int finalsls=(int)(sls+(zmax-zmin));
        //if(!expand)finalsls=sls;
        finalimp=IJ.createImage(oimp.getTitle()+"-SkullLeveled", ""+oimp.getBitDepth()+"-bit"+(oimp.isComposite()?" composite":""), w, h, chs, finalsls, frms);
        ImageStack finalstack=finalimp.getStack();
        boolean fullstack=mapstack.getSize()==frms;

        for(int fr=0; fr<frms; fr++){
            if(fullstack)fp=(FloatProcessor)(mapstack.getProcessor(fr+1));
            final int frf = fr;
            for(int sl=0; sl<sls; sl++){
                IJ.showStatus("SkullLeveling "+" T"+(fr+1)+" Z"+(sl+1));
                final int slf=sl;
                for(int ch=0; ch<chs; ch++){
                    final int channel=ch;
                    final FloatProcessor finalfp=fp;
                    final int zmaxf=zmax;
                    IJ.showProgress((double)(ch+slf*chs+fr*sls*chs+1)/(double)(chs*sls*frms));
                    ImageProcessor ip=oimp.getStack().getProcessor(oimp.getStackIndex(ch+1,slf+1,fr+1));
                    
                    int procn=Runtime.getRuntime().availableProcessors();
                    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(procn);
                    java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                    for(int p=0;p<procn;p++){
                        final int pf=p;
                        futures.add(executor.submit(() -> {
                            for (int y = pf*h/procn; y < ((pf+1)*h/procn); y++) {
                                for (int x = 0; x < w; x++) {
                                    int xfp = Math.min((int) (x / mwf), finalfp.getWidth() - 1);
                                    int yfp = Math.min((int) (y / mwf), finalfp.getHeight() - 1);
                                    float zskull = finalfp.getf(xfp, yfp);
                                    float finalsl = (float) slf + 1f - (zskull - (float) zmaxf);
                                    int value = ip.get(x, y);
                                    if (finalsl > Math.floor(finalsl)) {
                                        ImageProcessor hip = oimp.getStack().getProcessor(oimp.getStackIndex(channel + 1, slf, frf + 1));
                                        float high = finalsl - (float) Math.floor(finalsl), low = 1f - high;
                                        value = (int) ((float) value * low + hip.getf(x, y) * high);
                                    }
                                    finalstack.getProcessor(finalimp.getStackIndex(channel + 1, (int) finalsl, frf + 1)).set(x, y, value);
                                }
                            }
                        }));
                    }
                    for (java.util.concurrent.Future<?> f : futures) {
                        try { f.get(); } catch (Exception e) { e.printStackTrace(); }
                    }
                    executor.shutdown();
                }
            }
        }
        finalimp.setCalibration(oimp.getCalibration());
        finalimp.show();
        if(finalimp instanceof CompositeImage && oimp instanceof CompositeImage)((CompositeImage)finalimp).setLuts(((CompositeImage)oimp).getLuts());
        finalimp.updateAndDraw();
        Slicelabel_Transfer.transferSliceLabels(oimp, finalimp);
        IJ.showStatus("Skull-Leveler complete");
        IJ.log("Skull-Leveler time: " + String.format("%.2f", (System.currentTimeMillis()-startTime) / 1000.0) + " seconds");

		return finalimp;
	}
    
    public ImagePlus resliceROIs() {
        IJ.showStatus("Reslicing ROIs");
        FloatProcessor fp=(FloatProcessor)mapimp.getProcessor();
        ImagePlus rsimp=WindowManager.getImage("Reslice of "+oimp.getTitle());
        if(rsimp==null) {
            WindowManager.setTempCurrentImage(oimp);
            oimp.deleteRoi();
            IJ.run("Reslice [/]...", "start=Top");
            rsimp=WindowManager.getCurrentImage();
        }
        rsimp.setOverlay(new Overlay());
        int zprev=0;
        for(int i=0;i<mapimp.getHeight();i++) {
            int z=(int)Math.floor((double)i/(double)mapimp.getHeight()*(double)rsimp.getNSlices())+1;
            if(z>zprev){
                zprev=z;
                int[] xpoints=new int[mapimp.getWidth()];
                int[] ypoints=new int[mapimp.getWidth()];
                for(int j=0;j<mapimp.getWidth();j++) {
                    xpoints[j]=(int)((double)j*rsimp.getWidth()/(double)mapimp.getWidth());
                    ypoints[j]=(int)((double)fp.getf(j,i)*(double)rsimp.getHeight()/(double)oimp.getNSlices());
                }
                ij.gui.PolygonRoi proi=new ij.gui.PolygonRoi(xpoints, ypoints, xpoints.length, Roi.POLYLINE);
                if(rsimp.isHyperStack())proi.setPosition(0,z,0);
                else proi.setPosition(z);
                rsimp.setPosition(1, z, 1);
                rsimp.getOverlay().add(proi);
            }
        }
        IJ.showStatus("Reslice ROIs complete");
        return rsimp;
    }

    public ImagePlus roisToMap(ImagePlus imp) {
        if(imp==null){IJ.noImage(); return null;}
        Roi roi=imp.getRoi();
        if(roi==null || roi.getType()!=Roi.POINT) {
            IJ.error("No PointRoi selected");
            return null;
        }
        PointRoi pointRoi=(PointRoi)roi;
        if(pointRoi.getFloatPolygon().npoints<4) {
            IJ.error("PointRoi must contain at least 4 points for TPS map generation");
            return null;
        }

        int n=pointRoi.getFloatPolygon().npoints;
        double[][] src=new double[n][3];
        double avgZ=0.0;
        for(int i=0;i<n;i++) {
            src[i][0]=pointRoi.getFloatPolygon().xpoints[i];
            src[i][1]=pointRoi.getFloatPolygon().ypoints[i];
            int slice=pointRoi.getPointPosition(i);
            if(slice<=0){IJ.error("PointRoi points must have a slice position"); return null;}
            src[i][2]=(double)imp.convertIndexToPosition(slice)[1];
            avgZ+=src[i][2];
        }
        avgZ/=n;

        double[][] srcPts=new double[n*2][3];
        double[][] tgtPts=new double[n*2][3];
        for(int i=0;i<n;i++) {
            srcPts[i][0]=src[i][0];
            srcPts[i][1]=src[i][1];
            srcPts[i][2]=src[i][2];
            srcPts[i+n][0]=src[i][0];
            srcPts[i+n][1]=src[i][1];
            srcPts[i+n][2]=src[i][2]+10.0;

            tgtPts[i][0]=src[i][0];
            tgtPts[i][1]=src[i][1];
            tgtPts[i][2]=avgZ;
            tgtPts[i+n][0]=src[i][0];
            tgtPts[i+n][1]=src[i][1];
            tgtPts[i+n][2]=avgZ+10.0;
        }

        // Inverse TPS (target -> source) lets us query source z at each flattened (x,y,avgZ).
        ThinPlateSpline3D inverseTps;
        try {
            inverseTps=new ThinPlateSpline3D(srcPts, tgtPts, 1e-3);
        } catch(Exception e) {
            IJ.error("Failed to fit TPS for map generation\n"+e.getLocalizedMessage());
            return null;
        }

        int w=imp.getWidth(), h=imp.getHeight();
        FloatProcessor fp=new FloatProcessor(w, h);
        float zmin=Float.MAX_VALUE, zmax=-Float.MAX_VALUE;
        for(int y=0;y<h;y++) {
            IJ.showProgress((double)(y+1)/(double)h);
            for(int x=0;x<w;x++) {
                double[] sourcePt=inverseTps.transform(new double[]{x, y, avgZ});
                float z=(float)sourcePt[2];
                fp.setf(x, y, z);
                if(z<zmin)zmin=z;
                if(z>zmax)zmax=z;
            }
        }
        float zcap=zmax-zmin;
        if(zcap > (float)imp.getNSlices()) {
            zcap=(float)imp.getNSlices();
        }
        for(int y=0;y<h;y++) {
            IJ.showProgress((double)(y+1)/(double)h);
            for(int x=0;x<w;x++) {
                float z=fp.getf(x,y)-zmin;
                if(z>zcap) z=zcap;
                fp.setf(x,y,z);
            }
        }
        IJ.log("Generated TPS map from PointRoi with "+n+" points. Z-Range: 0 to "+IJ.d2s(zmax-zmin,3)+" capped to "+IJ.d2s(zcap,3));
        ImagePlus res=new ImagePlus(imp.getTitle()+MAP_SUFFIX, fp);
        java.awt.image.IndexColorModel cm=ij.plugin.LutLoader.getLut("Thermal");
        if(cm!=null)
            res.setLut(new ij.process.LUT(cm,zmin,zmax));
        res.show();
        return res;
    }
}
