// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.cscore.CvSink;
import edu.wpi.cscore.CvSource;
import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.EntryListenerFlags;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
       "switched cameras": [
           {
               "name": <virtual camera name>
               "key": <network table key used for selection>
               // if NT value is a string, it's treated as a name
               // if NT value is a double, it's treated as an integer index
           }
       ]
   }
 */

public final class Main {
  private static String configFile = "/boot/frc.json";

  @SuppressWarnings("MemberName")
  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  @SuppressWarnings("MemberName")
  public static class SwitchedCameraConfig {
    public String name;
    public String key;
  };

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();
  public static List<SwitchedCameraConfig> switchedCameraConfigs = new ArrayList<>();
  public static List<VideoSource> cameras = new ArrayList<>();
  private static int centerX = 0;
  private static VisionThread visionThread;
  private static double loopCtr;
  private static int numberImages;
  private static double loopCtr1;
  private static NetworkTableEntry threadCounterEntry;
  private static NetworkTableEntry centerXEntry;
  private static NetworkTableEntry driveCorrectionEntry;
  private static NetworkTableEntry numberImagesEntry;
  private static NetworkTableEntry rectWidthEntry;
  private static NetworkTableEntry rectHeightEntry;
  private static NetworkTableEntry threadCounter1Entry;
  private static NetworkTableEntry numberEntry;
  private static int imageWidth;
  private static int imageHeight;
  private static int rectWidth;
  private static int rectHeight;
  private static int x;
  private static int y;
  public static Scalar yellow = new Scalar(60, 100, 100, 0);
  public static Scalar red = new Scalar(0, 0, 255, 0);
  public static Scalar green = new Scalar(0, 255, 0, 0);
  public static Scalar blue = new Scalar(255, 0, 0, 0);

  private static int driveCorrection;
  private static final Object imgLock = new Object();
  private static int targetOffsetMultiplier = 5;
  private static Thread addTargeting;

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Keep target box in screen
   * 
   */
  public static int ensure(int value, int min, int max) {
    return Math.max(min, Math.min(value, max));
  }

