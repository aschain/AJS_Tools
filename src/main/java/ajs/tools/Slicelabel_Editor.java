package ajs.tools;

import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.TextArea;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;

import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.Menus;
import ij.Prefs;
import ij.WindowManager;
import ij.plugin.frame.Editor;
import ij.plugin.frame.Recorder;

public class Slicelabel_Editor extends Editor implements ImageListener {
	ImagePlus imp;
	String[] slbls;
	String oldtext="";
	int n=0;
	
	public Slicelabel_Editor() {
		super(24,80,0,Editor.MONOSPACED+Editor.MENU_BAR);
	}

	@Override
	public void run(String arg) {
		this.imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage();return;}
		if("editInfo".contentEquals(arg)) {
			ActionListener saveAction=new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					imp.setProperty("Info",getText());
					imp.changes=true;
					IJ.showStatus("Updated Info for "+imp.getTitle());
				}
			};
			setUp(imp.getTitle()+" Info", imp.getInfoProperty()==null?"":imp.getInfoProperty(), saveAction);
			return;
		}
		editSliceLabels(imp);
	}
	
	public void editSliceLabels(ImagePlus imp) {
		slbls=imp.getStack().getSliceLabels();
		if(slbls==null)slbls=new String[imp.getStackSize()];
		ActionListener saveAction=new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int n=imp.getCurrentSlice()-1;
				saveSliceLabel(n);
			}
		};
		setUp(imp.getTitle()+" C"+imp.getC()+" Z"+imp.getZ()+" T"+imp.getT(), slbls[imp.getCurrentSlice()-1], saveAction);
		oldtext=getText();
		ImagePlus.addImageListener(this);
	}
	
	public void setUp(String title, String text, ActionListener saveAction) {
		if(text==null)text="";
		create(title,text);
		//edta=editor.getTextArea();
		Menu fmenu=getMenuBar().getMenu(0);
		MenuItem save=fmenu.getItem(2);
		getMenuBar().remove(3);
		for(int i=(fmenu.getItemCount()-1);i>=0;i--) {
			String label=fmenu.getItem(i).getLabel();
			if(!("Save".contentEquals(label) || "Print...".contentEquals(label)) )fmenu.remove(i);
		}
		for(ActionListener l : save.getActionListeners())save.removeActionListener(l);
		for(WindowListener w : getWindowListeners())removeWindowListener(w);
		Editor ed=this;
		addWindowListener(new WindowAdapter() {
			 @Override
			 public void windowClosing(WindowEvent e) {
				if (e.getSource()==ed) {
					//editor.display("Closing", "Closing");
					WindowManager.removeWindow(ed);
					if (Recorder.record)
						Recorder.record("run", "Close");
					dispose();
				}
			}
			@Override
			public void windowActivated(WindowEvent e) {
				if(e.getSource()!=ed)return;
				if (Prefs.setIJMenuBar) {
					ed.setMenuBar(Menus.getMenuBar());
					Menus.setMenuBarCount++;
				}
				WindowManager.setWindow(ed);
			}
		});
		TextArea edta=getTextArea();
		edta.removeTextListener(edta.getTextListeners()[0]);
		if(saveAction!=null)save.addActionListener(saveAction);
	}
	
	private void saveSliceLabel(int n) {
		slbls[n]=getText();
		imp.getStack().setSliceLabel(slbls[n], n+1);
		oldtext=slbls[n];
		imp.changes=true;
		IJ.showStatus("Updated SliceLabel");
	}

	public void imageOpened(ImagePlus uimp) {}
	public void imageClosed(ImagePlus uimp) {
		if(uimp.equals(imp)){
			ImagePlus.removeImageListener(this);
			display("Closing", "closing...");
			dispose();
		}
	}
	public void imageUpdated(ImagePlus uimp){
		if(uimp.equals(imp)){
			String title=imp.getTitle()+" C"+imp.getC()+" Z"+imp.getZ()+" T"+imp.getT();
			if(!(getText()==null) && !getText().contentEquals(oldtext)) {
				String oldt=getTitle();
				oldt=oldt.substring(imp.getTitle().length()+2);
				int zi=oldt.indexOf(" Z"), ti=oldt.indexOf(" T");
				int c=AJ_Utils.parseIntTP(oldt.substring(0,zi));
				int z=AJ_Utils.parseIntWithin(oldt.substring(zi+2,ti));
				int t=AJ_Utils.parseIntWithin(oldt.substring(ti+2));
				if(IJ.showMessageWithCancel("Slicelabel changed", "SliceLabel was changed: save?")) {
					IJ.log("Saving c"+c+" z"+z+" t"+t);
					saveSliceLabel(imp.getStackIndex(c, z, t)-1);
				}
			}
			String s=slbls[imp.getCurrentSlice()-1];
			if(s==null)s="";
			getTextArea().setText(s);
			Menus.updateWindowMenuItem(getTitle(), title);
			setTitle(title);
			//editor.display(title, s);
			oldtext=getText();
		}
	}
}
