package ajs.tools;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

public class StackTrimmer implements PlugIn {

	@Override
	public void run(String arg) {
		trimStackWithDialog(WindowManager.getCurrentImage());
	}
	
	public static void trimStackWithDialog(ImagePlus imp) {
		trimStackWithDialog(imp, 1, 1, 1, imp.getNChannels(), imp.getNSlices(), imp.getNFrames());
	}
	
	public static void trimStackWithDialog(ImagePlus imp, int chst, int slst, int frst, int chend, int slend, int frend) {
		if(imp==null) {IJ.noImage();return;}
		int chs=imp.getNChannels(), sls=imp.getNSlices(), frms=imp.getNFrames();
		int[] chR=new int[] {0,imp.getNChannels()};
		int[] slR=new int[] {0,imp.getNSlices()};
		int[] frR=new int[] {0,imp.getNFrames()};
		GenericDialog gd=new GenericDialog("Stack Trimmer");
		gd.addMessage("Currently on ch:"+imp.getC()+" sl:"+imp.getZ()+" fr:"+imp.getT());
		if(chs>1)gd.addStringField("Keep Channels:", AJ_Utils.deParseRange(chst-1,chend));
		if(sls>1)gd.addStringField("Keep Slices:", AJ_Utils.deParseRange(slst-1,slend));
		if(frms>1)gd.addStringField("Keep Frames:", AJ_Utils.deParseRange(frst-1,frend));
		gd.showDialog();
		if(gd.wasCanceled())return;
		if(chs>1)chR=AJ_Utils.parseRange(gd.getNextString());
		if(sls>1)slR=AJ_Utils.parseRange(gd.getNextString());
		if(frms>1)frR=AJ_Utils.parseRange(gd.getNextString());
		trimStack(imp,chR[0]+1,slR[0]+1,frR[0]+1,chR[1],slR[1],frR[1]);
	}
	
	public static void trimStackFront() {
		trimStackFront(WindowManager.getCurrentImage());
	}
	
	public static void trimStackBack() {
		trimStackBack(WindowManager.getCurrentImage());
	}
	
	public static void trimStackFront(ImagePlus imp) {
		int ch=imp.getC(),sl=imp.getZ(),fr=imp.getT(), chs=imp.getNChannels(), sls=imp.getNSlices(), frms=imp.getNFrames();
		if(ch==1 && sl==1 && fr==1) {IJ.error("No slices before current position: ch:1 sl:1 fr:1");return;}
		int och=ch,osl=sl,ofr=fr;
		GenericDialog gd=new GenericDialog("Front Deleter");
		if(chs>1)gd.addCheckbox("Delete Channels before "+ch+"?", sl==1 && fr==1);
		if(sls>1)gd.addCheckbox("Delete Slices before "+sl+"?", sl>1 && fr==1);
		if(frms>1)gd.addCheckbox("Delete Frames before "+fr+"?", fr>1);
		gd.showDialog();
		if(gd.wasCanceled())return;
		if(chs>1 && !gd.getNextBoolean())ch=1;
		if(sls>1 && !gd.getNextBoolean())sl=1;
		if(frms>1 && !gd.getNextBoolean())fr=1;
		trimStack(imp,ch,sl,fr,chs,sls,frms);
		imp.setPosition(ch==1?och:1, sl==1?osl:1, fr==1?ofr:1);
	}
	
	public static void trimStackBack(ImagePlus imp) {
		int ch=imp.getC(),sl=imp.getZ(),fr=imp.getT(), chs=imp.getNChannels(), sls=imp.getNSlices(), frms=imp.getNFrames();
		if(ch==chs && sl==sls && fr==frms) {IJ.error("No slices beyond current position: ch:"+chs+" sl:"+sls+" fr:"+frms);return;}
		GenericDialog gd=new GenericDialog("Back Deleter");
		if(chs>1)gd.addCheckbox("Delete Channels after "+ch+"?", sls==sl && frms==fr);
		if(sls>1)gd.addCheckbox("Delete Slices after "+sl+"?", sl<sls && frms==fr);
		if(frms>1)gd.addCheckbox("Delete Frames after "+fr+"?", fr<frms);
		gd.showDialog();
		if(gd.wasCanceled())return;
		if(chs>1 && !gd.getNextBoolean())ch=chs;
		if(sls>1 && !gd.getNextBoolean())sl=sls;
		if(frms>1 && !gd.getNextBoolean())fr=frms;
		trimStack(imp,1,1,1,ch,sl,fr);
	}
	
	public static void trimStack(ImagePlus imp, int chst, int slst, int frst, int chend, int slend, int frend) {
		int chs=imp.getNChannels();
		int sls=imp.getNSlices();
		int frms=imp.getNFrames();
		if(chend<chst) {int t=chend;chend=chst;chst=t;}
		if(slend<slst) {int t=slend;slend=slst;slst=t;}
		if(frend<frst) {int t=frend;frend=frst;frst=t;}
		if(chst<1)chst=1;if(slst<1)slst=1;if(frst<1)frst=1;
		if(chend>chs)chend=chs;if(slend>sls)slend=sls;if(frend>frms)frend=frms;
		if(chst==1&&slst==1&&frst==1&&chend==chs&&slend==sls&&frend==frms)return;
		chst--; slst--;frst--;
		int nchs=chend-chst, nsls=slend-slst, nfrms=frend-frst;
		ImageStack imst=imp.getStack();
		Object[] stack=imst.getImageArray();
		String[] labels=imst.getSliceLabels();
		int n=0;
		if(slst>0 || chst>0 || frst>0 || slend<sls || chend<chs) {
			for(int fr=frst;fr<frend;fr++) {
				for(int sl=slst;sl<slend;sl++) {
					for(int ch=chst;ch<chend;ch++) {
						//if((ch+chst)<chs || (sl+slst)<sls || (fr+frst)<frms) {
							stack[n]=stack[(fr)*sls*chs+(sl)*chs+(ch)];
							labels[n++]=labels[(fr)*sls*chs+(sl)*chs+(ch)];
						//}
					}
				}
			}
		}else {n=nchs*nsls*nfrms;}
		for(;n<chs*sls*frms;n++) {imst.deleteLastSlice();}
		imp.setDimensions(nchs, nsls, nfrms);
		imp.updateAndDraw();
	}

}
