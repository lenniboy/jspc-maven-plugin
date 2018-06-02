/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jasper.compiler;

import java.io.*;
import java.util.*;

import org.apache.jasper.JasperException;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.*;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.classfmt.*;
import org.eclipse.jdt.internal.compiler.env.*;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.sonatype.plexus.build.incremental.BuildContext;

import io.leonard.maven.plugins.jspc.JspCContextAccessor;

/**
 * Based on {@link JDTCompiler}.<br>
 * Try to avoid some synchronization with this compiler when it use in parallel (with more than 4 threads)
 */
public class ParallelJDTCompiler extends org.apache.jasper.compiler.Compiler {

  private final Log log;
  private final BuildContext buildContext;
  ClassLoader classLoader;
  String sourceFile;
  String outputDir;
  String packageName;
  String targetClassName;

  public ParallelJDTCompiler(Log log, BuildContext buildContext, ErrorDispatcher errorDispatcher) {
	super();
	this.log = log;
	this.buildContext = buildContext;
	this.errDispatcher = errorDispatcher;
}

public boolean isCheckFileNecessary(char[] packageName) {
    if (Character.isUpperCase(packageName[0])) {
      return false;
    }

    String filename = String.valueOf(packageName);
    return !filename.contains(".");
  }

  class CompilationUnit implements ICompilationUnit {

    private final String className;
    private final String sourceFile;

    CompilationUnit(String sourceFile, String className) {
      this.className = className;
      this.sourceFile = sourceFile;
    }

    @Override
    public char[] getFileName() {
      return sourceFile.toCharArray();
    }

    @Override
    public char[] getContents() {
      char[] result = null;
      try (FileInputStream is = new FileInputStream(sourceFile);
          InputStreamReader isr = new InputStreamReader(
              is, ctxt.getOptions().getJavaEncoding());
          Reader reader = new BufferedReader(isr)) {
        char[] chars = new char[8192];
        StringBuilder buf = new StringBuilder();
        int count;
        while ((count = reader.read(chars, 0,
            chars.length)) > 0) {
          buf.append(chars, 0, count);
        }
        result = new char[buf.length()];
        buf.getChars(0, result.length, result, 0);
      } catch (IOException e) {
        log.error("Compilation error", e);
      }
      return result;
    }

    @Override
    public char[] getMainTypeName() {
      int dot = className.lastIndexOf('.');
      if (dot > 0) {
        return className.substring(dot + 1).toCharArray();
      }
      return className.toCharArray();
    }

    @Override
    public char[][] getPackageName() {
      StringTokenizer izer = new StringTokenizer(className, ".");
      char[][] result = new char[izer.countTokens() - 1][];
      for (int i = 0; i < result.length; i++) {
        String tok = izer.nextToken();
        result[i] = tok.toCharArray();
      }
      return result;
    }

    @Override
    public boolean ignoreOptionalProblems() {
      return false;
    }
  }
  
  class NullEnvironmentAnswer extends NameEnvironmentAnswer{

    public NullEnvironmentAnswer(IBinaryType binaryType, AccessRestriction accessRestriction) {
      super(binaryType, accessRestriction);
    }
    
  }

