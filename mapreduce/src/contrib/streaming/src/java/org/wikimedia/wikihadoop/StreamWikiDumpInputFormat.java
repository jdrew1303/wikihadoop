/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wikimedia.wikihadoop;

import java.io.*;
import java.util.*;

import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;

import org.apache.hadoop.io.compress.*;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.io.Text;

import org.apache.hadoop.mapred.*;
import org.apache.hadoop.io.compress.*;

/** A InputFormat implementation that splits a Wikimedia Dump File into page fragments, and emits them as input records.
 *  A key contains a pair of continued revisions and the page element that the revisions belong to in the following format.
 *  &lt;page&gt;
 *    &lt;title&gt;....&lt;/title&gt;
 *    &lt;id&gt;....&lt;/id&gt;
 *    &lt;revision&gt;
 *      ....
 *    &lt;/revision&gt;
 *    &lt;revision&gt;
 *      ....
 *    &lt;/revision&gt;
 *  &lt;/page&gt;
 */
public class StreamWikiDumpInputFormat extends KeyValueTextInputFormat {

  private CompressionCodecFactory compressionCodecs = null;
   
  public void configure(JobConf conf) {
    this.compressionCodecs = new CompressionCodecFactory(conf);
  }
  
  protected boolean isSplitable(FileSystem fs, Path file) {
    final CompressionCodec codec = compressionCodecs.getCodec(file);
    if (null == codec) {
      return true;
    }
    return codec instanceof SplittableCompressionCodec;
  }

  /** 
   * Generate the list of files and make them into FileSplits.
   * @param job the job context
   * @throws IOException
   */
  @Override public InputSplit[] getSplits(JobConf job, int n) throws IOException {
    System.err.println("StreamWikiDumpInputFormat.getSplits job=" + job + " n=" + n);
    InputSplit[] oldSplits = super.getSplits(job, n);
    List<InputSplit> splits = new ArrayList<InputSplit>();
    for ( InputSplit x: oldSplits ) {
      splits.add(x);//!
    }

    InputSplit[] array = new InputSplit[splits.size()];
    splits.toArray(array);
    return array;
  }

  public RecordReader<Text, Text> getRecordReader(final InputSplit genericSplit,
                                                  JobConf job, Reporter reporter) throws IOException {
    // handling non-standard record reader (likely StreamXmlRecordReader) 
    FileSplit split = (FileSplit) genericSplit;
    LOG.info("getRecordReader start.....split=" + split);
    reporter.setStatus(split.toString());

    // Open the file and seek to the start of the split
    FileSystem fs = split.getPath().getFileSystem(job);
    return new MyRecordReader(split, reporter, job, fs);
  }

  private class MyRecordReader implements RecordReader<Text,Text> {
    
