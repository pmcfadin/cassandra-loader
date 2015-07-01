/*
 * Copyright 2015 Brian Hess
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.loader;

import com.datastax.loader.parser.BooleanParser;
import com.datastax.loader.futures.FutureManager;
import com.datastax.loader.futures.PrintingFutureSet;

import java.lang.System;
import java.lang.String;
import java.lang.StringBuilder;
import java.lang.Integer;
import java.lang.Runtime;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Arrays;
import java.util.Locale;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.io.PrintStream;
import java.text.ParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Metrics;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.ProtocolVersion;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.ProtocolOptions;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;

import com.codahale.metrics.Timer;

public class CqlDelimLoad {
    private String version = "0.0.12";
    private String host = null;
    private int port = 9042;
    private String username = null;
    private String password = null;
    private Cluster cluster = null;
    private Session session = null;
    private ConsistencyLevel consistencyLevel = ConsistencyLevel.LOCAL_ONE;
    private int numFutures = 1000;
    private int inNumFutures = -1;
    private int queryTimeout = 2;
    private long maxInsertErrors = 10;
    private int numRetries = 1;
    private double rate = 50000.0;
    private long progressRate = 100000;
    private RateLimiter rateLimiter = null;
    private String rateFile = null;
    private PrintStream rateStream = null;

    private String cqlSchema = null;

    private long maxErrors = 10;
    private long skipRows = 0;
    private String skipCols = null;
    
    private long maxRows = -1;
    private String badDir = ".";
    private String filename = null;
    public static String STDIN = "stdin";
    public static String STDERR = "stderr";
    private String successDir = null;
    private String failureDir = null;

    private Locale locale = null;
    private BooleanParser.BoolStyle boolStyle = null;
    private String dateFormatString = null;
    private String nullString = null;
    private String delimiter = null;

    private int numThreads = Runtime.getRuntime().availableProcessors();
    private int batchSize = 1;

    private String usage() {
	StringBuilder usage = new StringBuilder("version: ").append(version).append("\n");
	usage.append("Usage: -f <filename> -host <ipaddress> -schema <schema> [OPTIONS]\n");
	usage.append("OPTIONS:\n");
	usage.append("  -delim <delimiter>             Delimiter to use [,]\n");
	usage.append("  -dateFormat <dateFormatString> Date format [default for Locale.ENGLISH]\n");
	usage.append("  -nullString <nullString>       String that signifies NULL [none]\n");
	usage.append("  -skipRows <skipRows>           Number of rows to skip [0]\n");
	usage.append("  -skipCOls <columnsToSkip>      Comma-separated list of columsn to skip in the input file\n");
	usage.append("  -maxRows <maxRows>             Maximum number of rows to read (-1 means all) [-1]\n");
	usage.append("  -maxErrors <maxErrors>         Maximum parse errors to endure [10]\n");
	usage.append("  -badDir <badDirectory>         Directory for where to place badly parsed rows. [none]\n");
	usage.append("  -port <portNumber>             CQL Port Number [9042]\n");
	usage.append("  -user <username>               Cassandra username [none]\n");
	usage.append("  -pw <password>                 Password for user [none]\n");
	usage.append("  -consistencyLevel <CL>         Consistency level [LOCAL_ONE]");
	usage.append("  -numFutures <numFutures>       Number of CQL futures to keep in flight [1000]\n");
	usage.append("  -batchSize <batchSize>         Number of INSERTs to batch together [1]\n");
	usage.append("  -decimalDelim <decimalDelim>   Decimal delimiter [.] Other option is ','\n");
	usage.append("  -boolStyle <boolStyleString>   Style for booleans [TRUE_FALSE]\n");
	usage.append("  -numThreads <numThreads>       Number of concurrent threads (files) to load [num cores]\n");
	usage.append("  -queryTimeout <# seconds>      Query timeout (in seconds) [2]\n");
	usage.append("  -numRetries <numRetries>       Number of times to retry the INSERT [1]\n");
	usage.append("  -maxInsertErrors <# errors>    Maximum INSERT errors to endure [10]\n");
	usage.append("  -rate <rows-per-second>        Maximum insert rate [50000]\n");
	usage.append("  -progressRate <num txns>       How often to report the insert rate [100000]\n");
	usage.append("  -rateFile <filename>           Where to print the rate statistics\n");
	usage.append("  -successDir <dir>              Directory where to move successfully loaded files\n");
	usage.append("  -failureDir <dir>              Directory where to move files that did not successfully load\n");

	usage.append("\n\nExamples:\n");
	usage.append("cassandra-loader -f /path/to/file.csv -host localhost -schema \"test.test3(a, b, c)\"\n");
	usage.append("cassandra-loader -f /path/to/directory -host 1.2.3.4 -schema \"test.test3(a, b, c)\" -delim \"\\t\" -numThreads 10\n");
	usage.append("cassandra-loader -f stdin -host localhost -schema \"test.test3(a, b, c)\" -user myuser -pw mypassword\n");
	return usage.toString();
    }
    
    private boolean validateArgs() {
	if (0 >= numFutures) {
	    System.err.println("Number of futures must be positive (" + numFutures + ")");
	    return false;
	}
	if (0 >= batchSize) {
	    System.err.println("Batch size must be positive (" + batchSize + ")");
	    return false;
	}
	if (0 >= queryTimeout) {
	    System.err.println("Query timeout must be positive");
	    return false;
	}
	if (0 > maxInsertErrors) {
	    System.err.println("Maximum number of insert errors must be non-negative");
	    return false;
	}
	if (0 > numRetries) {
	    System.err.println("Number of retries must be non-negative");
	    return false;
	}
	if (0 > skipRows) {
	    System.err.println("Number of rows to skip must be non-negative");
	    return false;
	}
	if (0 >= maxRows) {
	    System.err.println("Maximum number of rows to load must be positive");
	    return false;
	}
	if (0 > maxErrors) {
	    System.err.println("Maximum number of parse errors must be non-negative");
	    return false;
	}
	if (0 > progressRate) {
	    System.err.println("Progress rate must be non-negative");
	    return false;
	}
	if (numThreads < 1) {
	    System.err.println("Number of threads must be non-negative");
	    return false;
	}
	if (!STDIN.equalsIgnoreCase(filename)) {
	    File infile = new File(filename);
	    if ((!infile.isFile()) && (!infile.isDirectory())) {
		System.err.println("The -f argument needs to be a file or a directory");
		return false;
	    }
	    if (infile.isDirectory()) {
		File[] infileList = infile.listFiles();
		if (infileList.length < 1) {
		    System.err.println("The directory supplied is empty");
		    return false;
		}
	    }
	}
	if (null != successDir) {
	    if (STDIN.equalsIgnoreCase(filename)) {
		System.err.println("Cannot specify -successDir with stdin");
		return false;
	    }
	    File sdir = new File(successDir);
	    if (!sdir.isDirectory()) {
		System.err.println("-successDir must be a directory");
		return false;
	    }
	}
	if (null != failureDir) {
	    if (STDIN.equalsIgnoreCase(filename)) {
		System.err.println("Cannot specify -failureDir with stdin");
		return false;
	    }
	    File sdir = new File(failureDir);
	    if (!sdir.isDirectory()) {
		System.err.println("-failureDir must be a directory");
		return false;
	    }
	}
	if ((null == username) && (null != password)) {
	    System.err.println("If you supply the password, you must supply the username");
	    return false;
	}
	if ((null != username) && (null == password)) {
	    System.err.println("If you supply the username, you must supply the password");
	    return false;
	}

	if (0 > rate) {
	    System.err.println("Rate must be positive");
	    return false;
	}

	return true;
    }
    
    private boolean parseArgs(String[] args) {
	if (args.length == 0) {
	    System.err.println("No arguments specified");
	    return false;
	}
	if (0 != args.length % 2) {
	    System.err.println("Not an even number of parameters");
	    return false;
	}

	Map<String, String> amap = new HashMap<String,String>();
	for (int i = 0; i < args.length; i+=2)
	    amap.put(args[i], args[i+1]);

	host = amap.remove("-host");
	if (null == host) { // host is required
	    System.err.println("Must provide a host");
	    return false;
	}

	filename = amap.remove("-f");
	if (null == filename) { // filename is required
	    System.err.println("Must provide a filename/directory");
	    return false;
	}

	cqlSchema = amap.remove("-schema");
	if (null == cqlSchema) { // schema is required
	    System.err.println("Must provide a schema");
	    return false;
	}

	String tkey;
	if (null != (tkey = amap.remove("-port")))          port = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-user")))          username = tkey;
	if (null != (tkey = amap.remove("-pw")))            password = tkey;
	if (null != (tkey = amap.remove("-consistencyLevel"))) consistencyLevel = ConsistencyLevel.valueOf(tkey);
	if (null != (tkey = amap.remove("-numFutures")))    inNumFutures = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-batchSize")))    batchSize = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-queryTimeout")))  queryTimeout = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-maxInsertErrors"))) maxInsertErrors = Long.parseLong(tkey);
	if (null != (tkey = amap.remove("-numRetries")))    numRetries = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-maxErrors")))     maxErrors = Long.parseLong(tkey);
	if (null != (tkey = amap.remove("-skipRows")))      skipRows = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-skipCols")))      skipCols = tkey;
	if (null != (tkey = amap.remove("-maxRows")))       maxRows = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-badDir")))        badDir = tkey;
	if (null != (tkey = amap.remove("-dateFormat")))    dateFormatString = tkey;
	if (null != (tkey = amap.remove("-nullString")))    nullString = tkey;
	if (null != (tkey = amap.remove("-delim")))         delimiter = tkey;
	if (null != (tkey = amap.remove("-numThreads")))    numThreads = Integer.parseInt(tkey);
	if (null != (tkey = amap.remove("-rate")))          rate = Double.parseDouble(tkey);
	if (null != (tkey = amap.remove("-progressRate")))  progressRate = Long.parseLong(tkey);
	if (null != (tkey = amap.remove("-rateFile")))      rateFile = tkey;
	if (null != (tkey = amap.remove("-successDir")))    successDir = tkey;
	if (null != (tkey = amap.remove("-failureDir")))    failureDir = tkey;
	if (null != (tkey = amap.remove("-decimalDelim"))) {
	    if (tkey.equals(","))
		locale = Locale.FRANCE;
	}
	if (null != (tkey = amap.remove("-boolStyle"))) {
	    boolStyle = BooleanParser.getBoolStyle(tkey);
	    if (null == boolStyle) {
		System.err.println("Bad boolean style.  Options are: " + BooleanParser.getOptions());
		return false;
	    }
	}
	if (-1 == maxRows)
	    maxRows = Long.MAX_VALUE;
	if (-1 == maxErrors)
	    maxErrors = Long.MAX_VALUE;
	if (-1 == maxInsertErrors)
	    maxInsertErrors = Long.MAX_VALUE;
	
	if (!amap.isEmpty()) {
	    for (String k : amap.keySet())
		System.err.println("Unrecognized option: " + k);
	    return false;
	}

	if (0 < inNumFutures)
	    numFutures = inNumFutures / numThreads;

	return validateArgs();
    }

    private void setup() throws IOException {
	// Connect to Cassandra
	PoolingOptions pOpts = new PoolingOptions();
	pOpts.setCoreConnectionsPerHost(HostDistance.LOCAL, 4);
	pOpts.setMaxConnectionsPerHost(HostDistance.LOCAL, 4);
	Cluster.Builder clusterBuilder = Cluster.builder()
	    .addContactPoint(host)
	    .withPort(port)
	    //.withProtocolVersion(ProtocolVersion.V3) 
	    .withProtocolVersion(ProtocolVersion.V2) // Should be V3, but issues for now....
	    //.withCompression(ProtocolOptions.Compression.LZ4)
	    .withPoolingOptions(pOpts)
	    .withLoadBalancingPolicy(new TokenAwarePolicy( new DCAwareRoundRobinPolicy(), true));

	if (null != username)
	    clusterBuilder = clusterBuilder.withCredentials(username, password);
	cluster = clusterBuilder.build();
	if (null == cluster) {
	    throw new IOException("Could not create cluster");
	}

	if (null != rateFile) {
	    if (STDERR.equalsIgnoreCase(rateFile)) {
		rateStream = System.err;
	    }
	    else {
		rateStream = new PrintStream(new BufferedOutputStream(new FileOutputStream(rateFile)), true);
	    }
	}
	Session tsession = cluster.connect();
	Metrics metrics = cluster.getMetrics();
	com.codahale.metrics.Timer timer = metrics.getRequestsTimer();
	rateLimiter = new RateLimiter(rate, progressRate, timer, rateStream);
	//rateLimiter = new Latency999RateLimiter(rate, progressRate, 3000, 200, 10, 0.5, 0.1, cluster, false);
	session = new RateLimitedSession(tsession, rateLimiter);
    }

    private void cleanup() {
	rateLimiter.report(null, null);
	if (rateStream != null)
	    rateStream.close();
	if (null != session)
	    session.close();
	if (null != cluster)
	    cluster.close();
    }
    
    public boolean run(String[] args) throws IOException, ParseException, InterruptedException, ExecutionException {
	if (false == parseArgs(args)) {
	    System.err.println("Bad arguments");
	    System.err.println(usage());
	    return false;
	}

	// Setup
	setup();
	
	// open file
	BufferedReader reader = null;
	String readerName = "";
	Deque<File> fileList = new ArrayDeque<File>();
	File infile = null;
	File[] inFileList = null;
	boolean onefile = true;
	if (STDIN.equalsIgnoreCase(filename)) {
	    infile = null;
	}
	else {
	    infile = new File(filename);
	    if (infile.isFile()) {
	    }
	    else {
		inFileList = infile.listFiles();
		if (inFileList.length < 1)
		    throw new IOException("directory is empty");
		onefile = false;
		Arrays.sort(inFileList, 
			    new Comparator<File>() {
				public int compare(File f1, File f2) {
				    return f1.getName().compareTo(f2.getName());
				}
			    });
		for (int i = 0; i < inFileList.length; i++)
		    fileList.push(inFileList[i]);
	    }
	}

	// Launch Threads
	ExecutorService executor;
	long total = 0;
	if (onefile) {
	    // One file/stdin to process
	    executor = Executors.newSingleThreadExecutor();
	    Callable<Long> worker = new CqlDelimLoadTask(cqlSchema, delimiter, 
							 nullString,
							 dateFormatString, 
							 boolStyle, locale, 
							 maxErrors, skipRows,
							 skipCols,
							 maxRows, badDir, infile, 
							 session, consistencyLevel,
							 numFutures, batchSize,
							 numRetries, queryTimeout,
							 maxInsertErrors, 
							 successDir, failureDir);
	    Future<Long> res = executor.submit(worker);
	    total = res.get();
	    executor.shutdown();
	}
	else {
	    executor = Executors.newFixedThreadPool(numThreads);
	    Set<Future<Long>> results = new HashSet<Future<Long>>();
	    while (!fileList.isEmpty()) {
		File tFile = fileList.pop();
		Callable<Long> worker = new CqlDelimLoadTask(cqlSchema, delimiter,
							     nullString,
							     dateFormatString, 
							     boolStyle, locale, 
							     maxErrors, skipRows,
							     skipCols,
							     maxRows, badDir, tFile, 
							     session,
							     consistencyLevel,
							     numFutures, batchSize,
							     numRetries, 
							     queryTimeout,
							     maxInsertErrors, 
							     successDir, failureDir);
		results.add(executor.submit(worker));
	    }
	    executor.shutdown();
	    for (Future<Long> res : results)
		total += res.get();
	}

	// Cleanup
	cleanup();
	//System.err.println("Total rows inserted: " + total);

	return true;
    }

    public static void main(String[] args) throws IOException, ParseException, InterruptedException, ExecutionException {
	CqlDelimLoad cdl = new CqlDelimLoad();
	boolean success = cdl.run(args);
	if (success) {
            System.exit(0);
        } else {
            System.exit(-1);
        }
    }
}

