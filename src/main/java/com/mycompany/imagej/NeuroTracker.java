package com.mycompany.imagej;

import java.awt.Color;
import java.awt.Point;
import java.awt.TextField;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Vector;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.Plot;
import ij.gui.PlotDialog;
import ij.gui.PlotWindow;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.YesNoCancelDialog;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.filter.Analyzer;
import ij.plugin.filter.Calibrator;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;

public class NeuroTracker implements PlugIn, MouseListener, KeyListener {
	class NeuronInfo {
		double x;
		double y;
		double xc;
		double yc;
		double intDens;
		double maxInt;
		double area;
		double sqArea;
		double sqIntDens;
		double intSub;
		double sqIntSub;
		double bgMedian;
		double bgMedian2;
		double bgAvg;
		double avg;
		double lowerThreshold;
		boolean redFlag;
		
		NeuronInfo() {}
		
		/*NeuronInfo(double x, double y, double intSub, double sqIntSub, double bgMedian, double avg) {
			this.x = x;
			this.y = y;
			this.intSub = intSub;
			this.sqIntSub = sqIntSub;
			this.bgMedian = bgMedian;
			this.avg = avg;
		}*/
	}
	
	protected ImagePlus image;
	boolean paused = true;
	boolean redFlag = false;
	boolean redFlagDelay = false;
	boolean lostTrack = false;
	int currentSlice = 0;
	int currentAnimal = 0;
	int numImages;
	
	boolean doneSelecting = false;
	
	boolean initialPosFileWritten = false;
	
	int currentImageIndex = -1;
	//ArrayList<ImagePlus> images;
	File[] fileList;
	
	String directory;
	
	//List<Point> neuronCoordinates = new ArrayList<>();
	Map<String, Integer> settings = new HashMap<>();
	
	//animal -> coordinates for each slice
	HashMap<Integer, ArrayList<NeuronInfo>> neuronCoordinates = new HashMap<>();
	
	ArrayList<NeuronInfo> initialPositions = new ArrayList<>();
	ArrayList<NeuronInfo> finalPositions = new ArrayList<>();
	NeuronInfo[] positions;
	
