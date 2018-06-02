//========================================================================
//$Id: JspcMojo.java 6430 2011-03-15 17:52:17Z joakime $
//Copyright 2006 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================
package io.leonard.maven.plugins.jspc;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import org.apache.jasper.*;
import org.apache.jasper.compiler.ErrorDispatcher;
import org.apache.jasper.compiler.MavenErrorDispatcher;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.*;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.*;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jetty.util.IO;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * <p>
 * This goal will compile jsps for a webapp so that they can be included in a
 * war.
 * </p>
 * <p>
 * It is a fork of <a href="http://docs.codehaus.org/display/JETTY/Maven+Jetty+Jspc+Plugin">jetty-jspc-maven-plugin</a>
 * but has the following improvements:
 * </p>
 * <ul>
 * <li>Faster: on my test project I was able to cut down the compilation
 * time by about 40%
 * </li>
 * <li>Indication of the progress of the compilation by showing which JSP
 * is currently being compiled
 * </li>
 * </ul>
 * <p>
 * The compiler used in this plugin the Apache Jasper 8.5.8.
 * </p>
 *
 * @author <a href="mailto:leonard.ehrenfrie@web.de">Leonard Ehrenfried</a>
 * @description Runs jspc compiler to produce .java and .class files
 */
@org.apache.maven.plugins.annotations.Mojo( name = "compile", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true )
public class JspcMojo extends AbstractMojo {

  private static final String WEB_XML = "web.xml";
  public static final String END_OF_WEBAPP = "</web-app>";
  
  @Component
  private BuildContext buildContext;

  /**
   * The maven project.
   *
   */
  @Parameter(defaultValue="${project}", readonly=true, required=true)
  private MavenProject project;
  /**
   * File into which to generate the &lt;servlet&gt; and
   * &lt;servlet-mapping&gt; tags for the compiled jsps.
   * <br>
   * <p>
   * If multithreading mode is active (threads > 1), then this filename
   * will be suffixed by ".threadIndex" (example : webfrag.xml.3).
   */
  @Parameter(defaultValue="${basedir}/target/webfrag.xml")
  private String webXmlFragment;
  /**
   * Optional. A marker string in the src web.xml file which indicates where
   * to merge in the generated web.xml fragment. Note that the marker string
   * will NOT be preserved during the insertion. Can be left blank, in which
   * case the generated fragment is inserted just before the &lt;/web-app&gt;
   * line
   *
   * @parameter
   */
  @Parameter
  private String insertionMarker;
  /**
   * Merge the generated fragment file with the web.xml from
   * webAppSourceDirectory. The merged file will go into the same directory as
   * the webXmlFragment.
   */
  @Parameter(defaultValue="true")
  private boolean mergeFragment;
  /**
   * The destination directory into which to put the compiled jsps.
   */
  @Parameter(defaultValue="${project.build.outputDirectory}")
  private String generatedClasses;
  /**
   * Controls whether or not .java files generated during compilation will be
   * preserved.
   */
  @Parameter(defaultValue="false")
  private boolean keepSources;
  /**
   * Default root package for all generated classes
   */
  @Parameter(defaultValue="jsp")
  private String packageRoot;
  /**
   * Root directory for all html/jsp etc files
   */
  @Parameter(defaultValue="${basedir}/src/main/webapp")
  private String webAppSourceDirectory;
  /**
   * Location of web.xml. Defaults to src/main/webapp/web.xml.
   */
  @Parameter(defaultValue="${basedir}/src/main/webapp/WEB-INF/web.xml")
  private String webXml;
  /**
   * The comma separated list of patterns for file extensions to be processed. By default
   * will include all .jsp and .jspx files.
   */
  @Parameter(defaultValue="**\\/*.jsp, **\\/*.jspx,  **\\/*.jspf")
  private String[] includes;
  /**
   * The comma separated list of file name patters to exclude from compilation.
   */
  @Parameter(defaultValue="**\\/.svn\\/**")
  private String[] excludes;
  /**
   * The location of the compiled classes for the webapp
   */
  @Parameter(defaultValue="${project.build.outputDirectory}")
  private File classesDirectory;
  /**
   * Whether or not to output more verbose messages during compilation.
   */
  @Parameter(defaultValue="false")
  private boolean verbose;
  /**
   * If true, validates tlds when parsing.
   */
  @Parameter(defaultValue="false")
  private boolean validateXml;
  /**
   * The encoding scheme to use.
   */
  @Parameter(defaultValue="UTF-8")
  private String javaEncoding;
  /**
   * Whether or not to generate JSR45 compliant debug info
   */
  @Parameter(defaultValue="true")
  private boolean suppressSmap;
  /**
   * Whether or not to ignore precompilation errors caused by jsp fragments.
   */
  @Parameter(defaultValue="false")
  private boolean ignoreJspFragmentErrors;
  /**
   * Allows a prefix to be appended to the standard schema locations so that
   * they can be loaded from elsewhere.
   */
  @Parameter
  private String schemaResourcePrefix;
  /**
   * Fail the build and stop at the first jspc error.
   * If set to "false", all jsp will be compiled even if they raise errors, and all errors will be listed when they raise.
   * In this case the build will fail too.
   * In case of threads > 1 and stopAtFirstError=true, each thread can have is own first error.
   */
  @Parameter(defaultValue="true")
  private boolean stopAtFirstError;
  /**
   * The number of threads will be used for compile all of the jsps.
   * Number total of jsps will be divided by thread number.
   * Each part will be given to differents thread.
   * 
   * Defaults to the number of CPUs available as indicated by {@link Runtime#availableProcessors()}
   */
  @Parameter(defaultValue="-1")
  private int threads;