  final INameEnvironment env = new INameEnvironment() {

    @Override
    public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < compoundTypeName.length; i++) {
        if (i > 0)
          result.append('.');
        result.append(compoundTypeName[i]);
      }
      return findType(result.toString());
    }

    @Override
    public NameEnvironmentAnswer findType(char[] typeName,
        char[][] packageName) {
      StringBuilder result = new StringBuilder();
      int i = 0;
      for (; i < packageName.length; i++) {
        if (i > 0)
          result.append('.');
        result.append(packageName[i]);
      }
      if (i > 0)
        result.append('.');
      result.append(typeName);
      return findType(result.toString());
    }

    private NameEnvironmentAnswer findType(String className) {

      if (className.equals(targetClassName)) {
        ICompilationUnit compilationUnit = new CompilationUnit(sourceFile, className);
        return new NameEnvironmentAnswer(compilationUnit, null);
      }

      String resourceName = className.replace('.', '/') + ".class";

      Map<String, NameEnvironmentAnswer> resourcesCache = ((JspCContextAccessor) ctxt.getOptions()).getResourcesCache();
      if (resourcesCache.containsKey(resourceName)) {
        NameEnvironmentAnswer answer = resourcesCache.get(resourceName);
        return answer instanceof NullEnvironmentAnswer ? null : answer;
      }
      NameEnvironmentAnswer answer = findAnswer(className, resourceName);
      resourcesCache.put(resourceName, answer);
      return answer instanceof NullEnvironmentAnswer ? null : answer;
    }

    private NameEnvironmentAnswer findAnswer(String className, String resourceName) {
      try (InputStream is = classLoader.getResourceAsStream(resourceName)) {
        if (is != null) {
          byte[] classBytes;
          byte[] buf = new byte[8192];
          ByteArrayOutputStream baos = new ByteArrayOutputStream(buf.length);
          int count;
          while ((count = is.read(buf, 0, buf.length)) > 0) {
            baos.write(buf, 0, count);
          }
          baos.flush();
          classBytes = baos.toByteArray();
          char[] fileName = className.toCharArray();
          ClassFileReader classFileReader = new ClassFileReader(classBytes, fileName,
              true);
          return new NameEnvironmentAnswer(classFileReader, null);
        }
      } catch (IOException | org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException exc) {
        log.error("Compilation error", exc);
      }
      return new NullEnvironmentAnswer(null, null);
    }

    private boolean isPackage(String result) {
      if (result.equals(targetClassName)) {
        return false;
      }
      String resourceName = result.replace('.', '/') + ".class";
      Map<String, NameEnvironmentAnswer> resourcesCache = ((JspCContextAccessor) ctxt.getOptions()).getResourcesCache();
      if (resourcesCache.containsKey(resourceName)) {
        return resourcesCache.get(resourceName) instanceof NullEnvironmentAnswer;
      }

      try (InputStream is = classLoader.getResourceAsStream(resourceName)) {
        NameEnvironmentAnswer answer = findAnswer(result, resourceName);
        resourcesCache.put(resourceName, answer);
        return is == null;
      } catch (IOException e) {
        // we are here, since close on is failed. That means it was not null
        return false;
      }

    }

    @Override
    public boolean isPackage(char[][] parentPackageName,
        char[] packageName) {
      if (!isCheckFileNecessary(packageName)) {
        return false;
      }

      StringBuilder result = new StringBuilder();
      int i = 0;
      if (parentPackageName != null) {
        for (; i < parentPackageName.length; i++) {
          if (i > 0)
            result.append('.');
          result.append(parentPackageName[i]);
        }
      }

      if (Character.isUpperCase(packageName[0]) && !isPackage(result.toString())) {
        return false;
      }
      if (i > 0)
        result.append('.');
      result.append(packageName);

      return isPackage(result.toString());
    }

    @Override
    public void cleanup() {
    }

  };

