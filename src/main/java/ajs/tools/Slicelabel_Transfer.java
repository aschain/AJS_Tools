package ajs.tools;
import ij.plugin.PlugIn;
import ij.gui.*;
import ij.*;
import java.lang.Math;


/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class Slicelabel_Transfer implements PlugIn {
	/**
	 * This method gets called by ImageJ / Fiji.
	 *
	 * @param arg can be specified in plugins.config
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */

	
	 
	@Override
	public void run(String arg) {
	//args= "images=title additional=adder prepend(if true) begin=offset" 
	//where title is the transfer-to image and adder is an optional added line to the transferred text, 
	//prepend is whether to prepend or postpend the adder, and offset is beginning fr on target
		IJ.showStatus("Slicelabel_transfer");
		ImagePlus simp=WindowManager.getCurrentImage();
		if(simp==null){IJ.showStatus("No open images"); return;}
		//String marg=Macro.getOptions();
		String title,adder;
		boolean prependadder=false,averaged=false;
		int offset=1;
		
		//dialog
		int[] idlist=WindowManager.getIDList();
		String[] imnames=new String[idlist.length];
		for(int i=0;i<idlist.length;i++) imnames[i]=WindowManager.getImage(idlist[i]).getTitle();
		GenericDialog gd = new GenericDialog("Select Image");
		gd.addMessage("Transfer titles from "+simp.getTitle()+" to:");
		String defchoice=imnames[0]; if(defchoice==simp.getTitle() && imnames.length>1)defchoice=imnames[1];
		gd.addChoice("Images: ",imnames,defchoice);
		gd.addStringField("Additional label:","");
		gd.addCheckbox("Prepend", prependadder);
		gd.addNumericField("Begin on target frame:",offset, 0);
		gd.addCheckbox("New image averaged instead of truncated", averaged);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		title=gd.getNextChoice();
		adder=gd.getNextString();
		prependadder=gd.getNextBoolean();
		if(adder==null) adder="";
		offset=((int) gd.getNextNumber());
		averaged=gd.getNextBoolean();
			
		offset--;
		ImagePlus timp=WindowManager.getImage(title);
		
		if(timp==null) {
			IJ.log("Error no stack named "+title);
			//int[] ids=WindowManager.getIDList();
			//for(int i=0;i<ids.length;i++){
			//	String idt=WindowManager.getImage(ids[i]).getTitle();
			//}
			return;
		}
		transferSliceLabels(simp, timp, offset, adder, prependadder, averaged);
	}
	
	public static void transferSliceLabels(ImagePlus simp, ImagePlus timp) {
		transferSliceLabels(simp, timp, 0, "", true, false);
	}
	
	public static void transferSliceLabels(ImagePlus simp, ImagePlus timp, int offset, String adder, Boolean prepend, Boolean averaged) {
		
		ImageStack imgst=simp.getStack();
		String preadder="", postadder="";
		
        if(adder!=null && !adder.equals("")){
        	if(prepend)preadder=adder+"\n";
        	else postadder="\n"+adder;
        }

		int chs=simp.getNChannels(); int sls=simp.getNSlices(); int frms=simp.getNFrames();
		int cht=timp.getNChannels(); int slt=timp.getNSlices(); int frmt=timp.getNFrames();
		if(frms > frmt-offset) {IJ.showStatus("Incomplete transfer-- only room for "+(frmt-offset));}
		//if(frms != frmt-offset) {IJ.log("Warning: frame mismatch");}
		

		int chx=1,slx=1,frx=1;
		if(averaged) {
			if(offset>0) {IJ.showMessage("Can't do averaged stack with offset");return;}
			chx=chs/cht; slx=sls/slt; frx=frms/frmt;
			if(chx==chs)chx=1; //RGB doesn't need to be averaged
			if(slx==sls)slx=1; //MAX doesn't need to be averaged
			if(frx==frms)frx=1; 
		}
		
		String[] labels=imgst.getSliceLabels();
		if(labels==null){
			IJ.showStatus("Slicelabel_transfer: no labels");
			return;
		}
		String temp;
		ImageStack st=timp.getStack();
		if(timp.getStackSize()==1) {
			timp.setProperty("Label", preadder+simp.getProperty("Label")+postadder);
		}else{
			for(int ch=0;ch<Math.min(chs,cht);ch++){
				for(int sl=0;sl<Math.min(sls,slt);sl++){
					for(int fr=0;fr<Math.min(frms,(frmt-offset));fr++){
						temp=labels[fr*frx*(sls*chs)+sl*slx*chs+ch*chx];
						if(temp!=null && temp.length()>1 && temp.endsWith("\n"))temp=temp.substring(0,temp.length()-1);
						if(temp!=null){
							int ioz=temp.indexOf("Z");
							if(ioz>-1 && slt==1 && sls>1 && temp.startsWith("s_") && temp.length()>ioz+4){
								temp=""+temp.substring(0,ioz+1)+"MAX"+temp.substring(ioz+4);
							}
							int ioc=temp.indexOf("C");
							if(ioc>-1 && cht==1 && chs>1 && temp.startsWith("s_") && temp.length()>ioc+4){
								temp=""+temp.substring(0,ioc+1)+"RGB"+temp.substring(ioc+4);
							}
							st.setSliceLabel(preadder+temp+postadder,(fr+offset)*(slt*cht)+sl*cht+ch+1);
						}
					}
				}
			}
		}
		timp.updateAndRepaintWindow();
		IJ.showStatus("Slicelabel transfer completed");
	}

	public static String getSliceLabels(){
		ImageStack imgst=WindowManager.getCurrentImage().getStack();
		String[] labels=imgst.getSliceLabels();
		//IJ.log("labels: "+labels.length+" imgst: "+imgst.getSize());
		String result=null;
		for(int i=0;i<imgst.getSize();i++){
			if(i==0) result=labels[i];
			else result=result+"@@"+labels[i];
		}
		return result;
	}
	public static void setSliceLabels(String[] labels){
		ImageStack imgst=WindowManager.getCurrentImage().getStack();
		if(labels.length<imgst.getSize()){IJ.log("Error wrong size slicelabel "+labels.length+" "+imgst.getSize()); return;}
		for(int i=0;i<imgst.getSize();i++)
			imgst.setSliceLabel(labels[i],i+1);
	}
	
	public static void setSliceLabels(String labels){
		setSliceLabels(labels.split("@@"));
	}

	public static void addToSliceLabels(String adder){
		ImageStack imgst=WindowManager.getCurrentImage().getStack();
		String[] labels=imgst.getSliceLabels();
		String[] adderarray=adder.split("@@");
		int minlen=Math.min(imgst.getSize(),adderarray.length);
		if(labels==null) labels= new String[minlen];
		for(int i=0;i<minlen;i++){
			if(labels[i]==null)labels[i]="";
			if(labels[i].equals("")||labels[i].endsWith("\n"))labels[i]=labels[i].concat(adderarray[i]);
			else labels[i]=labels[i].concat("\n"+adderarray[i]);
			imgst.setSliceLabel(labels[i],i+1);
		}
	}

}