    public MyRecordReader(FileSplit split, Reporter reporter,
                          JobConf job, FileSystem fs) throws IOException {
      this.revisionBeginPattern = "<revision";
      this.revisionEndPattern   = "</revision>";
      this.pageHeader   = new DataOutputBuffer();
      this.prevRevision = new DataOutputBuffer();
      this.pageFooter = getBuffer("\n</page>\n".getBytes("UTF-8"));
      this.revHeader  = getBuffer((//"<this-is-second-revision-by-debugger/>"+//!
                                   this.revisionBeginPattern).getBytes("UTF-8"));
      this.firstDummyRevision = getBuffer(" beginningofpage=\"true\"></revision>\n".getBytes("UTF-8"));
      this.bufInRev = new DataOutputBuffer();
      this.bufBeforeRev = new DataOutputBuffer();
      
      final CompressionCodec codec = compressionCodecs.getCodec(split.getPath());

      this.fs = fs;
      this.split = split;
      this.codec = codec;
      if (codec != null) {
        Decompressor decompressor = CodecPool.getDecompressor(codec);
        if (codec instanceof SplittableCompressionCodec) {
          SplittableCompressionCodec scodec = (SplittableCompressionCodec)codec;
          final SplitCompressionInputStream cIn = scodec.createInputStream
            (fs.open(split.getPath()), decompressor, split.getStart(), split.getStart() + split.getLength(),
             SplittableCompressionCodec.READ_MODE.BYBLOCK);
          //System.err.println("init decompressor=" + decompressor + " codec=" + scodec);//!

          this.start = cIn.getAdjustedStart();
          this.end   = cIn.getAdjustedEnd();
          this.fileBytes = new BytesRead();
        } else {
          this.start = split.getStart();
          this.end   = split.getStart() + split.getLength();
          this.fileBytes  = new BytesRead();
        }
      } else {
        this.start = split.getStart();
        this.end   = split.getStart() + split.getLength();
        this.fileBytes    = new BytesRead();
      }
      this.reporter = reporter;
      this.pageBytes = new ArrayList<Long>();
      init();
    }
    
    
    public void init() throws IOException {
      SplittableCompressionCodec scodec = null;
      if ( this.codec != null && this.codec instanceof SplittableCompressionCodec ) {
        scodec = (SplittableCompressionCodec)codec;
      }
      this.pageHeader.reset();
      allWrite(this.prevRevision, this.firstDummyRevision);
      this.currentPageNum = -1;
      this.pageBytes.clear();
      this.pageBytes.addAll(getPageBytes(scodec, this.fs, this.split, this.reporter));

      if ( this.codec != null ) {
        Decompressor decompressor = CodecPool.getDecompressor(codec);
        if ( this.codec instanceof SplittableCompressionCodec ) {
          scodec = (SplittableCompressionCodec)codec;
          SplitCompressionInputStream cIn = scodec.createInputStream
            (fs.open(split.getPath()), decompressor, this.split.getStart(), this.split.getStart() + this.split.getLength(),
             SplittableCompressionCodec.READ_MODE.BYBLOCK);
          this.istream = new SeekableInputStream(cIn);
        } else {
          FSDataInputStream in = fs.open(split.getPath());
          this.istream = new SeekableInputStream(codec.createInputStream(in, decompressor), in);
        }
      } else {
        this.istream = new SeekableInputStream(fs.open(split.getPath()));
        this.istream.seek(this.start);
      }
      this.seekNextRecordBoundary();      
    }
    
    @Override public Text createKey() {
      return new Text();
    }
    
    @Override public Text createValue() {
      return new Text();
    }
    
    @Override public void close() throws IOException {
      this.istream.close();
    }
    
    @Override public float getProgress() throws IOException {
      float rate = 0.0f;
      if (this.end == this.start) {
        rate = 1.0f;
      } else {
        rate = ((float)(this.getPosition() - this.start)) / ((float)(this.end - this.start));
      }
      return rate;
    }
    
    @Override public long getPos() throws IOException {
      return this.istream.getPos();
    }
    
    public long getPosition() throws IOException {
      return this.istream.getPos();
    }
    
    public synchronized long getReadBytes() throws IOException {
        return this.fileBytes.getByteCount();
      }
    
