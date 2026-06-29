package ajs.tools;

import ij.plugin.PlugIn;
import ij.*;
import ij.io.FileInfo;
import java.io.*;

/**
 * This is a template for a plugin that does not require one image
 * (be it that it does not require any, or that it lets the user
 * choose more than one image in a dialog).
 */
public class TestPlugin implements PlugIn {
	
	@Override
	public void run(String arg) {
		ImagePlus imp=WindowManager.getCurrentImage();
		if(imp==null) return;
		String title=imp.getTitle();
		String dir=imp.getOriginalFileInfo().directory;
		if(!title.endsWith(".tif")) title=title+".tif";
		if(dir==null || dir.isEmpty()){
			IJ.runMacro("getInfo(\"image.directory\")");
			String log=IJ.getLog();
			if(log!=null){
				String[] lines=log.split("\n");
				dir=lines[lines.length-1].trim();
			}
		}
		if(dir==null || dir.isEmpty()){
			dir=IJ.getDirectory("temp")+"ijcellpose"+File.separator;
			(new File(dir)).mkdir();
			IJ.saveAs("tiff", dir+title);
			IJ.log("Saved image to "+dir+title);
		}
		String inputPath=(dir+title).replace("\\", "/");
		String cmd="\"conda run -n cellpose3 python C:/Users/aaron/Documents/git/BashScripts/getCellposeAJTCT.py "+inputPath+"\"";
		IJ.log("Running command: "+cmd);
		ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", cmd);
		//Runtime r=Runtime.getRuntime();
		//Process p=null;
		try {
			Process p = pb.start();
			BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String line = "";
			IJ.log(""+System.currentTimeMillis());
			while ((line = b.readLine()) != null) {
				IJ.log(line);
			}
			b.close();
			p.waitFor();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		String outputPath=inputPath.substring(0,inputPath.length()-4)+" -AJTCTcp.tif";
		if((new File(outputPath)).exists()){ 
			IJ.open(outputPath);
			IJ.log("Cellpose output completed: ");
		}else IJ.log("Error could not find segmented output file: "+outputPath);
	}
}
