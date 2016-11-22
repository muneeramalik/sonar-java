/*
 * SonarQube Java
 * Copyright (C) 2012-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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
package org.sonar.java.se;

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.fest.assertions.Fail;
import org.sonar.java.AnalyzerMessage;
import org.sonar.java.ast.JavaAstScanner;
import org.sonar.java.model.VisitorsBridgeForTests;
import org.sonar.plugins.java.api.JavaFileScanner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.partitioningBy;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;
import static org.sonar.java.se.Expectations.IssueAttribute.EFFORT_TO_FIX;
import static org.sonar.java.se.Expectations.IssueAttribute.END_COLUMN;
import static org.sonar.java.se.Expectations.IssueAttribute.END_LINE;
import static org.sonar.java.se.Expectations.IssueAttribute.FLOWS;
import static org.sonar.java.se.Expectations.IssueAttribute.MESSAGE;
import static org.sonar.java.se.Expectations.IssueAttribute.SECONDARY_LOCATIONS;
import static org.sonar.java.se.Expectations.IssueAttribute.START_COLUMN;

/**
 * It is possible to specify the absolute line number on which the issue should appear by appending {@literal "@<line>"} to "Noncompliant".
 * But usually better to use line number relative to the current, this is possible to do by prefixing the number with either '+' or '-'.
 * For example:
 * <pre>
 *   // Noncompliant@+1 {{do not import "java.util.List"}}
 *   import java.util.List;
 * </pre>
 * Full syntax:
 * <pre>
 *   // Noncompliant@+1 [[startColumn=1;endLine=+1;endColumn=2;effortToFix=4;secondary=3,4]] {{issue message}}
 * </pre>
 * Attributes between [[]] are optional:
 * <ul>
 *   <li>startColumn: column where the highlight starts</li>
 *   <li>endLine: relative endLine where the highlight ends (i.e. +1), same line if omitted</li>
 *   <li>endColumn: column where the highlight ends</li>
 *   <li>effortToFix: the cost to fix as integer</li>
 *   <li>secondary: a comma separated list of integers identifying the lines of secondary locations if any</li>
 * </ul>
 */
@Beta
public class JavaCheckVerifier {

  /**
   * Default location of the jars/zips to be taken into account when performing the analysis.
   */
  private static final String DEFAULT_TEST_JARS_DIRECTORY = "target/test-jars";
  private final String testJarsDirectory;
  private final Expectations expectations;

  private JavaCheckVerifier() {
    this.testJarsDirectory = DEFAULT_TEST_JARS_DIRECTORY;
    this.expectations = new Expectations();
  }

  public JavaCheckVerifier(Expectations expectations) {
    this(DEFAULT_TEST_JARS_DIRECTORY, expectations);
  }

  public JavaCheckVerifier(String testJarsDirectory, Expectations expectations) {
    this.testJarsDirectory = testJarsDirectory;
    this.expectations = expectations;
  }

  /**
   * Verifies that the provided file will raise all the expected issues when analyzed with the given check.
   *
   * <br /><br />
   *
   * By default, any jar or zip archive present in the folder defined by {@link JavaCheckVerifier#DEFAULT_TEST_JARS_DIRECTORY} will be used
   * to add extra classes to the classpath. If this folder is empty or does not exist, then the analysis will be based on the source of
   * the provided file.
   *
   * @param filename The file to be analyzed
   * @param check The check to be used for the analysis
   */
  public static void verify(String filename, JavaFileScanner... check) {
    new JavaCheckVerifier().scanFile(filename, check);
  }

  /**
   * Verifies that the provided file will raise all the expected issues when analyzed with the given check,
   * but using having the classpath extended with a collection of files (classes/jar/zip).
   *
   * @param filename The file to be analyzed
   * @param check The check to be used for the analysis
   * @param classpath The files to be used as classpath
   */
  public static void verify(String filename, JavaFileScanner check, Collection<File> classpath) {
    new JavaCheckVerifier().scanFile(filename, new JavaFileScanner[] {check}, classpath);
  }

  /**
   * Verifies that the provided file will raise all the expected issues when analyzed with the given check,
   * using jars/zips files from the given directory to extends the classpath.
   *
   * @param filename The file to be analyzed
   * @param check The check to be used for the analysis
   * @param testJarsDirectory The directory containing jars and/or zip defining the classpath to be used
   */
  public static void verify(String filename, JavaFileScanner check, String testJarsDirectory) {
    JavaCheckVerifier javaCheckVerifier = new JavaCheckVerifier(testJarsDirectory, new Expectations());
    javaCheckVerifier.scanFile(filename, new JavaFileScanner[] {check});
  }