  /**
   * Whether Jsp Tag Pooling should be enabled.
   */
  @Parameter(defaultValue="true")
  private boolean enableJspTagPooling;

  /**
   * Should white spaces in template text between actions or directives be trimmed?
   */
  @Parameter(defaultValue="false")
  private boolean trimSpaces;

  /**
   * Should text strings be generated as char arrays, to improve performance in some cases?
   */
  @Parameter(defaultValue="false")
  private boolean genStringAsCharArray;

  /**
   * Version of Java used to compile the jsp files.
   */
  @Parameter(defaultValue="1.7")
  private String compilerVersion;
  
  private Map<String,NameEnvironmentAnswer> resourcesCache = new ConcurrentHashMap<>();

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
	if(threads == -1) {
		threads = Runtime.getRuntime().availableProcessors();
	}
    MavenErrorDispatcher errorDispatcher = new MavenErrorDispatcher(buildContext, getLog());
    if (getLog().isDebugEnabled()) {
      getLog().info("verbose=" + verbose);
      getLog().info("webAppSourceDirectory=" + webAppSourceDirectory);
      getLog().info("generatedClasses=" + generatedClasses);
      getLog().info("webXmlFragment=" + webXmlFragment);
      getLog().info("webXml=" + webXml);
      getLog().info("validateXml=" + validateXml);
      getLog().info("packageRoot=" + packageRoot);
      getLog().info("javaEncoding=" + javaEncoding);
      getLog().info("insertionMarker=" + (insertionMarker == null || insertionMarker.equals("") ? END_OF_WEBAPP : insertionMarker));
      getLog().info("keepSources=" + keepSources);
      getLog().info("mergeFragment=" + mergeFragment);
      getLog().info("suppressSmap=" + suppressSmap);
      getLog().info("ignoreJspFragmentErrors=" + ignoreJspFragmentErrors);
      getLog().info("schemaResourcePrefix=" + schemaResourcePrefix);
      getLog().info("stopAtFirstError=" + stopAtFirstError);
      getLog().info("threads=" + threads);
      getLog().info("enableJspTagPooling=" + enableJspTagPooling);
      getLog().info("trimSpaces=" + trimSpaces);
      getLog().info("genStringAsCharArray=" + genStringAsCharArray);
      getLog().info("compilerVersion=" + compilerVersion);
    }
    try {
      long start = System.currentTimeMillis();

      prepare();
      compile(errorDispatcher);
      cleanupSrcs();
      mergeWebXml();

      long finish = System.currentTimeMillis();
      long millis = finish - start;
      String time = String.format("%d min, %d sec",
        TimeUnit.MILLISECONDS.toMinutes(millis),
        TimeUnit.MILLISECONDS.toSeconds(millis)
          - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)));

      getLog().info("Compilation completed in " + time);
    } catch (Exception e) {
      throw new MojoExecutionException("Failure processing jsps", e);
    }
    if(errorDispatcher.isErrorOccurred()) {
  	  throw new MojoFailureException("Failed to process jsps");
    }
  }

  public void compile(ErrorDispatcher errorDispatcher) throws Exception {
    ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();

    ArrayList<URL> urls = new ArrayList<URL>();
    setUpClassPath(urls);
    URLClassLoader ucl = new URLClassLoader(urls.toArray(new URL[0]), currentClassLoader);
    StringBuilder classpathStr = new StringBuilder();

    for (int i = 0; i < urls.size(); i++) {
      if (getLog().isDebugEnabled()) {
        getLog().debug("webappclassloader contains: " + urls.get(i));
      }
      classpathStr.append(urls.get(i).getFile());
      if (getLog().isDebugEnabled()) {
        getLog().debug(
          "added to classpath: " + urls.get(i).getFile());
      }
      classpathStr.append(System.getProperty("path.separator"));
    }

    Thread.currentThread().setContextClassLoader(ucl);

    List<String> modifiedJspFiles = new ArrayList<>();
    for( String jspFile : getJspFiles(webAppSourceDirectory) ) {
    	if(buildContext.hasDelta(jspFile)) {
    		modifiedJspFiles.add(jspFile);
    	}
    }
    String[] jspFiles = modifiedJspFiles.toArray(new String[] {});
    if(verbose) {
        getLog().info("Files selected to precompile: " + StringUtils.join(jspFiles,", "));
    }

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    List<Future<String>> results = executor.invokeAll(initJspcWorkers(classpathStr, jspFiles, initJspList(jspFiles), errorDispatcher));
    executor.shutdown();

    getLog().info("Number total of jsps : " + jspFiles.length);
    manageResults(results);

    Thread.currentThread().setContextClassLoader(currentClassLoader);
  }

  private List<String> initJspList(String[] jspFiles) {
    List<String> jspFilesList = new ArrayList<>();
    Collections.addAll(jspFilesList, jspFiles);
    return jspFilesList;
  }

  private List<JspcWorker> initJspcWorkers(StringBuilder classpathStr, String[] jspFiles, List<String> jspFilesList, ErrorDispatcher errorDispatcher) throws JasperException, IOException {
    List<JspcWorker> workers = new ArrayList<>();
    int minItem = jspFiles.length / threads;
    int maxItem = minItem + 1;
    int threadsWithMaxItems = jspFiles.length - threads * minItem;
    int start = 0;
    JspCContextAccessor topJspC = initJspc(classpathStr, -1, null, errorDispatcher);
    for (int index = 0; index < threads; index++) {
      int itemsCount = (index < threadsWithMaxItems ? maxItem : minItem);
      int end = start + itemsCount;
      List<String> jspFilesSubList = jspFilesList.subList(start, end);
      if(!jspFilesSubList.isEmpty()) {
	      JspC firstJspC = initJspc(classpathStr, index, topJspC, errorDispatcher);
	      JspcWorker worker = new JspcWorker(firstJspC, jspFilesSubList);
	      workers.add(worker);
	      start = end;
	      getLog().info("Number of jsps for thread " + (index + 1) + " : " + jspFilesSubList.size());
      }
    }
    return workers;
  }

  private JspCContextAccessor initJspc(StringBuilder classpathStr, int threadIndex, JspCContextAccessor topJspC, ErrorDispatcher errorDispatcher) throws IOException, JasperException {
    JspCContextAccessor jspc = new JspCContextAccessor(buildContext, getLog(), errorDispatcher);
    jspc.setWebXmlFragment(getwebXmlFragmentFilename(threadIndex));
    jspc.setUriroot(webAppSourceDirectory);
    jspc.setPackage(packageRoot);
    jspc.setOutputDir(generatedClasses);
    jspc.setValidateXml(validateXml);
    jspc.setClassPath(classpathStr.toString());
    jspc.setCompile(true);
    jspc.setSmapSuppressed(suppressSmap);
    jspc.setSmapDumped(!suppressSmap);
    jspc.setJavaEncoding(javaEncoding);
    jspc.setFailOnError(stopAtFirstError);
    jspc.setPoolingEnabled(enableJspTagPooling);
    jspc.setTrimSpaces(trimSpaces);
    jspc.setGenStringAsCharArray(genStringAsCharArray);
    jspc.setCompilerSourceVM(compilerVersion);
    jspc.setCompilerTargetVM(compilerVersion);
    jspc.setResourcesCache(resourcesCache);
    if (topJspC == null) {
      jspc.initClassLoader();
      jspc.initServletContext();
    } else {
      jspc.initContext(topJspC);
    }

    // JspC#setExtensions() does not exist, so
    // always set concrete list of files that will be processed.

    getLog().info("Includes=" + StringUtils.join(includes, ","));
    if (excludes != null) {
      getLog().info("Excludes=" + StringUtils.join(excludes, ","));
    }

    if (verbose) {
      jspc.setVerbose(99);
    } else {
      jspc.setVerbose(0);
    }

    return jspc;
  }

  private void manageResults(List<Future<String>> results) throws InterruptedException, ExecutionException, MojoExecutionException {
    boolean failTheBuild = false;
    for (Future<String> result : results) {
      if (result.get() != null) {
        getLog().error(result.get());
        failTheBuild = true;
      }
    }

    if (failTheBuild) {
      throw new MojoExecutionException("see previous errors");
    }
  }

  private String[] getJspFiles(String webAppSrcDir) {
    DirectoryScanner scanner = new DirectoryScanner();
    scanner.setBasedir(new File(webAppSrcDir));
    if ((excludes != null) && (excludes.length != 0)) {
      scanner.setExcludes(excludes);
    }
    scanner.addDefaultExcludes();
    scanner.setIncludes(includes);
    scanner.setCaseSensitive(false);
    scanner.scan();

    String[] includedFiles = scanner.getIncludedFiles();

    getLog().debug(String.format("Included files returned from directory scan: %s", StringUtils.join(includedFiles, ",")));
    getLog().debug(String.format("Excluded files returned from directory scan: %s", StringUtils.join(scanner.getExcludedFiles(), ",")));
    getLog().debug(String.format("Excluded directories returned from directory scan: %s", StringUtils.join(scanner.getExcludedDirectories(), ",")));

    return includedFiles;
  }

  /**
   * Until Jasper supports the option to generate the srcs in a different dir
   * than the classes, this is the best we can do.
   */
  public void cleanupSrcs() {
    // delete the .java files - depending on keepGenerated setting
    if (!keepSources) {
      File generatedClassesDir = new File(generatedClasses);

      if (generatedClassesDir.exists() && generatedClassesDir.isDirectory()) {
        delete(generatedClassesDir, new FileFilter() {

          @Override
          public boolean accept(File f) {
            return f.isDirectory() || f.getName().endsWith(".java");
          }
        });
      }
    }
  }

  void delete(File dir, FileFilter filter) {
    File[] files = dir.listFiles(filter);
    for (int i = 0; i < files.length; i++) {
      File f = files[i];
      if (f.isDirectory()) {
        delete(f, filter);
      } else {
        f.delete();
      }
      buildContext.refresh(f);
    }
  }

  /**
   * Take the web fragment (for each thread if we active multithreading mode)
   * and put it inside a copy of the web.xml.
   * <p>
   * You can specify the insertion point by specifying the string in the
   * insertionMarker configuration entry.
   * <p>
   * If you dont specify the insertionMarker, then the fragment will be
   * inserted at the end of the file just before the &lt;/webapp&gt;
   *
   * @throws IOException by {@link JspcMojo#writeStartOfWebXmlMergedFile} method when reading xml file
   */
  public void mergeWebXml() throws IOException {
    if (mergeFragment) {

      // open the src web.xml
      File webXmlFile = getWebXmlFile();

      if (!webXmlFile.exists()) {
        getLog().info(webXmlFile.toString() + " does not exist, cannot merge with generated fragment");
        return;
      }

      File mergedWebXml = new File(new File(getwebXmlFragmentFilename(0)).getParentFile(),
        WEB_XML);
      try (BufferedReader webXmlReader = new BufferedReader(new FileReader(webXmlFile))) {
        try (PrintWriter mergedWebXmlWriter = new PrintWriter(buildContext.newFileOutputStream(mergedWebXml))) {
          String marker = writeStartOfWebXmlMergedFile(webXmlReader, mergedWebXmlWriter);

          writeXmlFragmentsInMergedXmlFile(mergedWebXmlWriter);

          writeEndOfWebXmlMergedFile(webXmlReader, mergedWebXmlWriter, marker);
        }
      }
    }
  }

  private String writeStartOfWebXmlMergedFile(BufferedReader webXmlReader, PrintWriter mergedWebXmlWriter) throws IOException {
    // read up to the insertion marker or the </webapp> if there is no
    // marker
    boolean atInsertPoint = false;
    boolean atEOF = false;
    String marker = (insertionMarker == null
      || insertionMarker.equals("") ? END_OF_WEBAPP : insertionMarker);
    while (!atInsertPoint && !atEOF) {
      String line = webXmlReader.readLine();
      if (line == null) {
        atEOF = true;
      } else if (line.indexOf(marker) >= 0) {
        atInsertPoint = true;
      } else {
        mergedWebXmlWriter.println(line);
      }
    }
    return marker;
  }

  private void writeXmlFragmentsInMergedXmlFile(PrintWriter mergedWebXmlWriter) throws IOException {
    for (int index = 0; index < threads; index++) {
      File fragmentWebXml = new File(getwebXmlFragmentFilename(index));
      if (!fragmentWebXml.exists()) {
        getLog().info("No fragment web.xml file generated for thread " + index);
      } else {
        // put in the generated fragment for the current thread
        try (BufferedReader fragmentWebXmlReader = new BufferedReader(
          new FileReader(fragmentWebXml))) {
          IO.copy(fragmentWebXmlReader, mergedWebXmlWriter);
        }
      }
    }
  }

  private void writeEndOfWebXmlMergedFile(BufferedReader webXmlReader, PrintWriter mergedWebXmlWriter, String marker) throws IOException {
    // if we inserted just before the </web-app>, put it back in
    if (marker.equals(END_OF_WEBAPP)) {
      mergedWebXmlWriter.println(END_OF_WEBAPP);
    }

    // copy in the rest of the original web.xml file
    IO.copy(webXmlReader, mergedWebXmlWriter);
  }

  private void prepare() {
    // For some reason JspC doesn't like it if the dir doesn't
    // already exist and refuses to create the web.xml fragment
    File generatedSourceDirectoryFile = new File(generatedClasses);
    if (!generatedSourceDirectoryFile.exists()) {
      generatedSourceDirectoryFile.mkdirs();
      buildContext.refresh(generatedSourceDirectoryFile);
    }
  }

  /**
   * Set up the execution classpath for Jasper.
   * <p>
   * Put everything in the classesDirectory and all of the dependencies on the
   * classpath.
   *
   * @param urls a list to which to add the urls of the dependencies
   * @throws IOException
   */
  private void setUpClassPath(List<URL> urls) throws IOException {
    String classesDir = classesDirectory.getCanonicalPath();
    classesDir = classesDir
      + (classesDir.endsWith(File.pathSeparator) ? "" : File.separator);
    //we must keep deprecated usage of File.toURL because URLClassloader seem not working with path with %20 for example.
    urls.add(new File(classesDir).toURL());

    if (getLog().isDebugEnabled()) {
      getLog().debug("Adding to classpath classes dir: " + classesDir);
    }

    for (Iterator<?> iter = project.getArtifacts().iterator(); iter.hasNext(); ) {
      Artifact artifact = (Artifact) iter.next();

      // Include runtime and compile time libraries
      if (!Artifact.SCOPE_TEST.equals(artifact.getScope())) {
        String filePath = artifact.getFile().getCanonicalPath();
        if (getLog().isDebugEnabled()) {
          getLog().debug(
            "Adding to classpath dependency file: " + filePath);
        }

        urls.add(artifact.getFile().toURL());
      }
    }
  }

  private File getWebXmlFile()
    throws IOException {
    File file = null;
    File baseDir = project.getBasedir().getCanonicalFile();
    File defaultWebAppSrcDir = new File(baseDir, "src/main/webapp").getCanonicalFile();
    File webAppSrcDir = new File(webAppSourceDirectory).getCanonicalFile();
    File defaultWebXml = new File(defaultWebAppSrcDir, WEB_XML).getCanonicalFile();

    //If the web.xml has been changed from the default, try that
    File webXmlFile = new File(webXml).getCanonicalFile();
    if (webXmlFile.compareTo(defaultWebXml) != 0) {
      file = new File(webXml);
      return file;
    }

    //If the web app src directory has not been changed from the default, use whatever
    //is set for the web.xml location
    file = new File(webAppSrcDir, WEB_XML);
    return file;
  }

  /**
   * Add thread index at the end of webXmlFragment filename to deal with multithreading.
   * If the number of threads is equal to 1 (no multithreading) we don't add suffix to maintain the same behavior
   * as in the mode without multithreading.
   *
   * @param threadNumber the index of current thread
   * @return web xml fragment filename with thread index
   */
  private String getwebXmlFragmentFilename(int threadIndex) {
    return threads == 1 ? webXmlFragment : webXmlFragment + "." + threadIndex;
  }
}
