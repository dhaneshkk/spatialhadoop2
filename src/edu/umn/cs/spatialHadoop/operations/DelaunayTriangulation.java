/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/
package edu.umn.cs.spatialHadoop.operations;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.util.GenericOptionsParser;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.Point;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.mapreduce.RTreeRecordReader3;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialInputFormat3;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialRecordReader3;
import edu.umn.cs.spatialHadoop.nasa.HDFRecordReader;

/**
 * Computes the Delaunay triangulation for a set of points.
 * @author Ahmed Eldawy
 * 
 * TODO use a pointer array of integers to refer to points to save memory
 *
 */
public class DelaunayTriangulation {
  
  static final Log LOG = LogFactory.getLog(DelaunayTriangulation.class);
  
  /**
   * Stores a triangulation of some points. All points are referenced by indexes
   * in an array and the array is not stored in this class.
   * @author eldawy
   *
   */
  public static class Triangulation {
    /**All edges of the triangulation as an adjacency list*/
    Map<Point, List<Point>> edges = new HashMap<Point, List<Point>>();
    /**The subset of points which are on the convex hull*/
    Point[] convexHull;
    
    public Triangulation(Triangulation L, Triangulation R) {
      // Compute the convex hull of the result
      Point[] bothHulls = new Point[L.convexHull.length + R.convexHull.length];
      this.convexHull = ConvexHull.convexHull(bothHulls);
      
      // Find the base LR-edge (lowest edge of the convex hull that crosses from L to R)
      Point baseL = null, baseR = null;
      for (int i = 0; i < this.convexHull.length; i++) {
        Point p1 = this.convexHull[i];
        Point p2 = i == this.convexHull.length - 1 ? this.convexHull[0] : this.convexHull[i+1];
        if (inArray(L.convexHull, p1) && inArray(R.convexHull, p2)) {
          if (baseL == null || p1.y > baseL.y) {
            baseL = p1;
            baseR = p2;
          }
        } else if (inArray(L.convexHull, p2) && inArray(R.convexHull, p1)) {
          if (baseL == null || p2.y > baseL.y) {
            baseL = p2;
            baseR = p1;
          }
        }
      }
      
      // Trace the base LR edge up to the root
      boolean finished = false;
      do {
        double anglePotential = -1, angleNextPotential = -1;
        Point rPotentialCandidate = null, rNextPotentialCandidate = null;
        for (Point rNeighbor : R.edges.get(baseR)) {
          if (R.edges.containsKey(rNeighbor)) {
            // Check this RR edge
            double cwAngle = calculateCWAngle(baseL, baseR, rNeighbor);
            if (rPotentialCandidate == null || cwAngle < anglePotential) {
              // Found a new potential candidate
              angleNextPotential = anglePotential;
              rNextPotentialCandidate = rPotentialCandidate;
              anglePotential = cwAngle;
              rPotentialCandidate = rNeighbor;
            } else if (rNextPotentialCandidate == null | cwAngle < angleNextPotential) {
              angleNextPotential = cwAngle;
              rNextPotentialCandidate = rNeighbor;
            }
          }
        }
        if (anglePotential >= Math.PI * 2) {
          // Does not qualify as a candidate
          rPotentialCandidate = null;
        } else {
          // Check if the circumcircle of the base edge with the potential
          // candidate contains the next potential candidate
          Point circleCenter = calculateCircumCircleCenter(baseL, baseR, rPotentialCandidate);
        }
      } while (!finished);
      
    }
    
    private static Point calculateCircumCircleCenter(Point pt1, Point pt2, Point pt3) {
      // Calculate the perpendicular bisector of the first two points 
      Point p1 = new Point((pt1.x + pt2.x) / 2, (pt1.y + pt2.y) /2);
      Point v1 = new Point(pt2.y - pt1.y, pt2.x - pt1.x);
      // Calculate the perpendicular bisector of the second two points 
      Point p2 = new Point((pt3.x + pt2.x) / 2, (pt3.y + pt2.y) /2);
      Point v2 = new Point(pt2.y - pt3.y, pt2.x - pt3.x);
      
      // Calculate the intersection of the two new lines
      double a = ((p2.x - p1.x) * v2.y - (p2.y - p1.y) * v2.x) / (v1.x * v2.y - v1.y * v2.x);
      return new Point(p1.x + a * v1.x, p1.y + a * v1.y);
    }

    public Triangulation(Point p1, Point p2) {
      List<Point> neighbors = new Vector<Point>();
      neighbors.add(p2);
      edges.put(p1, neighbors);
      neighbors = new Vector<Point>();
      neighbors.add(p1);
      edges.put(p2, neighbors);
      convexHull = new Point[] {p1, p2};
    }
    
    public Triangulation(Point p1, Point p2, Point p3) {
      List<Point> neighbors = new Vector<Point>();
      neighbors.add(p2); neighbors.add(p3);
      edges.put(p1, neighbors);
      neighbors = new Vector<Point>();
      neighbors.add(p1); neighbors.add(p3);
      edges.put(p2, neighbors);
      neighbors.add(p1); neighbors.add(p2);
      edges.put(p3, neighbors);
      convexHull = new Point[] {p1, p2, p3};
    }
    
    
    private static boolean inArray(Object[] array, Object objectToFind) {
      for (Object objectToCompare : array)
        if (objectToFind == objectToCompare)
          return true;
      return false;
    }
    
    private static double calculateCWAngle(Point p1, Point p2, Point p3) {
      double angle1 = Math.atan2(p1.y - p2.y, p1.x - p2.x);
      double angle2 = Math.atan2(p3.y - p2.y, p3.x - p2.x);
      return angle1 > angle2 ? (angle1 - angle2) : (Math.PI * 2 + (angle1 - angle2));
    }
  }
  