  /**
   * Verifies that the provided file will not raise any issue when analyzed with the given check.
   *
   * @param filename The file to be analyzed
   * @param check The check to be used for the analysis
   */
  public static void verifyNoIssue(String filename, JavaFileScanner check) {
    JavaCheckVerifier javaCheckVerifier = new JavaCheckVerifier(new Expectations(true, null, null));
    javaCheckVerifier.scanFile(filename, new JavaFileScanner[] {check});
  }

  /**
   * Verifies that the provided file will only raise an issue on the file, with the given message, when analyzed using the given check.
   *
   * @param filename The file to be analyzed
   * @param message The message expected to be raised on the file
   * @param check The check to be used for the analysis
   */
  public static void verifyIssueOnFile(String filename, String message, JavaFileScanner check) {
    JavaCheckVerifier javaCheckVerifier = new JavaCheckVerifier(new Expectations(false, message, null));
    javaCheckVerifier.scanFile(filename, new JavaFileScanner[] {check});
  }

  private void scanFile(String filename, JavaFileScanner[] checks) {
    Collection<File> classpath = Lists.newLinkedList();
    File testJars = new File(testJarsDirectory);
    if (testJars.exists()) {
      classpath = FileUtils.listFiles(testJars, new String[] {"jar", "zip"}, true);
    } else if (!DEFAULT_TEST_JARS_DIRECTORY.equals(testJarsDirectory)) {
      fail("The directory to be used to extend class path does not exists (" + testJars.getAbsolutePath() + ").");
    }
    classpath.add(new File("target/test-classes"));
    scanFile(filename, checks, classpath);
  }

  private void scanFile(String filename, JavaFileScanner[] checks, Collection<File> classpath) {
    List<JavaFileScanner> visitors = new ArrayList<>(Arrays.asList(checks));
    visitors.add(expectations.parser());
    VisitorsBridgeForTests visitorsBridge = new VisitorsBridgeForTests(visitors, Lists.newArrayList(classpath), null);
    JavaAstScanner.scanSingleFileForTests(new File(filename), visitorsBridge);
    VisitorsBridgeForTests.TestJavaFileScannerContext testJavaFileScannerContext = visitorsBridge.lastCreatedTestContext();
    checkIssues(testJavaFileScannerContext.getIssues());
  }

  private void checkIssues(Set<AnalyzerMessage> issues) {
    if (expectations.expectNoIssues) {
      assertNoIssues(expectations.expected, issues);
    } else if (StringUtils.isNotEmpty(expectations.expectFileIssue)) {
      assertSingleIssue(expectations.expectFileIssueOnLine, expectations.expectFileIssue, issues);
    } else {
      assertMultipleIssue(expectations, expectations.expected, issues);
    }
  }

  private static void assertMultipleIssue(Expectations expectations, Multimap<Integer, Map<Expectations.IssueAttribute, String>> expected,
    Set<AnalyzerMessage> issues) throws AssertionError {
    Preconditions.checkState(!issues.isEmpty(), "At least one issue expected");
    List<Integer> unexpectedLines = Lists.newLinkedList();
    for (AnalyzerMessage issue : issues) {
      validateIssue(expectations, expected, unexpectedLines, issue);
    }
    if (!expected.isEmpty() || !unexpectedLines.isEmpty()) {
      Collections.sort(unexpectedLines);
      String expectedMsg = !expected.isEmpty() ? ("Expected " + expected) : "";
      String unexpectedMsg = !unexpectedLines.isEmpty() ? ((expectedMsg.isEmpty() ? "" : ", ") + "Unexpected at " + unexpectedLines) : "";
      fail(expectedMsg + unexpectedMsg);
    }
  }

  private static void validateIssue(Expectations expectations, Multimap<Integer, Map<Expectations.IssueAttribute, String>> expected,
    List<Integer> unexpectedLines, AnalyzerMessage issue) {
    int line = issue.getLine();
    if (expected.containsKey(line)) {
      Map<Expectations.IssueAttribute, String> attrs = Iterables.getLast(expected.get(line));
      assertAttributeMatch(issue, attrs, MESSAGE);
      validateAnalyzerMessageAttributes(expectations, attrs, issue);
      expected.remove(line, attrs);
    } else {
      unexpectedLines.add(line);
    }
  }

  private static void validateAnalyzerMessageAttributes(Expectations expectations, Map<Expectations.IssueAttribute, String> attrs,
    AnalyzerMessage analyzerMessage) {
    Double effortToFix = analyzerMessage.getCost();
    if (effortToFix != null) {
      assertAttributeMatch(Integer.toString(effortToFix.intValue()), attrs, EFFORT_TO_FIX);
    }
    validateLocation(attrs, analyzerMessage.primaryLocation());
    if (attrs.containsKey(SECONDARY_LOCATIONS)) {
      List<AnalyzerMessage> actual = analyzerMessage.flows.stream().map(l -> l.isEmpty() ? null : l.get(0)).filter(Objects::nonNull).collect(Collectors.toList());
      ArrayList<String> expected = Lists.newArrayList(Splitter.on(",").omitEmptyStrings().trimResults().split(attrs.get(SECONDARY_LOCATIONS)));
      validateSecondaryLocations(actual, expected);
    }
    if (attrs.containsKey(FLOWS)) {
      validateFlows(analyzerMessage.flows, expectations);
    }
  }

