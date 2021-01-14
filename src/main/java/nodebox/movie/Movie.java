package nodebox.movie;

import nodebox.Log;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.Random;

public class Movie {

    private static final File FFMPEG_BINARY;
    private static final String TEMPORARY_FILE_PREFIX = "sme";

    public static final ArrayList<VideoFormat> VIDEO_FORMATS;
    public static final VideoFormat DEFAULT_FORMAT = MP4VideoFormat.HighFormat;

    static {
        String osName = System.getProperty("os.name").split("\\s")[0];
        // If we provide a binary for this system, use it. Otherwise, see if a default "ffmpeg"  binary exists.
        String binaryName = "ffmpeg";
        if (osName.equals("Windows"))
            binaryName = "ffmpeg.exe";
        File packagedBinary = nodebox.util.FileUtils.getApplicationFile(String.format("bin/%s", binaryName));
        if (packagedBinary.exists()) {
            FFMPEG_BINARY = packagedBinary;
        } else {
            Log.warn(String.format("Could not find packaged ffmpeg %s", packagedBinary));

            File systemBinary = new File("/usr/bin/ffmpeg");
            if (systemBinary.exists()) {
                FFMPEG_BINARY = systemBinary;
            } else {
                systemBinary = new File("/usr/local/bin/ffmpeg");
                if (systemBinary.exists()) {
                    FFMPEG_BINARY = systemBinary;
                } else {
                    FFMPEG_BINARY = new File("ffmpeg");
                }
            }
        }
        VIDEO_FORMATS = new ArrayList<VideoFormat>();
        VIDEO_FORMATS.add(MP4VideoFormat.LosslessFormat);
        VIDEO_FORMATS.add(MP4VideoFormat.HighFormat);
        VIDEO_FORMATS.add(MP4VideoFormat.MediumFormat);
        VIDEO_FORMATS.add(MP4VideoFormat.LowFormat);
    }

    private String movieFilename;
    private VideoFormat videoFormat;
    private int width, height;
    private boolean verbose;
    private int frameCount = 0;
    private String temporaryFileTemplate;

    public Movie(String movieFilename, VideoFormat format, int width, int height) {
        this(movieFilename, format, width, height, false);
    }

    public Movie(String movieFilename, VideoFormat format, int width, int height, boolean verbose) {
        this.movieFilename = movieFilename;
        this.videoFormat = format;
        this.width = width;
        this.height = height;
        this.verbose = verbose;
        // Generate the prefix for a temporary file.
        // We generate a temporary file, then use that as the prefix for our own files.
        try {
            File tempFile = File.createTempFile(TEMPORARY_FILE_PREFIX, "");
            temporaryFileTemplate = tempFile.getPath() + "-%05d.png";
            tempFile.delete();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public String getMovieFilename() {
        return movieFilename;
    }

    public File getMovieFile() {
        return new File(movieFilename);
    }

    public File temporaryFileForFrame(int frame) {
        return new File(String.format(temporaryFileTemplate, frame));
    }

    /**
     * Add the image to the movie.
     * <p/>
     * The image size needs to be exactly the same size as the movie.
     * <p/>
     * Internally, this saves the image to a temporary image and increases the frame counter. Temporary images are
     * cleaned up when calling save() or if an error occurs.
     *
     * @param img the image to add to the movie.
     */
    public void addFrame(RenderedImage img) {
        if (img.getWidth() != width || img.getHeight() != height) {
            throw new RuntimeException("Given image does not have the same size as the movie.");
        }
        try {
            ImageIO.write(img, "png", temporaryFileForFrame(frameCount));
            frameCount++;
        } catch (IOException e) {
            cleanupAndThrowException(e);
        }
    }

    public void save() {
        save(new StringWriter());
    }

    /**
     * Finishes the export and save the movie.
     */
    public void save(StringWriter sw) {
        Log.info(String.format("Exporting movie %s with inputs %s (ffmpeg %s)", movieFilename, temporaryFileTemplate, FFMPEG_BINARY.getAbsolutePath()));
        PrintWriter out = new PrintWriter(sw, true);
        ArrayList<String> commandList = new ArrayList<String>();
        commandList.add(FFMPEG_BINARY.getAbsolutePath());
        commandList.add("-hide_banner"); // Hide the FFMPEG compilation banner
        commandList.add("-y"); // Overwrite target if exists
        commandList.add("-i");
        commandList.add(temporaryFileTemplate); // Input images
        commandList.addAll(videoFormat.getArgumentList(this)); // Video format specific arguments
        commandList.add(movieFilename); // Target file name

        ProcessBuilder pb = new ProcessBuilder(commandList);
        if (verbose) {
            for (String cmd : pb.command()) {
                System.out.print(cmd + " ");
            }
            System.out.println();
        }
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
            p.getOutputStream().close();
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = in.readLine()) != null)
                out.println(line);
            p.waitFor();
            if (verbose) {
                System.out.println(sw.toString());
            }
        } catch (IOException | InterruptedException e) {
            cleanupAndThrowException(e);
        }
        cleanup();
    }

    /**
     * Cleans up the temporary images.
     * <p/>
     * Normally you should not call this method as it is called automatically when running finish() or if an error
     * occurred. The only reason to call it is if you have added images and then decide you don't want to generate
     * a movie. In that case, instead of calling finish(), call cleanup().
     *
     * @see #save()
     */
    public void cleanup() {
        for (int i = 0; i < frameCount; i++) {
            temporaryFileForFrame(i).delete();
        }
    }

    private void cleanupAndThrowException(Throwable t) {
        cleanup();
        Log.error("Error while exporting movie", t);
        throw new RuntimeException(t);
    }

    public static void main(String[] args) {
        int width = 640;
        int height = 480;
        // Create a new movie.
        Movie movie = new Movie("test.mp4", MP4VideoFormat.LosslessFormat, width, height);
        movie.setVerbose(true);
        /// Initialize an image to draw on.
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = (Graphics2D) img.getGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int frame = 0; frame < 20; frame++) {
            System.out.println("frame = " + frame);
            // Clear the canvas and draw some simple circles.
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            Random r = new Random(0);
            for (int j = 0; j < 100; j++) {
                g.setColor(new Color(r.nextInt(255), 255, r.nextInt(255)));
                g.fillOval(r.nextInt(width) + frame, r.nextInt(height) + frame, 30, 30);
            }
            // Add the image to the movie.
            movie.addFrame(img);
        }
        // Export the movie.
        movie.save();
    }
}