  public static <P extends Point> void delaunayInMemory(P[] points) {
    Triangulation[] triangulations = new Triangulation[points.length / 3 + (points.length % 3 == 0 ? 0 : 1)];
    // Sort all points by X
    Arrays.sort(points);
    
    // Compute the trivial Delaunay triangles of every three consecutive points
    int i, t=0;
    for (i = 0; i < points.length - 4; i += 3) {
      // Compute Delaunay triangulation for three points
      triangulations[t++] =  new Triangulation(points[i], points[i+1], points[i+2]);
    }
    if (points.length - i == 4) {
      // Compute Delaunay triangulation for every two points
       triangulations[t++] = new Triangulation(points[i], points[i+1]);
       triangulations[t++] = new Triangulation(points[i+2], points[i+3]);
    } else if (points.length - i == 3) {
      // Compute for three points
      triangulations[t++] = new Triangulation(points[i], points[i+1], points[i+2]);
    } else if (points.length - i == 2) {
      // Two points, connect with a line
      triangulations[t++] = new Triangulation(points[i], points[i+1]);
    } else {
      throw new RuntimeException("Cannot happen");
    }
    
    // Start the merge process
    while (triangulations.length > 1) {
      // Merge every pair of Deluanay triangulations
      Triangulation[] newTriangulations = new Triangulation[triangulations.length / 2 + (triangulations.length & 1)];
      int t2 = 0;
      int t1;
      for (t1 = 0; t1 < triangulations.length - 1; t1 += 2) {
        Triangulation dt1 = triangulations[i];
        Triangulation dt2 = triangulations[i+1];
        newTriangulations[t2++] = new Triangulation(dt1, dt2);
      }
      if (t1 < triangulations.length)
        newTriangulations[t2++] = triangulations[t1];
      triangulations = newTriangulations;
    }
  }
  
  
  /**
   * Compute the Deluanay triangulation in the local machine
   * @param inPath
   * @param outPath
   * @param params
   * @throws IOException
   * @throws InterruptedException
   */
  public static <P extends Point> void delaunayLocal(Path inPath, Path outPath,
      OperationsParams params) throws IOException, InterruptedException {
    // 1- Split the input path/file to get splits that can be processed
    // independently
    final SpatialInputFormat3<Rectangle, P> inputFormat =
        new SpatialInputFormat3<Rectangle, P>();
    Job job = Job.getInstance(params);
    SpatialInputFormat3.setInputPaths(job, inPath);
    final List<InputSplit> splits = inputFormat.getSplits(job);
    
    // 2- Read all input points in memory
    List<P> points = new Vector<P>();
    for (InputSplit split : splits) {
      FileSplit fsplit = (FileSplit) split;
      final RecordReader<Rectangle, Iterable<P>> reader =
          inputFormat.createRecordReader(fsplit, null);
      if (reader instanceof SpatialRecordReader3) {
        ((SpatialRecordReader3)reader).initialize(fsplit, params);
      } else if (reader instanceof RTreeRecordReader3) {
        ((RTreeRecordReader3)reader).initialize(fsplit, params);
      } else if (reader instanceof HDFRecordReader) {
        ((HDFRecordReader)reader).initialize(fsplit, params);
      } else {
        throw new RuntimeException("Unknown record reader");
      }
      while (reader.nextKeyValue()) {
        Iterable<P> pts = reader.getCurrentValue();
        for (P p : pts) {
          points.add((P) p.clone());
        }
      }
      reader.close();
    }
    
    if (params.getBoolean("dup", true)) {
      // Remove duplicates to ensure correctness
      final float threshold = params.getFloat("threshold", 1E-5f);
      Collections.sort(points, new Comparator<P>() {
        @Override
        public int compare(P p1, P p2) {
          double dx = p1.x - p2.x;
          if (dx < -threshold)
            return -1;
          if (dx > +threshold)
            return 1;
          double dy = p1.y - p2.y;
          if (dy < -threshold)
            return -1;
          if (dy > +threshold)
            return 1;
          return 0;
        }
      });
      
      int i = 1;
      while (i < points.size()) {
        P p1 = points.get(i-1);
        P p2 = points.get(i);
        double dx = Math.abs(p1.x - p2.x);
        double dy = Math.abs(p1.y - p2.y);
        if (dx < threshold && dy < threshold)
          points.remove(i);
        else
          i++;
      }
    }
    
    LOG.info("Read "+points.size()+" points and computing DT");
    delaunayInMemory(points.toArray((P[])Array.newInstance(points.get(0).getClass(), points.size())));
  }

  private static void printUsage() {
    // TODO Auto-generated method stub
    
  }

  /**
   * @param args
   * @throws IOException 
   * @throws InterruptedException 
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    Point p1 = new Point(0,0);
    Point p2 = new Point(5,0);
    Point p3 = new Point(8,5);
    Point center = Triangulation.calculateCircumCircleCenter(p1, p2, p3);
    System.out.println(center);
    System.exit(0);
    
    
    GenericOptionsParser parser = new GenericOptionsParser(args);
    OperationsParams params = new OperationsParams(parser);
    
    Path[] paths = params.getPaths();
    if (paths.length == 0)
    {
      printUsage();
      System.exit(1);
    }
    Path inFile = paths[0];
    Path outFile = paths.length > 1 ? paths[1] : null;
    
    long t1 = System.currentTimeMillis();
    if (OperationsParams.isLocal(params, inFile)) {
      delaunayLocal(inFile, outFile, params);
    } else {
      //voronoiMapReduce(inFile, outFile, params);
    }
    long t2 = System.currentTimeMillis();
    System.out.println("Total time: " + (t2 - t1) + " millis");
  }



}
