package au.com.codecamp.unpacker;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

/**
 * Created by Chris on 6/18/2015.
 */
public class Main {

    public static class CommandLineParameters {

        @Parameter(names = "-out", description = "Output directory (defaults to current)", required = false)
        private String targetDirectory;

        @Parameter(description = "input file or directory", required = true)
        private ArrayList<String> sources;


        @Parameter(names = "-pad", description = "amount to pad around detection", required = false)
        private int pad;

        @Parameter(names = "-min", description = "minimum standard deviation on area", required = false)
        private Float minStdDev;

        @Parameter(names = "-max", description = "maximum standard deviation on area", required = false)
        private Float maxStdDev;


        @Parameter(names = "-chunk", description = "maximum number of sprites per sheet", required = false)
        private Integer chunkSize;

        @Parameter(names = "-maxHeight", description = "scale anything above this height down", required = false)
        private Integer maxHeight;

        public CommandLineParameters(String targetDirectory) {
            this.targetDirectory = targetDirectory;
            this.pad = 0;
            this.minStdDev = null;
            this.maxStdDev = null;
            this.chunkSize = null;
            this.maxHeight = null;
        }

        public String getTargetDirectory() {
            return targetDirectory;
        }

        public ArrayList<String> getSources() {
            return sources;
        }

        public int getPad() {
            return this.pad;
        }

        public Float getMinStdDev() {
            return minStdDev;
        }

        public Float getMaxStdDev() {
            return maxStdDev;
        }

        public Integer getChunkSize() {
            return this.chunkSize;
        }

        public Integer getMaxHeight() {
            return maxHeight;
        }
    }

    private static class Box {

        public int minx;
        public int miny;
        public int maxx;
        public int maxy;

        public Box(int x, int y) {
            this.minx = x;
            this.miny = y;
            this.maxx = x;
            this.maxy = y;
        }

        public void add(int x, int y) {
            this.minx = Math.min(this.minx, x);
            this.maxx = Math.max(this.maxx, x);
            this.miny = Math.min(this.miny, y);
            this.maxy = Math.max(this.maxy, y);
        }

        public boolean overlaps(Box box) {
            return
                    (box.minx <= this.minx && box.maxx >= this.minx || this.minx <= box.minx && this.maxx >= box.minx) &&
                    (box.miny <= this.miny && box.maxy >= this.miny || this.miny <= box.miny && this.maxy >= box.miny);
        }

        public void merge(Box box) {
            this.minx = Math.min(this.minx, box.minx);
            this.maxx = Math.max(this.maxx, box.maxx);
            this.miny = Math.min(this.miny, box.miny);
            this.maxy = Math.max(this.maxy, box.maxy);
        }

        public void pad(int padding) {
            this.minx -= padding;
            this.maxx += padding;
            this.miny -= padding;
            this.maxy += padding;
        }

        public int getWidth() {
            return (this.maxx - this.minx) + 1;
        }

        public int getHeight() {
            return (this.maxy - this.miny) + 1;
        }

        public int getArea() {
            return this.getWidth() * this.getHeight();
        }

        public int getWeight(int imageWidth, int imageHeight) {
            int stepHeight = Math.max(1, this.getHeight() / 4);
            return (this.minx + this.maxx)/2 + (((this.miny + this.maxy) / stepHeight) * stepHeight) * imageWidth;
        }

    }


    public static final void main(String[] args) {

        String userDirectory = System.getProperty("user.dir");
        CommandLineParameters parameters = new CommandLineParameters(userDirectory);
        JCommander commander = new JCommander(parameters);
        try {
            commander.parse(args);
        } catch( ParameterException ex ) {
            ex.printStackTrace(System.err);
            commander.usage(Main.class.getName());
        }

        for( String sourcePath : parameters.getSources() ) {
            File source = new File(sourcePath);
            File sourceDirectory;
            if( source.isDirectory() ) {
                sourceDirectory = source;
            } else {
                sourceDirectory = source.getParentFile();
            }
            String absoluteSourcePath = sourceDirectory.getAbsolutePath();

            File outputDirectory = new File(parameters.getTargetDirectory());

            ArrayList<File> files = new ArrayList<File>();
            toFiles(source, files);


            for( File inputFile : files ) {
                String inputPath = inputFile.getAbsolutePath();
                String commonPath = inputPath.substring(absoluteSourcePath.length()+1);
                File outputFile = new File(outputDirectory, commonPath);
                File outputDir = outputFile.getParentFile();
                outputDir.mkdirs();
                System.out.println("converting "+commonPath+" ...");
                try {
                    convert(inputFile, outputDir, parameters.getPad(), parameters.getMinStdDev(), parameters.getMaxStdDev(), parameters.getChunkSize(), parameters.getMaxHeight());
                } catch( Exception ex ) {
                    System.err.println("unable to convert "+commonPath);
                    ex.printStackTrace();
                }
            }
        }
    }