/**
   * Compile the servlet from .java file to .class file
   */
  @Override
  protected void generateClass(String[] smap)
      throws FileNotFoundException, JasperException, Exception {

      long t1 = 0;
      if (log.isDebugEnabled()) {
          t1 = System.currentTimeMillis();
      }
      
      buildContext.removeMessages(new File(ctxt.getJspFile()));
      
      classLoader = ctxt.getJspLoader();
      sourceFile = ctxt.getServletJavaFileName();
      outputDir = ctxt.getOptions().getScratchDir().getAbsolutePath();
      packageName = ctxt.getServletPackageName();
      targetClassName =
          ((packageName.length() != 0) ? (packageName + ".") : "")
                  + ctxt.getServletClassName();
      String[] fileNames = new String[] {sourceFile};
      String[] classNames = new String[] {targetClassName};
      final ArrayList<JavacErrorDetail> problemList = new ArrayList<>();

      
      final IErrorHandlingPolicy policy =
          DefaultErrorHandlingPolicies.proceedWithAllProblems();

      final Map<String,String> settings = new HashMap<>();
      settings.put(CompilerOptions.OPTION_LineNumberAttribute,
                   CompilerOptions.GENERATE);
      settings.put(CompilerOptions.OPTION_SourceFileAttribute,
                   CompilerOptions.GENERATE);
      settings.put(CompilerOptions.OPTION_ReportDeprecation,
                   CompilerOptions.IGNORE);
      if (ctxt.getOptions().getJavaEncoding() != null) {
          settings.put(CompilerOptions.OPTION_Encoding,
                  ctxt.getOptions().getJavaEncoding());
      }
      if (ctxt.getOptions().getClassDebugInfo()) {
          settings.put(CompilerOptions.OPTION_LocalVariableAttribute,
                       CompilerOptions.GENERATE);
      }

      // Source JVM
      if(ctxt.getOptions().getCompilerSourceVM() != null) {
          String opt = ctxt.getOptions().getCompilerSourceVM();
          if(opt.equals("1.1")) {
              settings.put(CompilerOptions.OPTION_Source,
                           CompilerOptions.VERSION_1_1);
          } else if(opt.equals("1.2")) {
              settings.put(CompilerOptions.OPTION_Source,
                           CompilerOptions.VERSION_1_2);
          } else if(opt.equals("1.3")) {
              settings.put(CompilerOptions.OPTION_Source,
                           CompilerOptions.VERSION_1_3);
          } else if(opt.equals("1.4")) {
              settings.put(CompilerOptions.OPTION_Source,
                           CompilerOptions.VERSION_1_4);
          } else if(opt.equals("1.5")) {
              settings.put(CompilerOptions.OPTION_Source,
                           CompilerOptions.VERSION_1_5);
          } else if(opt.equals("1.6")) {
              settings.put(CompilerOptions.OPTION_Source,
                           CompilerOptions.VERSION_1_6);
          } else if(opt.equals("1.7")) {
              settings.put(CompilerOptions.OPTION_Source,
                           CompilerOptions.VERSION_1_7);
          } else if(opt.equals("1.8")) {
              settings.put(CompilerOptions.OPTION_Source,
                           CompilerOptions.VERSION_1_8);
          } else if(opt.equals("1.9")) {
              settings.put(CompilerOptions.OPTION_Source,
                           CompilerOptions.VERSION_1_9);
          } else {
              log.warn("Unknown source VM " + opt + " ignored.");
              settings.put(CompilerOptions.OPTION_Source,
                      CompilerOptions.VERSION_1_7);
          }
      } else {
          // Default to 1.7
          settings.put(CompilerOptions.OPTION_Source,
                  CompilerOptions.VERSION_1_7);
      }

      // Target JVM
      if(ctxt.getOptions().getCompilerTargetVM() != null) {
          String opt = ctxt.getOptions().getCompilerTargetVM();
          if(opt.equals("1.1")) {
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                           CompilerOptions.VERSION_1_1);
          } else if(opt.equals("1.2")) {
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                           CompilerOptions.VERSION_1_2);
          } else if(opt.equals("1.3")) {
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                           CompilerOptions.VERSION_1_3);
          } else if(opt.equals("1.4")) {
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                           CompilerOptions.VERSION_1_4);
          } else if(opt.equals("1.5")) {
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                           CompilerOptions.VERSION_1_5);
              settings.put(CompilerOptions.OPTION_Compliance,
                      CompilerOptions.VERSION_1_5);
          } else if(opt.equals("1.6")) {
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                           CompilerOptions.VERSION_1_6);
              settings.put(CompilerOptions.OPTION_Compliance,
                      CompilerOptions.VERSION_1_6);
          } else if(opt.equals("1.7")) {
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                           CompilerOptions.VERSION_1_7);
              settings.put(CompilerOptions.OPTION_Compliance,
                      CompilerOptions.VERSION_1_7);
          } else if(opt.equals("1.8")) {
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                           CompilerOptions.VERSION_1_8);
              settings.put(CompilerOptions.OPTION_Compliance,
                      CompilerOptions.VERSION_1_8);
          } else if(opt.equals("1.9")) {
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                           CompilerOptions.VERSION_1_9);
              settings.put(CompilerOptions.OPTION_Compliance,
                      CompilerOptions.VERSION_1_9);
          } else {
              log.warn("Unknown target VM " + opt + " ignored.");
              settings.put(CompilerOptions.OPTION_TargetPlatform,
                      CompilerOptions.VERSION_1_7);
          }
      } else {
          // Default to 1.7
          settings.put(CompilerOptions.OPTION_TargetPlatform,
                  CompilerOptions.VERSION_1_7);
          settings.put(CompilerOptions.OPTION_Compliance,
                  CompilerOptions.VERSION_1_7);
      }

      final IProblemFactory problemFactory =
          new DefaultProblemFactory(Locale.getDefault());

      final ICompilerRequestor requestor = new ICompilerRequestor() {
              @Override
              public void acceptResult(CompilationResult result) {
                  try {
                      if (result.hasProblems()) {
                          IProblem[] problems = result.getProblems();
                          for (int i = 0; i < problems.length; i++) {
                              IProblem problem = problems[i];
                              if (problem.isError()) {
                                  String name =
                                      new String(problems[i].getOriginatingFileName());
                                  try {
                                      problemList.add(ErrorDispatcher.createJavacError
                                              (name, pageNodes, new StringBuilder(problem.getMessage()),
                                                      problem.getSourceLineNumber(), ctxt));
                                  } catch (JasperException e) {
                                      log.error("Error visiting node", e);
                                  }
                              }
                          }
                      }
                      if (problemList.isEmpty()) {
                          ClassFile[] classFiles = result.getClassFiles();
                          for (int i = 0; i < classFiles.length; i++) {
                              ClassFile classFile = classFiles[i];
                              char[][] compoundName =
                                  classFile.getCompoundName();
                              StringBuilder classFileName = new StringBuilder(outputDir).append('/');
                              for (int j = 0;
                                   j < compoundName.length; j++) {
                                  if(j > 0)
                                      classFileName.append('/');
                                  classFileName.append(compoundName[j]);
                              }
                              byte[] bytes = classFile.getBytes();
                              classFileName.append(".class");
                              try (OutputStream fout = buildContext.newFileOutputStream(
                                      new File(classFileName.toString()));
                                      BufferedOutputStream bos = new BufferedOutputStream(fout);) {
                                  bos.write(bytes);
                              }
                          }
                      }
                  } catch (IOException exc) {
                	  throw new RuntimeException(exc);
//                	  buildContext.addMessage(new File(ctxt.getJspFile()), 0, 0,exc.getMessage(), BuildContext.SEVERITY_ERROR, exc);
                  }
              }
          };

      ICompilationUnit[] compilationUnits =
          new ICompilationUnit[classNames.length];
      for (int i = 0; i < compilationUnits.length; i++) {
          String className = classNames[i];
          compilationUnits[i] = new CompilationUnit(fileNames[i], className);
      }
      CompilerOptions cOptions = new CompilerOptions(settings);
      cOptions.parseLiteralExpressionsAsConstants = true;
      Compiler compiler = new Compiler(env,
                                       policy,
                                       cOptions,
                                       requestor,
                                       problemFactory);
      compiler.compile(compilationUnits);

      if (!ctxt.keepGenerated()) {
          File javaFile = new File(ctxt.getServletJavaFileName());
          javaFile.delete();
          buildContext.refresh(javaFile);
      }

      if (!problemList.isEmpty()) {
          JavacErrorDetail[] jeds =
              problemList.toArray(new JavacErrorDetail[0]);
          errDispatcher.javacError(jeds);
//          for( JavacErrorDetail jed : jeds ) {
//        	  buildContext.addMessage(new File(jed.getJspFileName()), jed.getJspBeginLineNumber(), 0, jed.getErrorMessage(), BuildContext.SEVERITY_ERROR, null);
//          }
      }

      if( log.isDebugEnabled() ) {
          long t2=System.currentTimeMillis();
          log.debug("Compiled " + ctxt.getServletJavaFileName() + " "
                    + (t2-t1) + "ms");
      }

      if (ctxt.isPrototypeMode()) {
          return;
      }

      // JSR45 Support
      if (! options.isSmapSuppressed()) {
          SmapUtil.installSmap(smap);
          for (int i = 0; i < smap.length; i += 2) {
              File outServlet = new File(smap[i]);
              buildContext.refresh(outServlet);
          }
      }
  }
}