  public static int getXOffsetTarget() {
    return x + (rectWidth * targetOffsetMultiplier) / 10;
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read single switched camera configuration.
   */
  public static boolean readSwitchedCameraConfig(JsonObject config) {
    SwitchedCameraConfig cam = new SwitchedCameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read switched camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement keyElement = config.get("key");
    if (keyElement == null) {
      parseError("switched camera '" + cam.name + "': could not read key");
      return false;
    }
    cam.key = keyElement.getAsString();

    switchedCameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    if (obj.has("switched cameras")) {
      JsonArray switchedCameras = obj.get("switched cameras").getAsJsonArray();
      for (JsonElement camera : switchedCameras) {
        if (!readSwitchedCameraConfig(camera.getAsJsonObject())) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    CameraServer inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = inst.startAutomaticCapture(camera);

    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * Start running the switched camera.
   */
  public static MjpegServer startSwitchedCamera(SwitchedCameraConfig config) {
    System.out.println("Starting switched camera '" + config.name + "' on " + config.key);
    MjpegServer server = CameraServer.getInstance().addSwitchedCamera(config.name);

    NetworkTableInstance.getDefault().getEntry(config.key).addListener(event -> {
      if (event.value.isDouble()) {
        int i = (int) event.value.getDouble();
        if (i >= 0 && i < cameras.size()) {
          server.setSource(cameras.get(i));
        }
      } else if (event.value.isString()) {
        String str = event.value.getString();
        for (int i = 0; i < cameraConfigs.size(); i++) {
          if (str.equals(cameraConfigs.get(i).name)) {
            server.setSource(cameras.get(i));
            break;
          }
        }
      }
    }, EntryListenerFlags.kImmediate | EntryListenerFlags.kNew | EntryListenerFlags.kUpdate);

    return server;
  }

  /**
   * Example pipeline.
   */
  public static class MyPipeline implements VisionPipeline {
    public int val;

    @Override
    public void process(Mat mat) {
      val += 1;
    }
  }

  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
      ntinst.startDSClient();
      NetworkTable targetData = ntinst.getTable("targetData");
      threadCounterEntry = targetData.getEntry("threadCount");
      centerXEntry = targetData.getEntry("centerX");
      driveCorrectionEntry = targetData.getEntry("drCorr");
      numberImagesEntry = targetData.getEntry("numberImages");
      rectWidthEntry = targetData.getEntry("rectWidth");
      rectHeightEntry = targetData.getEntry("rectHeight");
      numberEntry = targetData.getEntry("value");
      threadCounter1Entry = targetData.getEntry("tgtTChnt");
      numberEntry.setDouble(99);
      threadCounter1Entry.setDouble(9999);
    }

    // start cameras
    for (CameraConfig config : cameraConfigs) {
      cameras.add(startCamera(config));
    }
    // start switched camera
    for (SwitchedCameraConfig config : switchedCameraConfigs) {
      startSwitchedCamera(config);
    }

    // start image processing on camera 0 if present

    if (cameras.size() >= 1) {
      visionThread = new VisionThread(cameras.get(0), new GripPipeline(), pipeline -> {
        imageWidth = cameras.get(0).getVideoMode().width;
        imageHeight = cameras.get(0).getVideoMode().height;
        loopCtr++;
        double maxSize = -1;
        int maxSizeIndex = -1;
        numberImages = pipeline.convexHullsOutput.size();
        if (numberImages < 1) {
          numberImages = 0;
          centerX = 0;
          driveCorrection = 0;
          x = 0;
          y = 0;
          rectHeight = 0;
          rectWidth = 0;
        } else {
          synchronized (imgLock) {
            for (int i = 0; i < pipeline.convexHullsOutput.size(); i++) {
              if (Imgproc.contourArea(pipeline.convexHullsOutput.get(i)) > maxSize) {
                maxSize = Imgproc.contourArea(pipeline.convexHullsOutput.get(i));
                maxSizeIndex = i;
              }

              Rect r = Imgproc.boundingRect(pipeline.convexHullsOutput().get(maxSizeIndex));
              rectHeight = r.height;
              rectWidth = r.width;
              x = r.x;
              y = r.y;
              centerX = r.x + (r.width / 2);
              driveCorrection = centerX - (imageWidth / 2);
            }
          }
        }
        rectWidthEntry.setDouble(rectWidth);
        rectHeightEntry.setDouble(rectHeight);
        driveCorrectionEntry.setDouble(driveCorrection);
        centerXEntry.setDouble(centerX);
        numberImagesEntry.setDouble(numberImages);
        threadCounterEntry.setDouble(loopCtr);
      });

      visionThread.start();

      addTargeting = new Thread(() -> {
        CvSink cvSink = CameraServer.getInstance().getVideo(cameras.get(0));
        // // Setup a CvSource. This will send images back to the Dashboard
        CvSource outputStream = CameraServer.getInstance().putVideo("Target", imageWidth, imageHeight);
        // // Mats are very memory expensive. Lets reuse this Mat.

        Mat mat = new Mat();

        // This cannot be 'true'. The program will never exit if it is. This
        // lets the robot stop this thread when restarting robot code or
        // deploying.
        while (!Thread.interrupted()) {
          // Tell the CvSink to grab a frame from the camera and put it
          // in the source mat. If there is an error notify the output.
          if (cvSink.grabFrame(mat) == 0) {
            // Send the output the error.
            outputStream.notifyError(cvSink.getError());
            // skip the rest of the current iteration
            continue;
          }
          loopCtr1++;
          if (loopCtr1 > 1000)
            loopCtr1 = 0;
          // Draw on the image
          if (numberImages > 0) {
            // image vertical center line
            Imgproc.line(mat, new Point(imageWidth / 2, 0), new Point(imageWidth / 2, imageHeight - 1), green, 2);

            // target line on cube(s)
            Imgproc.line(mat, new Point(getXOffsetTarget(), 0), new Point(getXOffsetTarget(), imageHeight - 1), blue,
                2);

            int x1Val = 0;
            int y1Val = 0;
            int x2Val = 0;
            int y2Val = 0;

            // target box 1
            // make sure not to write outside screen
            x1Val = ensure(x, 0, imageWidth - 1);
            y1Val = ensure(y, 0, imageHeight - 1);
            x2Val = ensure(x + rectWidth, x1Val + 1, imageWidth - 1);
            y2Val = ensure(y + rectHeight, y1Val + 1, imageHeight - 1);

            Imgproc.rectangle(mat, new Point(x1Val, y1Val), new Point(x2Val, y2Val), red, 2);
            // target center

          }
          // Give the output stream a new image to display
          outputStream.putFrame(mat);
          threadCounter1Entry.setDouble(loopCtr1);
        }
      });

      addTargeting.start();
    }

    // loop forever
    for (;;)

    {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
  }
}
