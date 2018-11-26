/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scanner.mediumtest.fs;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.utils.MessageException;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.LogTester;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonar.scanner.mediumtest.AnalysisResult;
import org.sonar.scanner.mediumtest.ScannerMediumTester;
import org.sonar.xoo.XooPlugin;
import org.sonar.xoo.global.GlobalSensor;
import org.sonar.xoo.rule.XooRulesDefinition;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

public class FileSystemMediumTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public LogTester logTester = new LogTester();

  @Rule
  public ScannerMediumTester tester = new ScannerMediumTester()
    .registerPlugin("xoo", new XooPlugin())
    .addDefaultQProfile("xoo", "Sonar Way")
    .addDefaultQProfile("xoo2", "Sonar Way");

  private File baseDir;
  private ImmutableMap.Builder<String, String> builder;

  @Before
  public void prepare() throws IOException {
    baseDir = temp.getRoot();

    builder = ImmutableMap.<String, String>builder()
      .put("sonar.task", "scan")
      .put("sonar.projectBaseDir", baseDir.getAbsolutePath())
      .put("sonar.projectKey", "com.foo.project")
      .put("sonar.projectName", "Foo Project")
      .put("sonar.projectVersion", "1.0-SNAPSHOT")
      .put("sonar.projectDescription", "Description of Foo Project");
  }

  private ImmutableMap.Builder<String, String> createBuilder() {
    return ImmutableMap.<String, String>builder()
      .put("sonar.task", "scan")
      .put("sonar.verbose", "true")
      .put("sonar.projectBaseDir", baseDir.getAbsolutePath())
      .put("sonar.projectKey", "com.foo.project")
      .put("sonar.projectVersion", "1.0-SNAPSHOT")
      .put("sonar.projectDescription", "Description of Foo Project");
  }

  @Test
  public void scanProjectWithoutProjectName() throws IOException {
    builder = createBuilder();

    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    int ref = result.getReportReader().readMetadata().getRootComponentRef();
    assertThat(result.getReportReader().readComponent(ref).getName()).isEmpty();
    assertThat(result.inputFiles()).hasSize(1);

    DefaultInputFile file = (DefaultInputFile) result.inputFile("src/sample.xoo");
    assertThat(file.type()).isEqualTo(InputFile.Type.MAIN);
    assertThat(file.relativePath()).isEqualTo("src/sample.xoo");
    assertThat(file.language()).isEqualTo("xoo");

    // file was published, since language matched xoo
    assertThat(file.isPublished()).isTrue();
    assertThat(result.getReportComponent(file.scannerId())).isNotNull();
  }

  @Test
  public void logProjectKeyAndOrganizationKey() throws IOException {
    builder = createBuilder();
    builder.put("sonar.organization", "my org");
    builder.put("sonar.branch", "");
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(logTester.logs()).contains("Project key: com.foo.project");
    assertThat(logTester.logs()).contains("Organization key: my org");
    assertThat(logTester.logs().stream().collect(joining("\n"))).doesNotContain("Branch key");
  }

  @Test
  public void logBranchKey() throws IOException {
    builder = createBuilder();
    builder.put("sonar.branch", "my-branch");
    File srcDir = new File(baseDir, "src");
    assertThat(srcDir.mkdir()).isTrue();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(logTester.logs()).contains("Project key: com.foo.project");
    assertThat(logTester.logs()).contains("Branch key: my-branch");
    assertThat(logTester.logs()).contains("The use of \"sonar.branch\" is deprecated and replaced by \"sonar.branch.name\". See https://redirect.sonarsource.com/doc/branches.html.");
  }

  @Test
  public void logBranchNameAndType() throws IOException {
    builder = createBuilder();
    builder.put("sonar.branch.name", "my-branch");
    File srcDir = new File(baseDir, "src");
    assertThat(srcDir.mkdir()).isTrue();

    // Using sonar.branch.name when the branch plugin is not installed is an error.
    // IllegalStateException is expected here, because this test is in a bit artificial,
    // the fail-fast mechanism in the scanner should have prevented reaching this point.
    thrown.expect(IllegalStateException.class);

    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();
  }

  @Test
  public void dontLogInvalidOrganization() throws IOException {
    builder = createBuilder();
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(logTester.logs()).contains("Project key: com.foo.project");
    assertThat(logTester.logs().stream().collect(joining("\n"))).doesNotContain("Organization key");
    assertThat(logTester.logs().stream().collect(joining("\n"))).doesNotContain("Branch key");
  }

  @Test
  public void onlyGenerateMetadataIfNeeded() throws IOException {
    builder = createBuilder();

    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    File javaFile = new File(srcDir, "sample.java");
    FileUtils.write(javaFile, "Sample xoo\ncontent");

    logTester.setLevel(LoggerLevel.DEBUG);

    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(logTester.logs()).contains("2 files indexed");
    assertThat(logTester.logs()).contains("'src/sample.xoo' generated metadata with charset 'UTF-8'");
    assertThat(logTester.logs().stream().collect(joining("\n"))).doesNotContain("'src/sample.java' generated metadata");

  }

  @Test
  public void preloadFileMetadata() throws IOException {
    builder = createBuilder()
      .put("sonar.preloadFileMetadata", "true");

    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    File javaFile = new File(srcDir, "sample.java");
    FileUtils.write(javaFile, "Sample xoo\ncontent");

    logTester.setLevel(LoggerLevel.DEBUG);

    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(logTester.logs()).contains("2 files indexed");
    assertThat(logTester.logs()).contains("'src/sample.xoo' generated metadata with charset 'UTF-8'");
    assertThat(logTester.logs()).contains("'src/sample.java' generated metadata with charset 'UTF-8'");
  }

  @Test
  public void dontPublishFilesWithoutDetectedLanguage() throws IOException {
    builder = createBuilder();

    Path mainDir = baseDir.toPath().resolve("src").resolve("main");
    Files.createDirectories(mainDir);

    Path testDir = baseDir.toPath().resolve("src").resolve("test");
    Files.createDirectories(testDir);

    Path testXooFile = testDir.resolve("sample.java");
    Files.write(testXooFile, "Sample xoo\ncontent".getBytes(StandardCharsets.UTF_8));

    Path xooFile = mainDir.resolve("sample.xoo");
    Files.write(xooFile, "Sample xoo\ncontent".getBytes(StandardCharsets.UTF_8));

    Path javaFile = mainDir.resolve("sample.java");
    Files.write(javaFile, "Sample xoo\ncontent".getBytes(StandardCharsets.UTF_8));

    logTester.setLevel(LoggerLevel.DEBUG);

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src/main")
        .put("sonar.tests", "src/test")
        .build())
      .execute();

    assertThat(logTester.logs()).contains("3 files indexed");
    assertThat(logTester.logs()).contains("'src/main/sample.xoo' generated metadata with charset 'UTF-8'");
    assertThat(logTester.logs().stream().collect(joining("\n"))).doesNotContain("'src/main/sample.java' generated metadata");
    assertThat(logTester.logs().stream().collect(joining("\n"))).doesNotContain("'src/test/sample.java' generated metadata");
    DefaultInputFile javaInputFile = (DefaultInputFile) result.inputFile("src/main/sample.java");

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Unable to find report for component");

    result.getReportComponent(javaInputFile);
  }

  @Test
  public void createIssueOnAnyFile() throws IOException {
    tester
      .addRules(new XooRulesDefinition())
      .addActiveRule("xoo", "OneIssuePerUnknownFile", null, "OneIssuePerUnknownFile", "MAJOR", null, "xoo");

    builder = createBuilder();

    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.unknown");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    logTester.setLevel(LoggerLevel.DEBUG);

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(logTester.logs()).contains("1 file indexed");
    assertThat(logTester.logs()).contains("'src" + File.separator + "sample.unknown' indexed with language 'null'");
    assertThat(logTester.logs()).contains("'src/sample.unknown' generated metadata with charset 'UTF-8'");
    DefaultInputFile inputFile = (DefaultInputFile) result.inputFile("src/sample.unknown");
    assertThat(result.getReportComponent(inputFile)).isNotNull();
  }

  @Test
  public void lazyIssueExclusion() throws IOException {
    tester
      .addRules(new XooRulesDefinition())
      .addActiveRule("xoo", "OneIssuePerFile", null, "OneIssuePerFile", "MAJOR", null, "xoo");

    builder = createBuilder();
    builder.put("sonar.issue.ignore.allfile", "1")
      .put("sonar.issue.ignore.allfile.1.fileRegexp", "pattern");
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    File unknownFile = new File(srcDir, "myfile.binary");
    byte[] b = new byte[512];
    new Random().nextBytes(b);
    FileUtils.writeByteArrayToFile(unknownFile, b);

    logTester.setLevel(LoggerLevel.DEBUG);

    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(logTester.logs()).containsOnlyOnce("'src" + File.separator + "myfile.binary' indexed with language 'null'");
    assertThat(logTester.logs()).doesNotContain("'src/myfile.binary' generating issue exclusions");
    assertThat(logTester.logs()).containsOnlyOnce("'src/sample.xoo' generating issue exclusions");
  }

  @Test
  public void preloadIssueExclusions() throws IOException {
    builder = createBuilder();
    builder.put("sonar.issue.ignore.allfile", "1")
      .put("sonar.issue.ignore.allfile.1.fileRegexp", "pattern")
      .put("sonar.preloadFileMetadata", "true");
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\npattern");

    File unknownFile = new File(srcDir, "myfile.binary");
    FileUtils.write(unknownFile, "some text");

    logTester.setLevel(LoggerLevel.DEBUG);

    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(logTester.logs()).containsOnlyOnce("- Exclusion pattern 'pattern': every issue in this file will be ignored.");
    assertThat(logTester.logs()).containsOnlyOnce("'src/myfile.binary' generating issue exclusions");
  }

  @Test
  public void publishFilesWithIssues() throws IOException {
    tester
      .addRules(new XooRulesDefinition())
      .addActiveRule("xoo", "OneIssueOnDirPerFile", null, "OneIssueOnDirPerFile", "MAJOR", null, "xoo");

    builder = createBuilder();

    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    DefaultInputFile file = (DefaultInputFile) result.inputFile("src/sample.xoo");

    assertThat(file.isPublished()).isTrue();
    assertThat(result.getReportComponent(file)).isNotNull();
  }

  @Test
  public void scanProjectWithSourceDir() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(result.inputFiles()).hasSize(1);
    assertThat(result.inputFile("src/sample.xoo").type()).isEqualTo(InputFile.Type.MAIN);
    assertThat(result.inputFile("src/sample.xoo").relativePath()).isEqualTo("src/sample.xoo");
  }

  @Test
  public void scanBigProject() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    int nbFiles = 100;
    int ruleCount = 100000;
    for (int nb = 1; nb <= nbFiles; nb++) {
      File xooFile = new File(srcDir, "sample" + nb + ".xoo");
      FileUtils.write(xooFile, StringUtils.repeat(StringUtils.repeat("a", 100) + "\n", ruleCount / 1000));
    }

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(result.inputFiles()).hasSize(100);
  }

  @Test
  public void scanProjectWithTestDir() throws IOException {
    File test = new File(baseDir, "test");
    test.mkdir();

    File xooFile = new File(test, "sampleTest.xoo");
    FileUtils.write(xooFile, "Sample test xoo\ncontent");

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "")
        .put("sonar.tests", "test")
        .build())
      .execute();

    assertThat(result.inputFiles()).hasSize(1);
    assertThat(result.inputFile("test/sampleTest.xoo").type()).isEqualTo(InputFile.Type.TEST);
  }

  /**
   * SONAR-5419
   */
  @Test
  public void scanProjectWithMixedSourcesAndTests() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    File xooFile2 = new File(baseDir, "another.xoo");
    FileUtils.write(xooFile2, "Sample xoo 2\ncontent");

    File testDir = new File(baseDir, "test");
    testDir.mkdir();

    File xooTestFile = new File(baseDir, "sampleTest2.xoo");
    FileUtils.write(xooTestFile, "Sample test xoo\ncontent");

    File xooTestFile2 = new File(testDir, "sampleTest.xoo");
    FileUtils.write(xooTestFile2, "Sample test xoo 2\ncontent");

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src,another.xoo")
        .put("sonar.tests", "test,sampleTest2.xoo")
        .build())
      .execute();

    assertThat(result.inputFiles()).hasSize(4);
  }

  @Test
  public void fileInclusionsExclusions() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    File xooFile2 = new File(baseDir, "another.xoo");
    FileUtils.write(xooFile2, "Sample xoo 2\ncontent");

    File testDir = new File(baseDir, "test");
    testDir.mkdir();

    File xooTestFile = new File(baseDir, "sampleTest2.xoo");
    FileUtils.write(xooTestFile, "Sample test xoo\ncontent");

    File xooTestFile2 = new File(testDir, "sampleTest.xoo");
    FileUtils.write(xooTestFile2, "Sample test xoo 2\ncontent");

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src,another.xoo")
        .put("sonar.tests", "test,sampleTest2.xoo")
        .put("sonar.inclusions", "src/**")
        .put("sonar.exclusions", "**/another.*")
        .put("sonar.test.inclusions", "**/sampleTest*.*")
        .put("sonar.test.exclusions", "**/sampleTest2.xoo")
        .build())
      .execute();

    assertThat(result.inputFiles()).hasSize(2);
  }

  @Test
  public void warn_user_for_outdated_inherited_exclusions_for_multi_module_project() throws IOException {

    File baseDir = temp.getRoot();
    File baseDirModuleA = new File(baseDir, "moduleA");
    File baseDirModuleB = new File(baseDir, "moduleB");
    File srcDirA = new File(baseDirModuleA, "src");
    srcDirA.mkdirs();
    File srcDirB = new File(baseDirModuleB, "src");
    srcDirB.mkdirs();

    File xooFileA = new File(srcDirA, "sample.xoo");
    FileUtils.write(xooFileA, "Sample xoo\ncontent", StandardCharsets.UTF_8);

    File xooFileB = new File(srcDirB, "sample.xoo");
    FileUtils.write(xooFileB, "Sample xoo\ncontent", StandardCharsets.UTF_8);

    AnalysisResult result = tester.newAnalysis()
      .properties(ImmutableMap.<String, String>builder()
        .put("sonar.task", "scan")
        .put("sonar.projectBaseDir", baseDir.getAbsolutePath())
        .put("sonar.projectKey", "com.foo.project")
        .put("sonar.sources", "src")
        .put("sonar.modules", "moduleA,moduleB")
        .put("sonar.exclusions", "src/sample.xoo")
        .build())
      .execute();

    InputFile fileA = result.inputFile("moduleA/src/sample.xoo");
    assertThat(fileA).isNull();

    InputFile fileB = result.inputFile("moduleB/src/sample.xoo");
    assertThat(fileB).isNull();

    assertThat(logTester.logs(LoggerLevel.WARN))
      .contains("File 'moduleB/src/sample.xoo' was excluded because patterns are still evaluated using module relative paths but this is deprecated. " +
      "Please update file inclusion/exclusion configuration so that patterns refer to project relative paths.");
  }

  @Test
  public void warn_user_for_outdated_module_exclusions_for_multi_module_project() throws IOException {

    File baseDir = temp.getRoot();
    File baseDirModuleA = new File(baseDir, "moduleA");
    File baseDirModuleB = new File(baseDir, "moduleB");
    File srcDirA = new File(baseDirModuleA, "src");
    srcDirA.mkdirs();
    File srcDirB = new File(baseDirModuleB, "src");
    srcDirB.mkdirs();

    File xooFileA = new File(srcDirA, "sample.xoo");
    FileUtils.write(xooFileA, "Sample xoo\ncontent", StandardCharsets.UTF_8);

    File xooFileB = new File(srcDirB, "sample.xoo");
    FileUtils.write(xooFileB, "Sample xoo\ncontent", StandardCharsets.UTF_8);

    AnalysisResult result = tester.newAnalysis()
      .properties(ImmutableMap.<String, String>builder()
        .put("sonar.task", "scan")
        .put("sonar.projectBaseDir", baseDir.getAbsolutePath())
        .put("sonar.projectKey", "com.foo.project")
        .put("sonar.sources", "src")
        .put("sonar.modules", "moduleA,moduleB")
        .put("moduleB.sonar.exclusions", "src/sample.xoo")
        .build())
      .execute();

    InputFile fileA = result.inputFile("moduleA/src/sample.xoo");
    assertThat(fileA).isNotNull();

    InputFile fileB = result.inputFile("moduleB/src/sample.xoo");
    assertThat(fileB).isNull();

    assertThat(logTester.logs(LoggerLevel.WARN))
      .contains("Defining inclusion/exclusions at module level is deprecated. " +
        "Move file inclusion/exclusion configuration from module 'moduleB' to the root project and update patterns to refer to project relative paths.");
  }

  @Test
  public void failForDuplicateInputFile() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    thrown.expect(MessageException.class);
    thrown.expectMessage("File src/sample.xoo can't be indexed twice. Please check that inclusion/exclusion patterns produce disjoint sets for main and test files");
    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src,src/sample.xoo")
        .build())
      .execute();
  }

  // SONAR-9574
  @Test
  public void failForDuplicateInputFileInDifferentModules() throws IOException {
    File srcDir = new File(baseDir, "module1/src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    thrown.expect(MessageException.class);
    thrown.expectMessage("File module1/src/sample.xoo can't be indexed twice. Please check that inclusion/exclusion patterns produce disjoint sets for main and test files");
    tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "module1/src")
        .put("sonar.modules", "module1")
        .put("module1.sonar.sources", "src")
        .build())
      .execute();

  }

  // SONAR-5330
  @Test
  public void scanProjectWithSourceSymlink() {
    assumeTrue(!System2.INSTANCE.isOsWindows());
    File projectDir = new File("test-resources/mediumtest/xoo/sample-with-symlink");
    AnalysisResult result = tester
      .newAnalysis(new File(projectDir, "sonar-project.properties"))
      .execute();

    assertThat(result.inputFiles()).hasSize(3);
    // check that symlink was not resolved to target
    assertThat(result.inputFiles()).extractingResultOf("path").toString().startsWith(projectDir.toString());
  }

  // SONAR-6719
  @Test
  public void scanProjectWithWrongCase() {
    // To please the quality gate, don't use assumeTrue, or the test will be reported as skipped
    if (System2.INSTANCE.isOsWindows()) {
      File projectDir = new File("test-resources/mediumtest/xoo/sample");
      AnalysisResult result = tester
        .newAnalysis(new File(projectDir, "sonar-project.properties"))
        .property("sonar.sources", "XOURCES")
        .property("sonar.tests", "TESTX")
        .execute();

      assertThat(result.inputFiles()).hasSize(3);
      assertThat(result.inputFiles()).extractingResultOf("relativePath").containsOnly(
        "xources/hello/HelloJava.xoo",
        "xources/hello/helloscala.xoo",
        "testx/ClassOneTest.xoo");
    }
  }

  @Test
  public void indexAnyFile() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    File otherFile = new File(srcDir, "sample.other");
    FileUtils.write(otherFile, "Sample other\ncontent");

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(result.inputFiles()).hasSize(2);
    assertThat(result.inputFile("src/sample.other").type()).isEqualTo(InputFile.Type.MAIN);
    assertThat(result.inputFile("src/sample.other").relativePath()).isEqualTo("src/sample.other");
    assertThat(result.inputFile("src/sample.other").language()).isNull();
  }

  @Test
  public void scanMultiModuleProject() {
    File projectDir = new File("test-resources/mediumtest/xoo/multi-modules-sample");
    AnalysisResult result = tester
      .newAnalysis(new File(projectDir, "sonar-project.properties"))
      .execute();

    assertThat(result.inputFiles()).hasSize(4);
  }

  @Test
  public void global_sensor_should_see_project_relative_paths() {
    File projectDir = new File("test-resources/mediumtest/xoo/multi-modules-sample");
    AnalysisResult result = tester
      .newAnalysis(new File(projectDir, "sonar-project.properties"))
      .property(GlobalSensor.ENABLE_PROP, "true")
      .execute();

    assertThat(result.inputFiles()).hasSize(4);
    assertThat(logTester.logs(LoggerLevel.INFO)).contains(
      "Global Sensor: module_a/module_a1/src/main/xoo/com/sonar/it/samples/modules/a1/HelloA1.xoo",
      "Global Sensor: module_a/module_a2/src/main/xoo/com/sonar/it/samples/modules/a2/HelloA2.xoo",
      "Global Sensor: module_b/module_b1/src/main/xoo/com/sonar/it/samples/modules/b1/HelloB1.xoo",
      "Global Sensor: module_b/module_b2/src/main/xoo/com/sonar/it/samples/modules/b2/HelloB2.xoo");
  }

  @Test
  public void scanProjectWithCommaInSourcePath() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample,1.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    File xooFile2 = new File(baseDir, "another,2.xoo");
    FileUtils.write(xooFile2, "Sample xoo 2\ncontent");

    File testDir = new File(baseDir, "test");
    testDir.mkdir();

    File xooTestFile = new File(testDir, "sampleTest,1.xoo");
    FileUtils.write(xooTestFile, "Sample test xoo\ncontent");

    File xooTestFile2 = new File(baseDir, "sampleTest,2.xoo");
    FileUtils.write(xooTestFile2, "Sample test xoo 2\ncontent");

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src,\"another,2.xoo\"")
        .put("sonar.tests", "\"test\",\"sampleTest,2.xoo\"")
        .build())
      .execute();

    assertThat(result.inputFiles()).hasSize(4);
  }

  @Test
  public void twoLanguagesWithSameExtension() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "Sample xoo\ncontent");

    File xooFile2 = new File(srcDir, "sample.xoo2");
    FileUtils.write(xooFile2, "Sample xoo 2\ncontent");

    AnalysisResult result = tester.newAnalysis()
      .properties(builder
        .put("sonar.sources", "src")
        .build())
      .execute();

    assertThat(result.inputFiles()).hasSize(2);

    try {
      result = tester.newAnalysis()
        .properties(builder
          .put("sonar.lang.patterns.xoo2", "**/*.xoo")
          .build())
        .execute();
    } catch (Exception e) {
      assertThat(e)
        .isInstanceOf(MessageException.class)
        .hasMessage(
          "Language of file 'src" + File.separator
            + "sample.xoo' can not be decided as the file matches patterns of both sonar.lang.patterns.xoo : **/*.xoo and sonar.lang.patterns.xoo2 : **/*.xoo");
    }

    // SONAR-9561
    result = tester.newAnalysis()
      .properties(builder
        .put("sonar.exclusions", "**/sample.xoo")
        .build())
      .execute();

    assertThat(result.inputFiles()).hasSize(1);
  }

}