    private List<String> readSettings(String fileLocation) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(fileLocation));
        String line = null;
        //StringBuilder stringBuilder = new StringBuilder();
        //String ls = System.getProperty("line.separator");
        
        List<String> settings = new ArrayList<>();

        try {
            while((line = reader.readLine()) != null) {
                //stringBuilder.append(line);
                //stringBuilder.append(ls);
            	String[] s = line.split("//");
            	if(s.length > 0) {
	            	String setting = s[0].replaceAll(" ", "");
	            	settings.add(setting);
            	}
            }

            //return stringBuilder.toString();
            return settings;
        } finally {
            reader.close();
        }
    }
	
	public void loadFolder() {
		String directory = IJ.getDirectory("Select Video Directory");
		this.directory = directory;
		File directoryPath = new File(directory);
		File[] filesList = directoryPath.listFiles(new FilenameFilter() {
		    public boolean accept(File dir, String name) {
		        return name.toLowerCase().endsWith(".tif");
		    }
		});
		if(filesList.length > 0) {
			GenericDialog dialog = new GenericDialog("Info");
			dialog.addMessage("Found " + filesList.length + " files. What range of files would you like to use?");
			dialog.addStringField("Range", "1-" + filesList.length);
			dialog.showDialog();
			@SuppressWarnings("unchecked")
			Vector<TextField> fields = dialog.getStringFields();
			String range = fields.get(0).getText().toString();
			String[] s = range.split("-");
			int start = Integer.parseInt(s[0]);
			int end = Integer.parseInt(s[1]);
			System.out.println(start + " - " + end);
			/*for(int i = start - 1; i <= end - 1; i++) {
				ImagePlus img = IJ.openImage(filesList[i].getPath());
				images.add(img);
			}*/
			this.fileList = Arrays.copyOfRange(filesList, start - 1, end);
		}
	}
	
	public void setImage(int index) {
		if(this.currentImageIndex == index)
			return;
		ImagePlus image = IJ.openImage(this.fileList[index].getPath());
		this.currentImageIndex = index;
		this.setImage(image);
	}
	
	public void resetPositions(int animal) {
		int size = this.image.getStackSize();
		positions = new NeuronInfo[size + 1];
		NeuronInfo ni = this.initialPositions.get(animal);
		positions[1] = ni;
		IJ.setThreshold(ni.lowerThreshold, 65535);
		this.redFlag = false;
		this.redFlagDelay = false;
	}
	
	public void saveFinalPositions() {
		this.finalPositions.add(this.positions[this.positions.length - 1]);
	}
	
	public void setImage(ImagePlus image) {
		if(this.image != null) {
			this.image.changes = false;
			this.image.close();
		}
		this.image = image;
		this.image.show();
		
		IJ.selectWindow(this.image.getTitle());

		//WindowManager.repaintImageWindows();
		neuronCoordinates = new HashMap<>();
		
		initialPositions.clear();
		//if(!this.finalPositions.isEmpty()) {
		//}
		int size = this.image.getStackSize();
		positions = new NeuronInfo[size + 1];
		
		IJ.run("Set Scale...", "distance=0");
		
		ImageWindow window = this.image.getWindow();
		ImageCanvas canvas = window.getCanvas();
		//canvas.requestFocusInWindow();
		canvas.disablePopupMenu(true);
		canvas.addMouseListener(this);
		canvas.addKeyListener(this);
	
		this.currentAnimal = 0;
		this.currentSlice = 1;
		this.paused = true;
		this.doneSelecting = false;
		this.redFlag = false;
		this.redFlagDelay = false;
		
		this.image.setSlice(1);
		
		int numImages = image.getStackSize();
		this.numImages = numImages;
		
		//default threshold
		IJ.setThreshold(this.image, this.settings.get("lowerThreshold"), 65535);
		
		//try to load saved positions here
		boolean usedFile = false;
		if(this.finalPositions.size() == 0 && this.positionsFileExists()) {
			YesNoCancelDialog dialog = new YesNoCancelDialog(WindowManager.getFrame(this.image.getTitle()), "Info", 
					"Positions file detected. Do you want to load the saved initial positions?", "yes","no");
			if(dialog.yesPressed()) {
				System.out.println("loading initial positions");
				try {
					this.loadSavedPositions();
					usedFile = true;
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		if(!usedFile) {
			for(NeuronInfo ni : this.finalPositions) {
				this.addInitialPosition(ni.xc, ni.yc, ni.lowerThreshold);
				System.out.println("using " + ni.xc + ", " + ni.yc + ", " + ni.lowerThreshold + " for the next video");
			}
			if(this.finalPositions.size() > 0) {
				this.paused = false;
				this.saveInitialPositions();
				this.doneSelecting = true;
				IJ.run("Close");
			}
		}
		this.finalPositions.clear();
		
		//this needs to come last
		canvas.requestFocusInWindow();
	}
	
	//check if we are done processing with this image
	public boolean isDone() {
		int numAnimals = this.neuronCoordinates.size();
		int numSlices = this.image.getStackSize();
		if(this.currentAnimal == numAnimals - 1 && this.currentSlice == numSlices) {
			return true;
		}
		return false;
	}
	
	@Override
	public void run(String arg) {
		//if (IJ.getVersion() >= "1.37r") 
		
			//IJ.setOption("DisablePopupMenu", true);)
		//String settingsPath = IJ.getFilePath("Choose Settings File Location");
		List<String> settingsInfo = null;
		try {
			//TrckSett_awa_2p5x_4pxSq
			settingsInfo = this.readSettings("C:\\Users\\nickc\\OneDrive\\NT\\settings\\tracksettings.txt");
			//settingsInfo = this.readSettings(settingsPath);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		this.settings.put("animal", Integer.parseInt(settingsInfo.get(0)));
		this.settings.put("lowerThreshold", Integer.parseInt(settingsInfo.get(1)));
		this.settings.put("upperThreshold", 65535);
		this.settings.put("searchDia", Integer.parseInt(settingsInfo.get(2)));
		this.settings.put("bgRingDia", Integer.parseInt(settingsInfo.get(3)));
		this.settings.put("sqSize", Integer.parseInt(settingsInfo.get(4)));
		this.settings.put("maxSize", Integer.parseInt(settingsInfo.get(5)));
		this.settings.put("minSize", Integer.parseInt(settingsInfo.get(6)));
		this.settings.put("expandAllow", Integer.parseInt(settingsInfo.get(7)));
		this.settings.put("usetracking", Integer.parseInt(settingsInfo.get(8)));
		this.settings.put("velocityPredict", Integer.parseInt(settingsInfo.get(9)));
		System.out.println(this.settings);
		
		// get width and height
		
		//Load folder
		loadFolder();
		
		//try to load saved positions here
		/*if(this.positionsFileExists()) {
			YesNoCancelDialog dialog = new YesNoCancelDialog(WindowManager.getFrame(this.image.getTitle()), "Info", 
					"Positions file detected. Do you want to load the saved initial positions?", "yes","no");
			if(dialog.yesPressed()) {
				System.out.println("loading initial positions");
				try {
					this.loadSavedPositions();
				} catch (IOException e) {
					e.printStackTrace();
				}
				//IJ.selectWindow(this.image.getTitle());
				//WindowManager.getFrame(this.image.getTitle()).requestFocus();
				canvas.requestFocus();
				window.requestFocus();
				System.out.println("is focused: " + window.isFocused());
			}
		}*/
		this.setImage(0);
	}
	
	public void process() {
		//no need to synchronize anything because the two threads are guaranteed to never access
		//each other's data
		new Thread(new Runnable() {
		    @Override
		    public void run() {
		    	int currentSlice = 0;
		    	int currentAnimal = 0;
		    	int currentImage = 0;
		    	NeuroTracker ntInstance = NeuroTracker.this;
				if(ntInstance.initialPositions.isEmpty()) {
					ntInstance.paused = true;
					System.out.println("No neuron coordinates have been selected");
				}
	    		currentImage = ntInstance.currentImageIndex;
		    	for(int imgIndex = currentImageIndex; imgIndex < ntInstance.fileList.length; imgIndex++) {
		    		ntInstance.setImage(imgIndex);
		    		if(ntInstance.paused) {
						//ntInstance.currentAnimal = a;
						return;
					}
		    		ntInstance.currentImageIndex = imgIndex;
			    	int numAnimals = ntInstance.initialPositions.size();
		    		currentAnimal = ntInstance.currentAnimal;
			    	for(int a = currentAnimal; a < numAnimals; a++) {
			    		if(ntInstance.paused) {
							//ntInstance.currentAnimal = a;
							return;
						}
			    		//System.out.println("animal: " + a);
				    	currentSlice = ntInstance.image.getCurrentSlice();
				    	for(int i = Math.max(1,  currentSlice); i <= ntInstance.numImages; i++) {
				    		if(ntInstance.redFlagDelay) {
				    			IJ.wait(450);
				    			ntInstance.redFlagDelay = false;
				    		}
				    		if(i == 1) {
				    			ntInstance.resetPositions(a);
				    		}
				    		ntInstance.currentAnimal = a;
				    		ntInstance.currentSlice = i;
							if(ntInstance.paused) {
								//ntInstance.currentAnimal = a;
								return;
							}
							ntInstance.image.setSlice(i);
							NeuronInfo prevNeuronInfo = positions[i - 1];
							if(prevNeuronInfo == null) {
								/*System.out.println("No neurons selected. Cancelling operation");
								ntInstance.currentSlice = 0;
								ntInstance.paused = true;
								return;*/
								//this should be changed, maybe add a flag if the slice was recently just clicked on
								//we need to process the same slice that was already clicked on to gather the 
								//particle analysis data
								prevNeuronInfo = positions[i];
								if(prevNeuronInfo == null) {
									System.out.println("No neurons selected for slice " + i + ". Please select a position");
									ntInstance.paused = true;
									return;
								}
							}
							double xc = prevNeuronInfo.xc;
							double yc = prevNeuronInfo.yc;
							double lowerThreshold = ntInstance.settings.get("lowerThreshold");
							double upperThreshold = ntInstance.settings.get("upperThreshold");
							//double w = ntInstance.settings.get("w");
							//double h = ntInstance.settings.get("h");
							double searchDia = ntInstance.settings.get("searchDia");
							double bgRingDia = ntInstance.settings.get("bgRingDia");
							double sqsize = ntInstance.settings.get("sqSize");
							double maxSize = ntInstance.settings.get("maxSize");
							double minSize = ntInstance.settings.get("minSize");
							double expandAllow = ntInstance.settings.get("expandAllow");
							int velocityPredict = ntInstance.settings.get("velocityPredict");
							NeuronInfo next = ntInstance.analyze(xc, yc, searchDia, bgRingDia, minSize, maxSize, 0.5, lowerThreshold, 
									upperThreshold, sqsize, expandAllow, velocityPredict == 1 ? true : false);
							if(next != null) {
								positions[i] = next;
							}
						}
			    		if(ntInstance.image.getCurrentSlice() == ntInstance.numImages) {
			    			ntInstance.serialize(a);
			    			ntInstance.plot();
					    	//NeuroTracker.this.paused = true;
					    	//System.out.println("finished processing");
					    	ntInstance.currentSlice = 1;
					    	ntInstance.image.setSlice(1);
					    	ntInstance.saveFinalPositions();
			    			//delete animal coordinates from map
			    		}
			    	} //end animal
			    	//prompt here for next image
			    	//IJ.showMessage("Info", "NeuroTracker will now proceed to the next video");
			    	
			    	//use the ending positions as the next initial positions
		    	}
		    	IJ.showMessage("Info", "NeuroTracker has completed the neuron analysis. Please exit the plugin");
		    	ntInstance.image.changes = false;
		    	ntInstance.image.close();
		    }
		}).start();
	}
	
	public NeuronInfo analyze(double xc, double yc, double searchDia, double bgRingDia, double minSize, double maxSize, 
			double searchBoxScale, double lowerThreshold, double upperThreshold, double sqsize, double expandAllow, boolean velocityPredict) {
		IJ.run("Set Scale...", "distance=0");
		makeOval(xc - searchDia / 2, yc - searchDia / 2, searchDia, searchDia);
		//makeOval(xc - searchBoxScale * w, yc - searchBoxScale * h, w, h);
		//IJ.runMacro("makeOval(" + (xc - searchBoxScale * w) + "," +  (yc - searchBoxScale * h) + "," + w + "," + h + ")");
		IJ.run(this.image, "Set Measurements...", "area min centroid center integrated slice limit redirect=None decimal=3");
		ResultsTable rt = new ResultsTable();
		ParticleAnalyzer pa = new ParticleAnalyzer(ParticleAnalyzer.SHOW_NONE /*+ ParticleAnalyzer.DISPLAY_SUMMARY*/
				+ ParticleAnalyzer.CLEAR_WORKSHEET + ParticleAnalyzer.SLICE, ParticleAnalyzer.AREA + ParticleAnalyzer.MIN_MAX
				+ ParticleAnalyzer.CENTROID + ParticleAnalyzer.CENTER_OF_MASS + ParticleAnalyzer.INTEGRATED_DENSITY
				+ ParticleAnalyzer.SLICE + ParticleAnalyzer.LIMIT, rt, minSize, maxSize, 0d, 1d);
		//IJ.run(this.image, "Analyze Particles...", "size=" + minSize + "-" + maxSize + "circularity=0.00-1.00 show=Nothing display clear slice");
		//pa.setHideOutputImage(true);
		pa.analyze(this.image);
		//System.out.println(Analyzer.getResultsTable().size());
		//System.out.println(ResultsTable.getResultsTable().size());
		
		//IJ.run(this.image, "Analyze Particles...", "size=" + minSize + "-" + maxSize + " circularity=0.00-1.00 show=Nothing display clear slice");
		ResultsTable resultsTable = rt;//ResultsTable.getResultsTable();
		//resultsTable.show("Results");
		double xm, ym, area, maxInt, intDens, x, y, avg;
		if(resultsTable.size() == 1) {
			xm = resultsTable.getValue("XM", 0);
			ym = resultsTable.getValue("YM", 0);
            area = resultsTable.getValue("Area", 0);
            maxInt = resultsTable.getValue("Max", 0);
            intDens = resultsTable.getValue("IntDen", 0);
            x = resultsTable.getValue("X", 0);
            y = resultsTable.getValue("Y", 0);
            avg = intDens / area;
		}
		else if(resultsTable.size() > 1) {
            double biggestArea = 0;
            int biggestAreaPos = 0;
            for (int res = 0; res < resultsTable.size(); res++) {
                double resArea = resultsTable.getValue("Area", res);
                if (resArea > biggestArea) {
                    biggestArea = resArea;
                    biggestAreaPos = res;
                }
            }

            xm = resultsTable.getValue("XM", biggestAreaPos);
            ym = resultsTable.getValue("YM", biggestAreaPos);
            area = resultsTable.getValue("Area", biggestAreaPos);
            maxInt = resultsTable.getValue("Max", biggestAreaPos);
            intDens = resultsTable.getValue("IntDen", biggestAreaPos);
            x = resultsTable.getValue("X", biggestAreaPos);
            y = resultsTable.getValue("Y", biggestAreaPos);
            avg = intDens / area;
		}
		else {
			int expand = 0;
			int numResults = 0;
			//int expandAllow = 0; //set
			while(numResults < 1 && expand <= expandAllow) {
				//IJ.makeOval(xc - searchBoxScale * w - expand, yc - searchBoxScale * h - expand, w + 5 * expand, h + 2 * expand);
				//double radiusX = w + 5 * expand;
				//double radiusY = h + 3 * expand;
				//makeOval(X-(rad/2), Y-(rad/2), rad, rad);
				makeOval(xc - searchDia / 2 - expand, yc - searchDia / 2 - expand, searchDia + 2 * expand, searchDia + 2 * expand);
				//makeOval(xc - (radiusX / 2), yc - (radiusY / 2), radiusX, radiusY);
				//IJ.run(this.image, "Analyze Particles...", "size=" + minSize + "-" + maxSize + " circularity=0.00-1.00 show=Nothing display clear slice");
				ResultsTable newRT = new ResultsTable();
				pa = new ParticleAnalyzer(ParticleAnalyzer.SHOW_NONE /*+ ParticleAnalyzer.DISPLAY_SUMMARY*/
						+ ParticleAnalyzer.CLEAR_WORKSHEET + ParticleAnalyzer.SLICE, ParticleAnalyzer.AREA + ParticleAnalyzer.MIN_MAX
						+ ParticleAnalyzer.CENTROID + ParticleAnalyzer.CENTER_OF_MASS + ParticleAnalyzer.INTEGRATED_DENSITY
						+ ParticleAnalyzer.SLICE + ParticleAnalyzer.LIMIT, newRT, minSize, maxSize, 0d, 1d);
				pa.analyze(this.image);
				resultsTable = newRT;
				numResults = resultsTable.size();
				expand++;
			}
			//System.out.println(resultsTable.size());
			//resultsTable.show("results");
			if(resultsTable.size() == 1) {
				xm = resultsTable.getValue("XM", 0);
				ym = resultsTable.getValue("YM", 0);
	            area = resultsTable.getValue("Area", 0);
	            maxInt = resultsTable.getValue("Max", 0);
	            intDens = resultsTable.getValue("IntDen", 0);
	            x = resultsTable.getValue("X", 0);
	            y = resultsTable.getValue("Y", 0);
	            avg = intDens / area;
			}
			//Lost track of neuron. Prompt user to click on neuron again
			else {
				System.out.println("lost track of neuron");
				this.currentSlice = this.currentSlice - 1;
				this.image.setSlice(this.image.getCurrentSlice() - 1);
                makeRectangle(xc - sqsize / 2, yc - sqsize / 2, sqsize, sqsize);
                IJ.showMessage("Error", "NeuroTracker has lost track of the neuron. Please manually select the neuron location(s) again.");
                this.paused = true;
                this.lostTrack = true;
                IJ.setThreshold(lowerThreshold, upperThreshold);
                //IJ.wait(100);
                IJ.setThreshold(lowerThreshold, 65535);
                IJ.run(this.image, "Clear Results", "");
                return null;
            }
		}
		
		//changed xc, yc to xm, ym
		makeRectangle(xm - sqsize / 2, ym - sqsize / 2, sqsize, sqsize);
		IJ.run(this.image, "Clear Results", "");
		//IJ.setThreshold(lowerThreshold, 65535);
		IJ.run(this.image, "Set Measurements...", "area min centroid center integrated slice redirect=None decimal=3");
		//IJ.run(this.image, "Measure","");
		resultsTable = new ResultsTable();
		Analyzer a = new Analyzer(this.image, resultsTable);
		a.measure();
		
		//IJ.run("Measure");
		
		//resultsTable = Analyzer.getResultsTable();
		double sqArea = 0;
		double sqIntDens = 0;
		if(resultsTable.size() == 1) {
			sqArea = resultsTable.getValue("Area", 0);
			sqIntDens = resultsTable.getValue("IntDen", 0);
		}
		
		IJ.run(this.image, "Add Selection...", "stroke=yellow width=1 fill=0");
		//this.image.setPosition(this.currentSlice); //???
		
		/*double offsetx = 0, offsety = 0; //set
	    offsetx = -1 * (2 * w);
	    offsety = -1 * (2 * h);*/

	    /*makeOval(xm - 1.2 * w, ym - 1.2 * h, 2.4 * w, 2.4 * h);
        IJ.setKeyDown(KeyEvent.VK_ALT); //alt key
        makeOval(xm - 0.7 * w, ym - 0.7 * h, 1.4 * w, 1.4 * h);*/
        //IJ.setKeyUp(IJ.ALL_KEYS);
		
		double BG_RING_OUT = 1.5;
        makeOval(xc - bgRingDia / 2 * BG_RING_OUT, yc - bgRingDia / 2 * BG_RING_OUT, bgRingDia * BG_RING_OUT, bgRingDia * BG_RING_OUT);
        IJ.setKeyDown(KeyEvent.VK_ALT);
        makeOval(xc - bgRingDia / 2, yc - bgRingDia / 2, bgRingDia, bgRingDia);
		
		IJ.run("Clear Results", "");
		IJ.run("Set Measurements...", "area mean min median slice redirect=None decimal=3");
		resultsTable = new ResultsTable();
		a = new Analyzer(this.image, resultsTable);
		a.measure();
		//IJ.run("Measure");
		//IJ.run(this.image, "Measure","");
		
		double l = this.image.getProcessor().getMinThreshold();
		
		//resultsTable = Analyzer.getResultsTable();
		
		if(velocityPredict) {
			double dx = xm - xc;
			double dy = ym - yc;
			xm = xm + dx / 2;
			ym = ym + dy / 2;
		}
		
		NeuronInfo neuronInfo = new NeuronInfo();
		neuronInfo.lowerThreshold = l;
		neuronInfo.xc = xm;
		neuronInfo.yc = ym;
		neuronInfo.avg = avg;
		neuronInfo.intDens = intDens;
		neuronInfo.maxInt = maxInt;
		neuronInfo.area = area;
		neuronInfo.x = x;
		neuronInfo.y = y;
		neuronInfo.sqArea = sqArea;
		neuronInfo.sqIntDens = sqIntDens;
		neuronInfo.redFlag = this.redFlag;
		if(resultsTable.size() == 1) {
			neuronInfo.bgAvg = resultsTable.getValue("Mean", 0);
			neuronInfo.bgMedian = resultsTable.getValue("Median", 0);
			neuronInfo.intSub = intDens - (area * neuronInfo.bgMedian);
			neuronInfo.sqIntSub = sqIntDens - (sqArea * neuronInfo.bgMedian);
		}
		neuronInfo.bgMedian2 = neuronInfo.bgMedian;
		return neuronInfo;
	}
	
	void makeOval(double x, double y, double w, double h) {
		Roi previousRoi = this.image.getRoi();
		boolean shiftKeyDown = IJ.shiftKeyDown();
		boolean altKeyDown = IJ.altKeyDown();
		if(shiftKeyDown || altKeyDown) {
			this.image.saveRoi();
		}
		IJ.makeOval(x, y, w, h);
		Roi roi = this.image.getRoi();
		if(previousRoi != null && roi != null) {
			if(shiftKeyDown || altKeyDown) {
				roi.update(shiftKeyDown, altKeyDown);
			}
		}
		IJ.setKeyUp(IJ.ALL_KEYS);
	}
	
	void makeRectangle(double x, double y, double w, double h) {
		Roi previousRoi = this.image.getRoi();
		boolean shiftKeyDown = IJ.shiftKeyDown();
		boolean altKeyDown = IJ.altKeyDown();
		if(shiftKeyDown || altKeyDown) {
			this.image.saveRoi();
		}
		IJ.makeRectangle(x, y, w, h);
		Roi roi = this.image.getRoi();
		if(previousRoi != null && roi != null) {
			if(shiftKeyDown || altKeyDown) {
				roi.update(shiftKeyDown, altKeyDown);
			}
		}
		IJ.setKeyUp(IJ.ALL_KEYS);
	}
	
	void serialize(int animal) {
		String fileName = this.directory + this.image.getTitle().replaceAll("\\.tif", "") + ".an" + animal + ".txt";
		File file = new File(fileName);
		boolean exists = file.exists();
		boolean overwrite = false;
		if(exists) {
			YesNoCancelDialog dialog = new YesNoCancelDialog(WindowManager.getFrame(this.image.getTitle()), "Info", 
					"Animal trace file already detected. Do you want to overwrite the file?", "yes","no");
			if(dialog.yesPressed()) {
				overwrite = true;
			}
		}
		
		if(exists && !overwrite)
			return;
		
		System.out.println("saving results to " + fileName);
		FileWriter fw;
		try {
			fw = new FileWriter(fileName);
			fw.write("Slice,xc,yc,intdens,intsub,bgmedian,maxint,area,x,y,sqintdens,sqintsub,sqarea,threshold,animal,redFlag,useTracking\n");
			for(int i = 1; i < this.positions.length; i++) {
				//NeuronInfo ni = slices.get(i);
				NeuronInfo ni = positions[i];
				if(ni == null)
					continue;
				NumberFormat formatter = new DecimalFormat("#0.000");  
				NumberFormat formatter2 = new DecimalFormat("#0");
				String line = i + "," + formatter.format(ni.xc) + "," + formatter.format(ni.yc) + "," + formatter.format(ni.intDens) + "," 
				+ formatter.format(ni.intSub) + "," + formatter.format(ni.bgMedian) + "," + formatter.format(ni.maxInt)
						+ "," + formatter.format(ni.area) + "," + formatter.format(ni.x) + "," + formatter.format(ni.y) + "," 
				+ formatter.format(ni.sqIntDens) + "," + formatter.format(ni.sqIntSub) + "," + formatter.format(ni.sqArea) + ","
						+ formatter2.format(ni.lowerThreshold) + "," + animal + "," + (ni.redFlag ? 1 : 0) + "," + "1";
				fw.write(line);
				fw.write("\n");
			}
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	boolean positionsFileExists() {
		String title = this.image.getTitle();
		title = title.replaceAll("\\.tif", "");
		String fileName = this.directory + title + "_Pos.txt";
		File file = new File(fileName);
		if(file.exists())
			return true;
		fileName = this.directory + "initialPos.txt";
		file = new File(fileName);
		return file.exists();
	}
	
	void loadSavedPositions() throws IOException {
		String title = this.image.getTitle();
		title = title.replaceAll("\\.tif", "");
		String fileName = this.directory + title + "_Pos.txt";
		
		File file = new File(fileName);
		if(!file.exists()) {
			fileName = this.directory + "initialPos.txt";
		}
		
        BufferedReader reader = new BufferedReader(new FileReader(fileName));
        String line = null;    
        try {
        	PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[2], b[2]));
            while((line = reader.readLine()) != null) {         	
        		String[] s = line.split(" ");
            	double x = Double.parseDouble(s[1]);
            	double y = Double.parseDouble(s[3]);
            	double animal = Double.parseDouble(s[5]);
            	double threshold = Double.parseDouble(s[7]);
            	System.out.println(threshold);
            	queue.offer(new double[] {x, y, animal, threshold});
            }
            while(!queue.isEmpty()) {
            	double[] pos = queue.poll();
            	//add threshold
            	this.addInitialPosition(pos[0], pos[1], pos[3]);
            }

        } finally {
			reader.close();
        }
	}
	
	void saveInitialPositions() {
		System.out.println("saving initial positions");
		String title = this.image.getTitle();
		title = title.replaceAll("\\.tif", "");
		String fileName = this.directory + title + "_Pos.txt";
		String initialPosFileName = this.directory + "initialPos.txt";
		System.out.println(fileName);
		FileWriter fw;
		FileWriter fw2 = null;
		try {
			fw = new FileWriter(fileName);
			if(!this.initialPosFileWritten) {
				fw2 = new FileWriter(initialPosFileName);
				this.initialPosFileWritten = true;
			}
			//double lower = this.image.getProcessor().getMinThreshold();
			//fw.write(String.valueOf(lower) + '\n');
			for(int i = 0; i < this.initialPositions.size(); i++) {
				String s = "x " + this.initialPositions.get(i).xc + " y " + this.initialPositions.get(i).yc + " a " + i 
						+ " t " + this.initialPositions.get(i).lowerThreshold + " f 0 g 1" + "\n";
				fw.write(s);
				if(fw2 != null) fw2.write(s);
			}
			fw.close();
			if(fw2 != null) fw2.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	void plot() {
		Plot plot = new Plot("Integrated intensity - Animal " + this.currentAnimal, "Frame", "Sq. Intensity");
		int size = this.positions.length - 1;
		//double[] intensities = new double[size];
		//double[] bgMed = new double[size];
		ArrayList<Double> intensities = new ArrayList<>();
		ArrayList<Double> bgMed = new ArrayList<>();
		for(int i = 1; i < this.positions.length; i++) {
			NeuronInfo ni = this.positions[i];
			if(ni == null)
				continue;
			intensities.add(ni.sqIntSub);
			bgMed.add(ni.bgMedian2);
		}
		plot.setColor(Color.BLACK);
		double[] arr1 = new double[intensities.size()];
		for(int i = 0; i < intensities.size(); i++) {
			arr1[i] = intensities.get(i);
		}
		double[] arr2 = new double[bgMed.size()];
		for(int i = 0; i < bgMed.size(); i++) {
			arr2[i] = bgMed.get(i);
		}
		plot.add("line", arr1);
		plot.setColor(Color.RED);
		plot.add("line", arr2);
		plot.setLegend("Intensity - BgMedian\tBgMedian", Plot.TOP_RIGHT);
		plot.setLimits(0, Double.NaN, 0, Double.NaN);
		PlotWindow plotWindow = plot.show();
		//ImagePlus plotImg = IJ.createImage("plot", "", 800, 600, 0);
		//PlotWindow window = new PlotWindow(plotImg, plot);
		//WindowManager.addWindow(window);
		//window.setFocusable(false);
		//window.setVisible(true);
		IJ.wait(5000);
		plotWindow.close();
		IJ.selectWindow(this.image.getTitle());
		//plotWindow.invalidate();
	}

	/**
	 * Main method for debugging.
	 *
	 * For debugging, it is convenient to have a method that starts ImageJ, loads
	 * an image and calls the plugin, e.g. after setting breakpoints.
	 *
	 * @param args unused
	 */
	public static void main(String[] args) throws Exception {
		// set the plugins.dir property to make the plugin appear in the Plugins menu
		// see: https://stackoverflow.com/a/7060464/1207769
		Class<?> clazz = NeuroTracker.class;
		java.net.URL url = clazz.getProtectionDomain().getCodeSource().getLocation();
		java.io.File file = new java.io.File(url.toURI());
		System.setProperty("plugins.dir", file.getAbsolutePath());

		// start ImageJ
		new ImageJ();
		
		//stream_2016-01-26-15-01_mov001
		//stream_2016-02-16-15-11_mov001
		//ImagePlus image = IJ.openImage("C:\\Users\\nickc\\Desktop\\NTVideos\\stream_2016-01-26-15-01_mov001.tif");
		//image.show();

		// run the plugin
		IJ.runPlugIn(clazz.getName(), "");
	}

	@Override
	public void mouseClicked(MouseEvent e) {
		//add neuron coordinate
		
	}
	
	public void addInitialPosition(double xc, double yc, double threshold) {
		System.out.println("added " + xc + ", " + yc);
		NeuronInfo neuron = new NeuronInfo();
		neuron.xc = xc;
		neuron.yc = yc;
		neuron.lowerThreshold = threshold;
		//int numNeurons = this.neuronCoordinates.size();
		//if lost track, then just replace the current neuron coordinate for the current animal
		//this.neuronCoordinates.put(numNeurons, neurons);
		//System.out.println(numNeurons);
		
		this.initialPositions.add(neuron);
		
		double sqsize = this.settings.get("sqSize");
		IJ.makeRectangle(xc - sqsize / 2, yc - sqsize / 2, sqsize, sqsize);
		IJ.run("Add Selection...", "stroke=yellow width=1 fill=0");
		
		//IJ.selectWindow("Log");
		IJ.log("added " + "x: " + xc + ", " + "y: " + yc + ", threshold: " + threshold);
		IJ.selectWindow(this.image.getTitle());
		
		IJ.makeRectangle(xc + sqsize / 2, yc - sqsize / 2, 24, 18);
		IJ.run("Labels...", "color=white font=12 show use draw");
		IJ.run("Properties... ", "name=" + (this.initialPositions.size() - 1));
		IJ.run("Add Selection...", "stroke=black width=0 fill=0");
	}
	
	public void setPosition(int animal, int slice, double xc, double yc) {
		NeuronInfo neuron = this.positions[slice];
		if(neuron == null) {
			neuron = new NeuronInfo();
		}
		neuron.xc = xc;
		neuron.yc = yc;
		this.positions[slice] = neuron;
	}

	@Override
	public void mousePressed(MouseEvent e) {
		if(this.doneSelecting && !this.paused) {
			this.paused = true;
			IJ.showMessage("Warning", "Please do not click on the image canvas or anywhere else while NeuroTracker "
					+ "is running. It will screw up tracking and it'll be your fault that it doesn't work!");
		}

		if(this.paused) {
			//record coordinate
			if(this.lostTrack) {
				Point p = this.image.getCanvas().getCursorLoc();
				System.out.println("set position at " + p.x + "," + p.y + " for slice " + this.image.getCurrentSlice()
						+ " at animal " + this.currentAnimal);
				/*ArrayList<NeuronInfo> coordinates = this.neuronCoordinates.get(this.currentAnimal);
				coordinates.subList(this.image.getCurrentSlice(), coordinates.size()).clear();*/
				//IJ.selectWindow("Log");
				//IJ.log("set " + "x: " + p.x + ", " + "y: " + p.y + ", threshold: " + this.image.getProcessor().getMinThreshold());
				IJ.selectWindow(this.image.getTitle());
				this.setPosition(this.currentAnimal, this.image.getCurrentSlice(), p.x, p.y);
				this.lostTrack = false;
				this.paused = false;
				//this.currentSlice++;
				this.currentSlice = this.image.getCurrentSlice() + 1;
				this.image.setSlice(this.image.getCurrentSlice() + 1);
				this.process();
				return;
			}
			//this.neuronCoordinates.clear();
			Point p = this.image.getCanvas().getCursorLoc();
			
			if(!this.doneSelecting) {
				double lower = this.image.getProcessor().getMinThreshold();
				this.addInitialPosition(p.x, p.y, lower);
				return;
			}
			//set position instead
			Point point = this.image.getCanvas().getCursorLoc();
			System.out.println(this.image.getCurrentSlice() + " vs " + this.currentSlice);
			System.out.println("set position at " + point.x + "," + point.y + " for slice " + this.image.getCurrentSlice()
					+ " at animal " + this.currentAnimal);
			//ArrayList<NeuronInfo> coordinates = this.neuronCoordinates.get(this.currentAnimal);
			//System.out.println(this.image.getImageStackSize());
			//coordinates.subList(this.image.getCurrentSlice()/* - 1*/, coordinates.size()).clear();
			//IJ.log("set " + "x: " + p.x + ", " + "y: " + p.y + ", threshold: " + this.image.getProcessor().getMinThreshold());
			//IJ.selectWindow(this.image.getTitle());
			this.setPosition(this.currentAnimal, this.image.getCurrentSlice(), point.x, point.y);
			this.image.setSlice(this.image.getCurrentSlice() + 1);
			double sqsize = this.settings.get("sqSize");
			IJ.makeRectangle(point.x - sqsize / 2, point.y - sqsize / 2, sqsize, sqsize);
			IJ.run("Add Selection...", "stroke=yellow width=1 fill=0");
		}
		//else {
			
		//}
	}

	@Override
	public void mouseReleased(MouseEvent e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void mouseEntered(MouseEvent e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void mouseExited(MouseEvent e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void keyTyped(KeyEvent e) {
		// TODO Auto-generated method stub
	}

	@Override
	public void keyPressed(KeyEvent e) {
		if(e.getKeyCode() == KeyEvent.VK_SPACE) {
			if(!this.doneSelecting) {
				this.saveInitialPositions();
				IJ.selectWindow("Log");
				IJ.run("Close");
				IJ.selectWindow(this.image.getTitle());
				this.doneSelecting = true;
			}
			System.out.println("paused: " + !this.paused);
			if(this.paused) {
				this.paused = false;
				this.process();
			}
			else {
				this.paused = true;
			}
		}
		else if(e.getKeyCode() == KeyEvent.VK_V) {
			this.saveInitialPositions();
		}
		else if(e.getKeyCode() == KeyEvent.VK_Q) {
			this.redFlag = !this.redFlag;
			this.redFlagDelay = true;
		}
	}

	@Override
	public void keyReleased(KeyEvent e) {
	}
}