    public static final void convert(File inputFile, File outputDir, int pad, Float minStdDev, Float maxStdDev, Integer chunkSize, Integer maxScaledHeight) throws Exception {
        // read in an image
        BufferedImage input = ImageIO.read(inputFile);
        // convert to ARGB
        final BufferedImage converted = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_ARGB);
        converted.getGraphics().drawImage(input, 0, 0, null);
        // flood fill transparency from the top-left corner
        int transparencyValue = converted.getRGB(0, 0);
        if( (transparencyValue & 0xFF000000) != 0 ) {
            floodFill(0, 0, converted, 0, 0, converted.getWidth()-1, converted.getHeight()-1, transparencyValue, 0x0);

        }

        BufferedImage map = new BufferedImage(converted.getWidth(), converted.getHeight(), BufferedImage.TYPE_INT_ARGB);
        HashMap<Integer, Box> boxMap = new HashMap<Integer, Box>();
        for( int x=converted.getWidth(); x>0; ) {
            x--;
            for( int y=converted.getHeight(); y>0; ) {
                y--;
                int p = converted.getRGB(x, y);
                int masked = p & 0xFF000000;
                if( masked != 0 ) {
                    int o = map.getRGB(x, y);
                    if( o == 0 ) {
                        int index = boxMap.size() + 1;
                        Box box = new Box(x, y);
                        flood(x, y, converted, map, index, box);
                        box.pad(pad);
                        boxMap.put(index, box);
                    }
                }
            }
        }
        // merge any overlapping boxes

        ArrayList<Box> boxes = new ArrayList<Box>(boxMap.values());

        for( int i=boxes.size(); i>0; ) {
            i--;
            Box boxi = boxes.get(i);
            for( int j=i; j>0; ) {
                j--;
                Box boxj = boxes.get(j);
                if( boxi.overlaps(boxj) ) {
                    // merge
                    boxi.merge(boxj);
                    boxes.remove(j);
                    i--;
                }
            }
        }

        // calculate the average area
        int totalArea = 0;
        for( Box box : boxes ) {
            totalArea += box.getArea();
        }

        // peg it at the min and max so we don't rip out useful entries
        // remove any boxes that who's area falls outside these ranges
        Collections.sort(boxes, new Comparator<Box>() {
            @Override
            public int compare(Box o1, Box o2) {
                return o1.getArea() - o2.getArea();
            }
        });
        int index = (boxes.size() * 19) / 20;
        // assume we have some very large garbage, but everything else is fine
        if( index >= boxes.size() - 2 ) {
            index = Math.max(0, boxes.size() - 3);
        }
        int medianArea = boxes.get(index).getArea();

        int maxWidth = 0;
        int maxHeight = 0;
        for( int i = boxes.size(); i>0 ; ) {
            i--;
            Box box = boxes.get(i);
            int area = box.getArea();
            boolean remove = false;
            if( maxStdDev != null ) {
                if( area > (maxStdDev * medianArea) ) {
                    remove = true;
                }
            }
            if( minStdDev != null ) {
                if( area < (minStdDev * medianArea) ) {
                    remove = true;
                }
            }
            if( remove ) {
                boxes.remove(i);
            } else {
                box.pad(-pad);
                maxWidth = Math.max(maxWidth, box.getWidth());
                maxHeight = Math.max(maxHeight, box.getHeight());
            }
        }

        // sort the boxes according to position
        Collections.sort(boxes, new Comparator<Box>() {
            @Override
            public int compare(Box o1, Box o2) {
                return o1.getWeight(converted.getWidth(), converted.getHeight()) - o2.getWeight(converted.getWidth(), converted.getHeight());
            }
        });