    @Override synchronized public boolean next(Text key, Text value) throws IOException {
      //System.err.println("StreamWikiDumpInputFormat: split=" + split + " start=" + this.start + " end=" + this.end + " pos=" + this.getPosition() + " read=" + this.getReadBytes() + " pageBytes=" + pageBytes);//!
      LOG.info("StreamWikiDumpInputFormat: split=" + split + " start=" + this.start + " end=" + this.end + " pos=" + this.getPosition() + " pageBytes=" + pageBytes);
      
      if ( this.pageBytes.size() == 0 ) {
        this.init();
        if ( this.pageBytes.size() == 0 ) {
          return false;
        }
        reporter.incrCounter(WikiDumpCounters.WRITTEN_REVISIONS, 0);
        reporter.incrCounter(WikiDumpCounters.WRITTEN_PAGES, 0);
      }

      //System.err.println("0.2 check pos="+this.getPosition() + " end="+this.end);//!
      if (this.currentPageNum >= this.pageBytes.size() / 2  ||  this.getReadBytes() >= this.tailPageEnd()) {
        return false;
      }

      //System.err.println("2 move to rev from: " + this.getReadBytes());//!
      if (!readUntilMatch(this.revisionBeginPattern, this.bufBeforeRev)  ||  this.getReadBytes() >= this.tailPageEnd()) { // move to the beginning of the next revision
        return false;
      }
      //System.err.println("2.1 move to rev to: " + this.getReadBytes());//!
        
      //System.err.println("4.5 check if exceed: " + this.getReadBytes() + " " + nextPageBegin() + " " + prevPageEnd());//!
      if ( this.getReadBytes() >= this.nextPageBegin() ) {
        // int off = (int)(this.nextPageBegin() - this.prevPageEnd());
        int off = findIndex(pageBeginPattern.getBytes("UTF-8"), this.bufBeforeRev);
        if ( off >= 0 ) {
          offsetWrite(this.pageHeader, off, this.bufBeforeRev);
          allWrite(this.prevRevision, this.firstDummyRevision);
          this.currentPageNum++;
          reporter.incrCounter(WikiDumpCounters.WRITTEN_PAGES, 1);
          //System.err.println("4.6 exceed");//!
        }
      }
      
      //System.err.println("4 read rev from: " + this.getReadBytes());//!
      if (!readUntilMatch(this.revisionEndPattern, this.bufInRev)) { // store the revision
        //System.err.println("no revision end" + this.getReadBytes() + " " + this.end);//!
          LOG.info("no revision end");
          return false;
        }
      //System.err.println("4.1 read rev to: " + this.getReadBytes());//!
        
      //System.err.println("5 read rev pos " + this.getReadBytes());//!
      byte[] record = writeInSequence(new DataOutputBuffer[]{ this.pageHeader,
                                                              this.prevRevision,
                                                              this.revHeader,
                                                              this.bufInRev,
                                                              this.pageFooter});
      key.set(record);
      //System.out.print(key.toString());//!
      value.set("");
      this.reporter.setStatus("StreamWikiDumpInputFormat: write new record pos=" + this.istream.getPos() + " bytes=" + this.getReadBytes());
      System.err.println("StreamWikiDumpInputFormat: write new record pos=" + this.istream.getPos() + " bytes=" + this.getReadBytes() + " next=" + this.nextPageBegin() + " prev=" + this.prevPageEnd());//!
      reporter.incrCounter(WikiDumpCounters.WRITTEN_REVISIONS, 1);
      
      allWrite(this.prevRevision, this.bufInRev);
      
      return true;
    }
    
    public synchronized void seekNextRecordBoundary() throws IOException {
      if ( this.getReadBytes() < this.nextPageBegin() ) {
        long len = this.nextPageBegin() - this.getReadBytes();
        this.fileBytes.proceed(len);
        this.istream.skip(len);
      }
    }
    private synchronized boolean readUntilMatch(String textPat, DataOutputBuffer outBufOrNull) throws IOException {
      if ( outBufOrNull != null )
        outBufOrNull.reset();
      return readUntilMatch_(this.istream, this.istream, this.fileBytes, this.end, textPat, outBufOrNull);
    }
    private long tailPageEnd() {
      if ( this.pageBytes.size() > 0 ) {
        return this.pageBytes.get(this.pageBytes.size() - 1);
      } else {
        return 0;
      }
    }
    private long nextPageBegin() {
      if ( (this.currentPageNum + 1) * 2 < this.pageBytes.size() - 1 ) {
        return this.pageBytes.get((this.currentPageNum + 1) * 2);
      } else {
        return this.end;
      }
    }
    private long prevPageEnd() {
      if ( this.currentPageNum == 0 ) {
        if ( this.pageBytes.size() > 0 ) {
          return this.pageBytes.get(0);
        } else {
          return this.start;
        }
      } else if ( this.currentPageNum * 2 - 1 <= this.pageBytes.size() - 1 ) {
        return this.pageBytes.get(this.currentPageNum * 2 - 1);
      } else {
        return this.end;
      }
    }  
  
