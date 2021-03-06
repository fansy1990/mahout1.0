/**
 * 
 */
package org.apache.mahout.clustering.spectral;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.apache.mahout.math.function.Functions;
import org.apache.mahout.math.hadoop.DistributedRowMatrix;

import com.fz.util.HUtils;

/**
 * @author fansy
 * @date 2015年8月10日
 */
public final class VectorMatrixMultiplicationJob {

	  private VectorMatrixMultiplicationJob() {
	  }

	  /**
	   * Invokes the job.
	   * @param markovPath Path to the markov DRM's sequence files
	   */
	  public static DistributedRowMatrix runJob(Path markovPath, Vector diag, Path outputPath)
	    throws IOException, ClassNotFoundException, InterruptedException {
	    
	    return runJob(markovPath, diag, outputPath, new Path(outputPath, "tmp"));
	  }

	  public static DistributedRowMatrix runJob(Path markovPath, Vector diag, Path outputPath, Path tmpPath)
	    throws IOException, ClassNotFoundException, InterruptedException {

	    // set up the serialization of the diagonal vector
	    Configuration conf = HUtils.getConf();
	    FileSystem fs = FileSystem.get(markovPath.toUri(), conf);
	    markovPath = fs.makeQualified(markovPath);
	    outputPath = fs.makeQualified(outputPath);
	    Path vectorOutputPath = new Path(outputPath.getParent(), "vector");
	    VectorCache.save(new IntWritable(Keys.DIAGONAL_CACHE_INDEX), diag, vectorOutputPath, conf);

	    // set up the job itself
	    Job job = Job.getInstance(conf, "VectorMatrixMultiplication");
	    job.setInputFormatClass(SequenceFileInputFormat.class);
	    job.setOutputKeyClass(IntWritable.class);
	    job.setOutputValueClass(VectorWritable.class);
	    job.setOutputFormatClass(SequenceFileOutputFormat.class);
	    job.setMapperClass(VectorMatrixMultiplicationMapper.class);
	    job.setNumReduceTasks(0);

	    FileInputFormat.addInputPath(job, markovPath);
	    FileOutputFormat.setOutputPath(job, outputPath);

	    job.setJarByClass(VectorMatrixMultiplicationJob.class);

	    boolean succeeded = job.waitForCompletion(true);
	    if (!succeeded) {
	      throw new IllegalStateException("Job failed!");
	    }

	    // build the resulting DRM from the results
	    return new DistributedRowMatrix(outputPath, tmpPath,
	        diag.size(), diag.size());
	  }
	  
	  public static class VectorMatrixMultiplicationMapper
	    extends Mapper<IntWritable, VectorWritable, IntWritable, VectorWritable> {
	    
	    private Vector diagonal;
	    
	    @Override
	    protected void setup(Context context) throws IOException, InterruptedException {
	      // read in the diagonal vector from the distributed cache
	      super.setup(context);
	      Configuration config = context.getConfiguration();
	      diagonal = VectorCache.load(config);
	      if (diagonal == null) {
	        throw new IOException("No vector loaded from cache!");
	      }
	      if (!(diagonal instanceof DenseVector)) {
	        diagonal = new DenseVector(diagonal);
	      }
	    }
	    
	    @Override
	    protected void map(IntWritable key, VectorWritable row, Context ctx) 
	      throws IOException, InterruptedException {
	      
	      for (Vector.Element e : row.get().all()) {
	        double dii = Functions.SQRT.apply(diagonal.get(key.get()));
	        double djj = Functions.SQRT.apply(diagonal.get(e.index()));
	        double mij = e.get();
	        e.set(dii * mij * djj);
	      }
	      ctx.write(key, row);
	    }
	    
	    /**
	     * Performs the setup of the Mapper. Used by unit tests.
	     * @param diag
	     */
	    void setup(Vector diag) {
	      this.diagonal = diag;
	    }
	  }
	}