        // churn out to a new image at the new size
        if( chunkSize == null ) {
            chunkSize = boxes.size();
        }
        int position = 0;
        int fileCount = 0;
        while( position < boxes.size() ) {

            int remaining = Math.min(chunkSize, boxes.size() - position);
            BufferedImage output = new BufferedImage(maxWidth * remaining, maxHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics g = output.getGraphics();
            int x = 0;
            int count = 0;
            while( count < chunkSize && position < boxes.size() ) {
                Box box = boxes.get(position);
                int dx1 = x + (maxWidth - box.getWidth())/2;
                int dx2 = dx1 + box.getWidth();
                int dy1 = maxHeight - box.getHeight();
                int dy2 = maxHeight;

                // check the bounds of this image aren't contained in a different color
                int a = converted.getRGB(box.minx, box.miny);
                int b = converted.getRGB(box.minx, box.maxy);
                int c = converted.getRGB(box.maxx, box.maxy);
                int d = converted.getRGB(box.maxx, box.miny);
                if( a == b && b == c && c == d ) {
                    replace(converted, box.minx, box.miny, box.maxx, box.maxy, a, 0x0);
                }

                g.drawImage(converted, dx1, dy1, dx2, dy2, box.minx, box.miny, box.maxx+1, box.maxy+1, null);

                x += maxWidth;
                count++;
                position++;
            }
            fileCount++;

            String fileName = inputFile.getName();
            // remove any '.' character
            index = fileName.lastIndexOf('.');
            if( index >= 0 ) {
                fileName = fileName.substring(0, index);
            }
            fileName += "_"+fileCount+"_c" + remaining+".png";

            if( maxScaledHeight != null && maxScaledHeight < maxHeight ) {
                // scale it down
                int scaledWidth = (output.getWidth() * maxScaledHeight) / output.getHeight();
                BufferedImage scaledImage = new BufferedImage(scaledWidth, maxScaledHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D scaledGraphics = (Graphics2D)scaledImage.getGraphics();
                scaledGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                scaledGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                scaledGraphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
                scaledGraphics.drawImage(output, 0, 0, scaledWidth, maxScaledHeight, null);
                output = scaledImage;
            }

            File outputFile = new File(outputDir, fileName);
            ImageIO.write(output, "PNG", outputFile);
        }
    }

    public static final void replace(BufferedImage image,  int iminx, int iminy, int imaxx, int imaxy, int matchValue, int setValue) {
        for( int x=iminx; x<= imaxx; x++ ) {
            for( int y=iminy; y<= imaxy; y++ ) {
                int p = image.getRGB(x, y);
                if( p == matchValue ) {
                    image.setRGB(x, y, setValue);
                }
            }
        }
    }

    public static final void floodFill(int sx, int sy, BufferedImage image,  int iminx, int iminy, int imaxx, int imaxy, int matchValue, int setValue) {
        ArrayList<Point> points = new ArrayList<Point>();
        points.add(new Point(sx, sy));
        while( !points.isEmpty() ) {
            Point point = points.remove(points.size() - 1);
            int x = point.x;
            int y = point.y;
            if( x >= iminx && y >= iminy && x <= imaxx && y < imaxy ) {
                int p = image.getRGB(x, y);
                if( p == matchValue ) {
                    image.setRGB(x, y, setValue);
                    points.add(new Point(x + 1, y));
                    points.add(new Point(x - 1, y));
                    points.add(new Point(x, y + 1));
                    points.add(new Point(x, y - 1));
                }

            }
        }
    }

    public static final void flood(int sx, int sy, BufferedImage input, BufferedImage map, int v, Box box) {
        ArrayList<Point> points = new ArrayList<Point>();
        points.add(new Point(sx, sy));
        while( !points.isEmpty() ) {
            Point point = points.remove(points.size() - 1);
            int x = point.x;
            int y = point.y;
            if( x >= 0 && y >= 0 && x < input.getWidth() && y < input.getHeight() ) {
                int p = input.getRGB(x, y);
                if( (p & 0xFF000000) != 0 ) {
                    int o = map.getRGB(x, y);
                    if( o == 0 ) {
                        box.add(x, y);
                        map.setRGB(x, y, v);
                        points.add(new Point(x + 1, y));
                        points.add(new Point(x - 1, y));
                        points.add(new Point(x, y + 1));
                        points.add(new Point(x, y - 1));
                    }
                }
            }
        }

    }

    public static final void toFiles(File file, ArrayList<File> files) {

        if( file.isDirectory() ) {
            File[] subfiles = file.listFiles();
            for( File subfile : subfiles ) {
                toFiles(subfile, files);
            }
        } else {
            // assume it's a PNG
            files.add(file);
        }

    }

}