    private int currentPageNum;
    private final long start;
    private final long end;
    private final List<Long> pageBytes;
    private final BytesRead fileBytes;
    private SeekableInputStream  istream;
    private final String revisionBeginPattern;
    private final String revisionEndPattern;
    private final DataOutputBuffer pageHeader;
    private final DataOutputBuffer revHeader;
    private final DataOutputBuffer prevRevision;
    private final DataOutputBuffer pageFooter;
    private final DataOutputBuffer firstDummyRevision;
    private final DataOutputBuffer bufInRev;
    private final DataOutputBuffer bufBeforeRev;
    private final FileSystem fs;
    private final FileSplit split;
    private final CompressionCodec codec;
    private final Reporter reporter;
  }

  private static byte[] writeInSequence(DataOutputBuffer[] array) {
    int size = 0;
    for (DataOutputBuffer buf: array) {
      size += buf.getLength();
    }
    byte[] dest = new byte[size];
    int n = 0;
    for (DataOutputBuffer buf: array) {
      System.arraycopy(buf.getData(), 0, dest, n, buf.getLength());
      n += buf.getLength();
    }
    return dest;
  }
  
  private static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[1024 * 1024];
    int len = 0;
    while ( (len = in.read(buffer)) > 0 ) {
      out.write(buffer, 0, len);
    }
    out.flush();
  }

  private static DataOutputBuffer getBuffer(byte[] bytes) throws IOException {
    DataOutputBuffer ret = new DataOutputBuffer(bytes.length);
    ret.write(bytes);
    return ret;
  }

  private static boolean readUntilMatch_(InputStream in, Seekable pos, BytesRead bytes, long end, String textPat, DataOutputBuffer outBufOrNull) throws IOException {
    byte[] match = textPat.getBytes("UTF-8");
    int i = 0;
    while (true) {
      int b = in.read();
      // end of file:
      if (b == -1) {
        //System.err.println("eof 1");
        return false;
      }
      bytes.proceed(1);         //! TODO: count up later in batch
      // save to buffer:
      if (outBufOrNull != null)
        outBufOrNull.write(b);
      
      // check if we're matching:
      if (b == match[i]) {
        i++;
        if (i >= match.length)
          return true;
      } else {
        i = 0;
        //pos.proceed(i + 1);
      }
      // see if we've passed the stop point:
      if (outBufOrNull == null && i == 0 && pos.getPos() >= end) {
        System.err.println("eof 2");
        return false;
      }
    }
  }

  private static List<Long> getPageBytes(SplittableCompressionCodec scodec, FileSystem fs, FileSplit split,  Reporter reporter) throws IOException {
    if ( scodec == null ) {
      FSDataInputStream din = fs.open(split.getPath());
      try {
        din.seek(split.getStart());
        return getPageBytes_(din, din, reporter, split.getStart(), split.getStart() + split.getLength());
      } finally {
        din.close();
      }
    } else {
      Decompressor decompressor = CodecPool.getDecompressor(scodec);
      //System.err.println("getp decompressor=" + decompressor + " codec=" + scodec);//!

      SplitCompressionInputStream cin = scodec.createInputStream
        (fs.open(split.getPath()), decompressor, split.getStart(), split.getStart() + split.getLength(),
         SplittableCompressionCodec.READ_MODE.BYBLOCK);
      try {
        return getPageBytes_(cin, cin, reporter, cin.getAdjustedStart(), cin.getAdjustedEnd());
      } finally {
        cin.close();
      }
    }
  }

  private static List<Long> getPageBytes_(InputStream in, Seekable pos, Reporter reporter, long start, long end) throws IOException {
    BytesRead bytes = new BytesRead();
    List<Long> ret = new ArrayList<Long>();
    while ( true ) {
      if ( pos.getPos() >= end  || !readUntilMatch_(in, pos, bytes, end, pageBeginPattern, null) ) {
        break;
      }
      ret.add(bytes.getByteCount() - pageBeginPattern.getBytes("UTF-8").length);
      if ( pos.getPos() >= end  || !readUntilMatch_(in, pos, bytes, end, pageEndPattern, null) ) {
        System.err.println("could not find "+pageEndPattern+", page over a split?  pos=" + pos.getPos() + " bytes=" + bytes.getByteCount());
        ret.remove(ret.size() - 1);
        break;
      }
      ret.add(bytes.getByteCount() - pageEndPattern.getBytes("UTF-8").length);
      reporter.setStatus(String.format("StreamWikiDumpInputFormat: find page %6d start=%d pos=%d end=%d bytes=%d", ret.size(), start, pos.getPos(), end, bytes.getByteCount()));
      reporter.incrCounter(WikiDumpCounters.FOUND_PAGES, 1);
    }
    //System.err.println("getPageBytes " + ret);//!
    return ret;
  }

  private static void offsetWrite(DataOutputBuffer to, int fromOffset, DataOutputBuffer from) throws IOException {
    if ( from.getLength() <= fromOffset || fromOffset < 0 ) {
      throw new IllegalArgumentException(String.format("invalid offset: offset=%d length=%d", fromOffset, from.getLength()));
    }
    byte[] bytes = new byte[from.getLength() - fromOffset];
    System.arraycopy(from.getData(), fromOffset, bytes, 0, bytes.length);
    to.reset();
    to.write(bytes);
  }
  private static void allWrite(DataOutputBuffer to, DataOutputBuffer from) throws IOException {
    offsetWrite(to, 0, from);
  }

  private static int findIndex(byte[] match, DataOutputBuffer from_) throws IOException {
    // TODO: faster string pattern match (KMP etc)
    int m = 0;
    int i;
    byte[] from = from_.getData();
    for ( i = 0; i < from_.getLength(); ++i ) {
      if ( from[i] == match[m] ) {
        ++m;
      } else {
        m = 0;
      }
      if ( m == match.length ) {
        return i - m + 1;
      }
    }
    // throw new IllegalArgumentException("pattern not found: " + new String(match) + " in " + new String(from));
    System.err.println("pattern not found: " + new String(match) + " in " + new String(from));//!
    return -1;
  }

  private static class BytesRead {
    long p = 0;
    public long getByteCount() {
      return p;
    }
    public void reset() {
      this.p = 0;
    }
    public void proceed(long n) {
      this.p += n;
    }
  }

  private static enum WikiDumpCounters {
    FOUND_PAGES, WRITTEN_REVISIONS, WRITTEN_PAGES
  }

  private static class SeekableInputStream extends FilterInputStream implements Seekable {
    Seekable seek;
    public SeekableInputStream(FSDataInputStream in) {
      super(in);
      this.seek = in;
    }
    public SeekableInputStream(SplitCompressionInputStream cin) {
      super(cin);
      this.seek = cin;
    }
    public SeekableInputStream(CompressionInputStream cin, FSDataInputStream in) {
      super(cin);
      this.seek = in;
    }
    public long getPos() throws IOException { return this.seek.getPos(); }
    public void seek(long pos) throws IOException { this.seek.seek(pos); } 
    public boolean seekToNewSource(long targetPos) throws IOException { return this.seek.seekToNewSource(targetPos); }
    @Override public String toString() {
      return this.in.toString();
    }
  }

  private static final String pageBeginPattern = "<page>";
  private static final String pageEndPattern   = "</page>";
}
