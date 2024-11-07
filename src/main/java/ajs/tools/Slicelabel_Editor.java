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
import ij.plugin.PlugIn;
import ij.plugin.frame.Editor;
import ij.plugin.frame.Recorder;
import ij.text.TextPanel;
import ij.text.TextWindow;

public class Slicelabel_Editor implements ImageListener,PlugIn {
	ImagePlus imp;
	String[] slbls;
	TextWindow tw;
	Editor editor;
	String oldtext="";
	//TextArea edta;
	int n=0;
	
	@Override
	public void run(String arg) {
		this.imp=WindowManager.getCurrentImage();
		if(imp==null) {IJ.noImage();return;}
		if("editInfo".contentEquals(arg)) {
			editInfo(imp);
			return;
		}
		editSliceLabels(imp);
	}
	
	public static void editInfo(ImagePlus imp) {
		Editor ed=new Editor(24,80,0,Editor.MONOSPACED+Editor.MENU_BAR);
		MenuItem save=setUpEditor(ed, imp.getTitle()+" Info", imp.getInfoProperty()==null?"":imp.getInfoProperty());
		save.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				imp.setProperty("Info",ed.getText());
				imp.changes=true;
				IJ.showStatus("Updated Info for "+imp.getTitle());
			}
		});
	}
	
	public void viewSliceLabels(ImagePlus imp) {
		slbls=imp.getStack().getSliceLabels();
		tw=new TextWindow("Slicelabel Viewer for "+imp.getTitle(),slbls[imp.getCurrentSlice()-1],600,500);
		ImagePlus.addImageListener(this);
	}
	
	public void editSliceLabels(ImagePlus imp) {
		slbls=imp.getStack().getSliceLabels();
		if(slbls==null)slbls=new String[imp.getStackSize()];
		editor=new Editor(24,80,0,Editor.MONOSPACED+Editor.MENU_BAR);
		MenuItem save=setUpEditor(editor, imp.getTitle()+" C"+imp.getC()+" Z"+imp.getZ()+" T"+imp.getT(), slbls[imp.getCurrentSlice()-1]);
		oldtext=editor.getText();
		save.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int n=imp.getCurrentSlice()-1;
				saveSliceLabel(n);
			}
		});
		ImagePlus.addImageListener(this);
	}
	
	public static MenuItem setUpEditor(Editor editor, String title, String text) {
		if(text==null)text="";
		editor.create(title,text);
		//edta=editor.getTextArea();
		Menu fmenu=editor.getMenuBar().getMenu(0);
		MenuItem save=fmenu.getItem(2);
		editor.getMenuBar().remove(3);
		for(int i=(fmenu.getItemCount()-1);i>=0;i--) {
			String label=fmenu.getItem(i).getLabel();
			if(!("Save".contentEquals(label) || "Print...".contentEquals(label)) )fmenu.remove(i);
		}
		for(ActionListener l : save.getActionListeners())save.removeActionListener(l);
		for(WindowListener w : editor.getWindowListeners())editor.removeWindowListener(w);
		editor.addWindowListener(new WindowAdapter() {
			 @Override
			 public void windowClosing(WindowEvent e) {
			    	if (e.getSource()==editor) {
			    		//editor.display("Closing", "Closing");
			    		editor.dispose();
			    		WindowManager.removeWindow(editor);
			    		if (Recorder.record)
			    			Recorder.record("run", "Close");
			    	}
			    }
			 	@Override
			    public void windowActivated(WindowEvent e) {
			 		if(e.getSource()!=editor)return;
					if (Prefs.setIJMenuBar) {
						editor.setMenuBar(Menus.getMenuBar());
						Menus.setMenuBarCount++;
					}
					WindowManager.setWindow(editor);
				}
		});
		TextArea edta=editor.getTextArea();
		edta.removeTextListener(edta.getTextListeners()[0]);
		return save;
	}
	
	private void saveSliceLabel(int n) {
		slbls[n]=editor.getText();
		imp.getStack().setSliceLabel(slbls[n], n+1);
		oldtext=slbls[n];
		imp.changes=true;
		IJ.showStatus("Updated SliceLabel");
	}

	public void imageOpened(ImagePlus uimp) {}
	public void imageClosed(ImagePlus uimp) {
		if(uimp.equals(imp)){
			if(tw!=null)tw.dispose();
			if(editor!=null) {editor.display("Closing", "closing..."); editor.dispose();}
			ImagePlus.removeImageListener(this);
		}
	}
	public void imageUpdated(ImagePlus uimp){
		if(uimp.equals(imp)){
			if(tw!=null && tw.isVisible()){
				TextPanel tp=tw.getTextPanel();
				tp.setColumnHeadings("");
				tp.append(slbls[imp.getCurrentSlice()-1]);
			}else if(editor!=null && editor.isVisible()) {
				String title=imp.getTitle()+" C"+imp.getC()+" Z"+imp.getZ()+" T"+imp.getT();
				if(!(editor.getText()==null) && !editor.getText().contentEquals(oldtext)) {
					String oldt=editor.getTitle();
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
				editor.getTextArea().setText(s);
				Menus.updateWindowMenuItem(editor.getTitle(), title);
				editor.setTitle(title);
				//editor.display(title, s);
				oldtext=editor.getText();
			}else {
				ImagePlus.removeImageListener(this);
				return;
			}
		}
	}
}