  private static void validateLocation(Map<Expectations.IssueAttribute, String> attrs, AnalyzerMessage.TextSpan textSpan) {
    assertAttributeMatch(normalizeColumn(textSpan.startCharacter), attrs, START_COLUMN);
    assertAttributeMatch(Integer.toString(textSpan.endLine), attrs, END_LINE);
    assertAttributeMatch(normalizeColumn(textSpan.endCharacter), attrs, END_COLUMN);
  }

  private static void validateFlows(List<List<AnalyzerMessage>> actual, Expectations expectations) {
    Set<String> foundFlowIds = new HashSet<>();
    Map<Boolean, List<List<AnalyzerMessage>>> partitionedFlows = actual.stream()
      .collect(partitioningBy(f -> expectations.containFlow(f, foundFlowIds)));

    List<List<AnalyzerMessage>> foundFlows = partitionedFlows.get(true);
    List<List<AnalyzerMessage>> unexpectedFlows = partitionedFlows.get(false);
    Sets.SetView<String> missingFlows = expectations.missingFlows(foundFlowIds);
    StringBuilder failMsg = new StringBuilder();
    if (!unexpectedFlows.isEmpty()) {
      failMsg.append(unexpectedFlows.stream().map(JavaCheckVerifier::flowToString).collect(joining("\n", "Unexpected flows: ", ". ")));
    }
    if (!missingFlows.isEmpty()) {
      failMsg.append(missingFlows.stream().collect(joining(",","Missing flows: ", ".")));
    }
    if (!missingFlows.isEmpty() || !unexpectedFlows.isEmpty()) {
      fail(failMsg.toString());
    }
  }

  private static String flowToString(List<AnalyzerMessage> flow) {
    return flow.stream().map(m -> String.valueOf(m.getLine())).collect(joining(",","[","]"));
  }

  private static void validateSecondaryLocations(List<AnalyzerMessage> actual, List<String> expected) {
    Multiset<String> actualLines = HashMultiset.create();
    actualLines.addAll(actual.stream().map(secondaryLocation -> Integer.toString(secondaryLocation.getLine())).collect(Collectors.toList()));
    List<String> unexpected = new ArrayList<>();
    for (String actualLine : actualLines) {
      if (expected.contains(actualLine)) {
        expected.remove(actualLine);
      } else {
        unexpected.add(actualLine);
      }
    }
    if (!expected.isEmpty() || !unexpected.isEmpty()) {
      fail("Secondary locations: expected: " + expected + " unexpected:" + unexpected);
    }
  }

  private static String normalizeColumn(int startCharacter) {
    return Integer.toString(startCharacter + 1);
  }

  private static void assertAttributeMatch(String value, Map<Expectations.IssueAttribute, String> attributes, Expectations.IssueAttribute attribute) {
    if (attributes.containsKey(attribute)) {
      assertThat(value).as("attribute mismatch for " + attribute + ": " + attributes).isEqualTo(attributes.get(attribute));
    }
  }

  private static void assertAttributeMatch(AnalyzerMessage issue, Map<Expectations.IssueAttribute, String> attributes, Expectations.IssueAttribute attribute) {
    if (attributes.containsKey(attribute)) {
      assertThat(issue.getMessage()).as("line " + issue.getLine() + " attribute mismatch for " + attribute + ": " + attributes).isEqualTo(attributes.get(attribute));
    }
  }

  private static void assertSingleIssue(Integer expectFileIssueOnline, String expectFileIssue, Set<AnalyzerMessage> issues) {
    Preconditions.checkState(issues.size() == 1, "A single issue is expected with line " + expectFileIssueOnline);
    AnalyzerMessage issue = Iterables.getFirst(issues, null);
    assertThat(issue.getLine()).isEqualTo(expectFileIssueOnline);
    assertThat(issue.getMessage()).isEqualTo(expectFileIssue);
  }

  private static void assertNoIssues(Multimap<Integer, Map<Expectations.IssueAttribute, String>> expected, Set<AnalyzerMessage> issues) {
    assertThat(issues).overridingErrorMessage("No issues expected but got: " + issues).isEmpty();
    // make sure we do not copy&paste verifyNoIssue call when we intend to call verify
    assertThat(expected.isEmpty()).overridingErrorMessage("The file should not declare noncompliants when no issues are expected").isTrue();
  }

}
