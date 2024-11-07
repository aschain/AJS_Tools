package ajs.tools;
import ij.plugin.PlugIn;
import ij.util.Java2;
import ij.gui.*;
import ij.io.OpenDialog;
import ij.*;

import java.awt.Component;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.util.ArrayList;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.UIManager;

/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class TwoPhoton_Import implements PlugIn {
	
	private boolean isOif, isPrairie, hasTifs, hasFolders;
	public static boolean debug;
	
	/**
	 * This method gets called by ImageJ / Fiji.
	 *
	 * @param arg can be specified in plugins.config
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	@Override
	public void run(String arg) {
		if(arg.equals("updateImageSliceTimes")) {updateImageSliceTimes(); return;}
		else if(arg.equals("printexloc")) {printExLoc(IJ.getDirectory("")); return;}
		else if(arg.equals("printexlocspecial")) {printExLocSpecial(); return;}
		else if(arg.equals("updateCurrentImage")) {updateCurrentImage();return;}
		else if(arg.equals("startContinuousUpdate")) {startContinuousUpdate();return;}
		else if(arg.equals("options")) {setOptions();return;}
		else if(isDirectory(arg)) {openTwoPhoton(arg); return;}
		else {
			openTwoPhoton();
		}
	}
	
	public void openTwoPhoton() {
		String[] dir=getDirectories();
		if(dir==null)return;
		if(dir.length==1)openTwoPhoton(dir[0]);
		else {
			//String other="";
			for(int i=0;i<dir.length;i++) {
				openTwoPhoton(dir[i],true, true);
				//String title=openTwoPhoton(dir[i],true).getTitle();
				//if(i==0)other+="  title="+title+"_CONCAT";
				//other+=" image"+(i+1)+"=["+title+"]";
			}
			//IJ.run("Concatenate...", other);
		}
	}
	
	public String[] getDirectories() {
		// Choose a directory using JFileChooser on the current thread
		String[] result=null;
		Java2.setSystemLookAndFeel();
		try {
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle("TwoPhoton Import");
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			chooser.setMultiSelectionEnabled(true);
			String defaultDir = Prefs.get("DirectoryChooser.DefaultDirectory",OpenDialog.getDefaultDirectory());
			if (defaultDir!=null) {
				File f = new File(defaultDir);
				chooser.setSelectedFile(f);
			}
			chooser.setAccessory(new PrevFolderSelector(chooser));
			chooser.setApproveButtonText("Select");
			if (chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {
				File files[] = chooser.getSelectedFiles();
				result=new String[files.length];
				for(int i=0;i<files.length;i++) {
					String directory = files[i].getAbsolutePath();
					defaultDir=directory;
					if (!(directory.endsWith(File.separator)||directory.endsWith("/")))
						directory += "/";
					result[i]=directory;
				}
				Prefs.set("DirectoryChooser.DefaultDirectory",defaultDir);
				Prefs.savePreferences();
			}
		} catch (Exception e) {}

		return result;
	}
	
	class PrevFolderSelector extends JComponent implements PropertyChangeListener {
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;
		JFileChooser fc;

		public PrevFolderSelector(JFileChooser fc) {
			this.fc=fc;
			fc.addPropertyChangeListener(this);
		}

		public void propertyChange(PropertyChangeEvent e) {
			String prop = e.getPropertyName();

			if (JFileChooser.DIRECTORY_CHANGED_PROPERTY.equals(prop)) {
				File oldDir = (File) e.getOldValue();
				File newDir = (File) e.getNewValue();
				if(newDir.getPath().equals(oldDir.getParent())) {
					if(UIManager.getLookAndFeel().getName().equals("Windows") || UIManager.getLookAndFeel().getName().equals("Windows Classic")){
						Component c=fc.findComponentAt(106,40);
						if(c!=null && c instanceof JComponent){
							c.requestFocusInWindow();
						}
					}
					fc.setSelectedFile(oldDir);
				}

				//If a file became selected, find out which one.
			} else if (JFileChooser.SELECTED_FILE_CHANGED_PROPERTY.equals(prop)) {
				//file = (File) e.getNewValue();
			}
		}

	}
	
	private void getFolderType(File[] fl) {
		isOif=false; isPrairie=false; hasTifs=false; hasFolders=false;
		for(int i=0;i<fl.length;i++){
			String name=fl[i].getName();
			if(name.endsWith(".xml") || name.endsWith(".cfg")) {isOif=false; isPrairie=true; if(hasTifs)break;}
			if(name.endsWith(".tif")){
				hasTifs=true;
				if(isPrairie) break;
				if(name.startsWith("s_")) {isOif=true; break;}
			}
			if(fl[i].isDirectory()) hasFolders=true;
		}
	}
	
	static boolean isDirectory(String dir) {
		if(dir==null)return false;
		File f= new File(dir);
		if(!f.exists() || !f.isDirectory()) return false;
		return true;
	}
	
	static public void setOptions() {
		
		GenericDialog gd=new GenericDialog("2P-Import Options");
		gd.addMessage("Continuous Import");
		gd.addCheckbox("Set up Webpage?", false);
		gd.addCheckbox("Set up Monitor IFTTT website?", false);
		
		gd.addMessage("Other");
		gd.addCheckbox("Move images when opened", Prefs.get("AJ.TwoPhoton_Import.dopos", true));
		gd.addCheckbox("Auto open all tps with slicelabels", Prefs.get("AJ.TwoPhoton_Import.noask", false));
		gd.addCheckbox("Debug",false);
		
		gd.showDialog();
		
		if(gd.wasCanceled())return;
		
		if(gd.getNextBoolean()) {
			if(IJ.showMessageWithCancel("Set up Webpage","Change web root folder?\n"+TwoPhotonImage.webpath)) TwoPhotonImage.setUpWebpage();
		}
		if(gd.getNextBoolean()) {
			GenericDialog sgd=new GenericDialog("IFTTT Webhook");
			sgd.addStringField("When monitoring continuous 2p, the alarm can send\n a webhook to a website if you like:", Prefs.get("AJ.TwoPhoton_Import.alarmwebhook", ""));
			sgd.showDialog();
			if(!sgd.wasCanceled()) {
				String wh=sgd.getNextString();
				if(wh!=null && !wh.isEmpty()) {
					if(!wh.startsWith("http"))wh="https://"+wh;
				}
				Prefs.set("AJ.TwoPhoton_Import.alarmwebhook",wh);
			}
		}
		Prefs.set("AJ.TwoPhoton_Import.dopos", gd.getNextBoolean());
		Prefs.set("AJ.TwoPhoton_Import.noask", gd.getNextBoolean());
		debug=gd.getNextBoolean();
		Prefs.savePreferences();
	}
	
	public ImagePlus openTwoPhoton(String dir) {
		return openTwoPhoton(dir, false, false);
	}
	
	public ImagePlus openTwoPhoton(String dir, boolean noask, boolean recurse){
		
		//test directory for oif, prairie, empty, tifs, or more folders
		if(dir==null)return null;
		if(dir.isEmpty())return null;
		if(!isDirectory(dir))return null;
		File f= new File(dir);
		long ptime=0;
		if(debug) {IJ.log("Getting file list..."); ptime=System.currentTimeMillis();}
		File[] fl=f.listFiles(TwoPhotonImage.nohidden);
		if(debug) {IJ.log("File list took "+(System.currentTimeMillis()-ptime)+"ms");}

		if(fl.length==0) {IJ.log("Directory is empty"); return null;}
		
		getFolderType(fl);
		if(!hasTifs && !hasFolders) {
			IJ.log("\n"+dir+" has nothing to open");
			return null;
		}
		if(!isOif && !isPrairie){
			openAllFolder(dir,recurse);
			return null;
		}
		
		//sets up tpi and does exloc and updates from current file list
		TwoPhotonImage tpi=new TwoPhotonImage(fl);
		if(noask)tpi.noask=true;
		return tpi.open();
	
	}

	public static void updateImageSliceTimes() {
		 TwoPhotonImage.updateImageSliceTimes(WindowManager.getCurrentImage());
	}
	
	public static void updateCurrentImage() {
		ImagePlus img=WindowManager.getCurrentImage();
		if(img!=null){
			TwoPhotonImage tpi=new TwoPhotonImage(img);
			tpi.updateImage();
		}else {IJ.noImage(); return;}
	}
	
	public void startContinuousUpdate() {
		ImagePlus img=WindowManager.getCurrentImage();
		if(img!=null){
			TwoPhotonImage tpi=new TwoPhotonImage(img);
			tpi.setupContinuousUpdate();
			tpi.startContinuousUpdate();
		}else {IJ.noImage(); return;}
	}

	public void openAllFolder(String path, boolean recurse){
		if(path==null)return;
		File f;
		try {
			f= new File(path);
		}catch(Exception e) {IJ.error("Could not open directory to open all files");return;}
		if(f==null || !f.exists() || !f.isDirectory()) return;
		File[] fl=f.listFiles(TwoPhotonImage.nohidden);

		if(fl.length==0) {IJ.log("Directory is empty"); return;}
		
		//OIF compatibility + tests for empty folders + recurse folders----------
		boolean go=true,dofs=false,dotifs=false,skipopen=false;
        String name;
        ArrayList<Integer> folders=new ArrayList<Integer>();
        ArrayList<Integer> tifs=new ArrayList<Integer>();
		for(int i=0;i<fl.length && go;i++){
			name=fl[i].getName();
			if(name.endsWith(".tif")){
				tifs.add(i);
			}
			if(fl[i].isDirectory()) {folders.add(i);}
		}
		if(tifs.size()==0 && folders.size()==0) {
			IJ.log("\n"+path+" has nothing to open");
			return;
		}
		
		String filterstring="";
		if(folders.size()>0) dofs=true;
		if(tifs.size()>0) dotifs=true;
		if(folders.size()==1 && tifs.size()==0) {
			openTwoPhoton(fl[folders.get(0)].getAbsolutePath(),true,recurse); return;
		}else {
			if(!recurse){
				GenericDialog gd = new GenericDialog("Open all");
				if(dofs) {
					gd.addCheckbox("Open all folders?",true);
					gd.addCheckbox("Recurse?",true);
				}
				if(dotifs) gd.addCheckbox("Open all tifs in folder?",true);
				gd.addCheckbox("Skip if already open?",true);
				gd.addStringField("Filter: ","");
				gd.addMessage("Wildcard [*] at start or end of filter means");
				gd.addMessage("that it ends with or starts with the string.");
				gd.showDialog();
				if(gd.wasCanceled())return;
				if(dofs) {
					dofs=gd.getNextBoolean();
					recurse=gd.getNextBoolean();
				}
				if(dotifs) dotifs=gd.getNextBoolean();
				skipopen=gd.getNextBoolean();
				filterstring=gd.getNextString();
			}
		}
		if(dofs || dotifs){
			for(int i=0;i<fl.length;i++) {
				go=true; name=fl[i].getName();
				if(name.endsWith(".tif")|| fl[i].isDirectory()){
					if(!filterstring.contentEquals("")){
						go=false; 
						if(filterstring.startsWith("*")) {
							int mod=0; 
							String endfilterstring=filterstring.substring(1,filterstring.length());
							// if(fl[i].isDirectory()) mod=1;else
							if(!filterstring.endsWith("tif")&&name.endsWith(".tif")) mod=4;
							name=name.substring(0,name.length()-mod);
							go=name.endsWith(endfilterstring);
						} else if(filterstring.endsWith("*")) {
							String stfilterstring=filterstring.substring(0,filterstring.length()-1);
							go=name.startsWith(stfilterstring);
						} else go=(name.indexOf(filterstring)!=-1);
					}
					if(skipopen){
						if(name.endsWith(".files")){name=name.substring(0,name.length()-6);} //-7 if directories end in slash
						if(WindowManager.getImage(name)!=null) {IJ.log(i+" Skipping "+name+", already open"); go=false;}
					}
					if((go && dofs) && fl[i].isDirectory() && !name.startsWith("SingleImage-") && !name.startsWith("Projection")) {
						//don't open mosaic in folder unless specified
						if(name.startsWith("FV10_")){if(IJ.showMessageWithCancel("FV10","Open "+fl[i]+"?")) openTwoPhoton(fl[i].getAbsolutePath(),true, true);}
						else {openTwoPhoton(fl[i].getAbsolutePath(),true, recurse);}
					}
					if(go && dotifs && name.endsWith(".tif"))
						IJ.openImage(fl[i].getAbsolutePath());
				}
			}
		}
	}
	
	public void printExLocSpecial() {
		GenericDialog gd=new GenericDialog("Location Extraction");
		gd.addCheckbox("Print locs.xy file?", false);
		gd.addCheckbox("Print location map?", false);
		gd.showDialog();
		
		TwoPhotonImage tpi=printExLoc(IJ.getDirectory(""),false);
		if(gd.getNextBoolean()) tpi.createPrairieLocXYfile();
		if(gd.getNextBoolean()) tpi.printLocationMap();
	}
	
	public void printExLoc(String dir) {
		printExLoc(dir,false);
	}
	
	public TwoPhotonImage printExLoc(String dir, boolean recurse){
		
		//test directory for oif, prairie, empty, tifs, or more folders
		if(dir==null)return null;
		File f= new File(dir);
		if(!f.exists() || !f.isDirectory()) return null;
		File[] fl=f.listFiles(TwoPhotonImage.nohidden);

		if(fl.length==0) {IJ.log("Directory is empty"); return null;}
		
		getFolderType(fl);
		if(!hasTifs && !hasFolders) {
			IJ.log("\n"+dir+" has nothing to open");
			return null;
		}
		if(!isOif && !isPrairie){
			if(recurse && hasFolders) {
				for(int i=0;i<fl.length;i++) {
					if(fl[i].isDirectory())printExLoc(fl[i].getAbsolutePath(),true);
				}
			}else {
				IJ.log("\n"+dir+" has nothing to open");
				return null;
			}
		}
		
		TwoPhotonImage tpi=new TwoPhotonImage(fl);
		if(IJ.getLog()!=null)IJ.log("");
		IJ.log(tpi.exlocoutput);
		return tpi;
		
	}
	
	//
	// Utility Functions
	//
	
	public static void addTPPopup() {
		if(hasInstalledTPPopup())return;
		PopupMenu popup=Menus.getPopupMenu();
		MenuItem tpmi=new MenuItem("Update 2p Stack");
		tpmi.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				updateCurrentImage();
			}
		});
		popup.add(tpmi);
	}
	
	public static void removeTPPopup() {
		PopupMenu popup=Menus.getPopupMenu();
		for(int i=0;i<popup.getItemCount();i++)
			if(popup.getItem(i).getActionCommand().equals("Update 2p Stack"))popup.remove(i);
	}
	
	static boolean hasInstalledTPPopup() {
		PopupMenu popup=Menus.getPopupMenu();
		for(int i=0;i<popup.getItemCount();i++)
			if(popup.getItem(i).getActionCommand().equals("Update 2p Stack"))return true;
		return false;
	}
	
}
