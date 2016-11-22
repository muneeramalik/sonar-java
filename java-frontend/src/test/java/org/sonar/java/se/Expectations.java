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

import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.fest.assertions.Fail;
import org.sonar.java.AnalyzerMessage;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.tree.SyntaxTrivia;
import org.sonar.plugins.java.api.tree.Tree;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.sonar.java.se.Expectations.IssueAttribute.EFFORT_TO_FIX;
import static org.sonar.java.se.Expectations.IssueAttribute.END_COLUMN;
import static org.sonar.java.se.Expectations.IssueAttribute.END_LINE;
import static org.sonar.java.se.Expectations.IssueAttribute.FLOWS;
import static org.sonar.java.se.Expectations.IssueAttribute.MESSAGE;
import static org.sonar.java.se.Expectations.IssueAttribute.SECONDARY_LOCATIONS;
import static org.sonar.java.se.Expectations.IssueAttribute.START_COLUMN;

class Expectations {

  private static final String NONCOMPLIANT_COMMENT = "// Noncompliant";
  private static final String FLOW_COMMENT = "// flow";

  private static final Map<String, IssueAttribute> ATTRIBUTE_MAP = ImmutableMap.<String, IssueAttribute>builder()
    .put("message", MESSAGE)
    .put("effortToFix", EFFORT_TO_FIX)
    .put("sc", START_COLUMN)
    .put("startColumn", START_COLUMN)
    .put("el", END_LINE)
    .put("endLine", END_LINE)
    .put("ec", END_COLUMN)
    .put("endColumn", END_COLUMN)
    .put("secondary", SECONDARY_LOCATIONS)
    .put("flows", FLOWS)
    .build();


  enum IssueAttribute {
    MESSAGE,
    START_COLUMN,
    END_COLUMN,
    END_LINE,
    EFFORT_TO_FIX,
    SECONDARY_LOCATIONS,
    FLOWS
  }

  static class FlowItem {
    String id;
    String msg;
    AnalyzerMessage.TextSpan textSpan;

    public FlowItem(String id, String msg, AnalyzerMessage.TextSpan textSpan) {
      this.id = id;
      this.msg = msg;
      this.textSpan = textSpan;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;
      FlowItem flowItem = (FlowItem) o;
      return Objects.equals(id, flowItem.id) &&
        Objects.equals(msg, flowItem.msg) &&
        Objects.equals(textSpan, flowItem.textSpan);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, msg, textSpan);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
        .add("id", id)
        .add("msg", msg)
        .add("textSpan", textSpan)
        .toString();
    }
  }

  final Multimap<Integer, Map<IssueAttribute, String>> expected = ArrayListMultimap.create();

  private final Multimap<Integer, AnalyzerMessage> issues = ArrayListMultimap.create();
  private final Multimap<String, FlowItem> flows = ArrayListMultimap.create();

  final boolean expectNoIssues;
  final String expectFileIssue;
  final Integer expectFileIssueOnLine;

  public Expectations() {
    this(false, null, null);
  }

  public Expectations(boolean expectNoIssues, @Nullable String expectFileIssue, @Nullable Integer expectFileIssueOnLine) {
    this.expectNoIssues = expectNoIssues;
    this.expectFileIssue = expectFileIssue;
    this.expectFileIssueOnLine = expectFileIssueOnLine;
  }

  private static String extractAttributes(String comment, Map<IssueAttribute, String> attr) {
    String attributesSubstr = StringUtils.substringBetween(comment, "[[", "]]");
    if (!StringUtils.isEmpty(attributesSubstr)) {
      Iterable<String> attributes = Splitter.on(";").split(attributesSubstr);
      for (String attribute : attributes) {
        String[] split = StringUtils.split(attribute, '=');
        if (split.length == 2 && ATTRIBUTE_MAP.containsKey(split[0])) {
          attr.put(ATTRIBUTE_MAP.get(split[0]), split[1]);
        } else {
          Fail.fail("// Noncompliant attributes not valid: " + attributesSubstr);
        }
      }
    }
    return attributesSubstr;
  }

  private static void updateEndLine(int expectedLine, EnumMap<IssueAttribute, String> attr) {
    if (attr.containsKey(END_LINE)) {
      String endLineStr = attr.get(END_LINE);
      if (endLineStr.startsWith("+")) {
        int endLine = Integer.parseInt(endLineStr);
        attr.put(END_LINE, Integer.toString(expectedLine + endLine));
      } else {
        Fail.fail("endLine attribute should be relative to the line and must be +N with N integer");
      }
    }
  }

  boolean containFlow(List<AnalyzerMessage> flow, Set<String> foundFlowIds) {
    // TODO make this faster by precomputing lines of expected flows
    int[] actualLines = flow.stream().mapToInt(AnalyzerMessage::getLine).sorted().toArray();
    for (Collection<FlowItem> expectedFlow : flows.asMap().values()) {
      int[] flowLines = expectedFlow.stream().mapToInt(f -> f.textSpan.startLine).sorted().toArray();
      if (Arrays.equals(actualLines, flowLines)) {
        expectedFlow.stream().findFirst().ifPresent(f -> foundFlowIds.add(f.id));
        return true;
      }
    }
    return false;
  }

  int flowCount() {
    return flows.asMap().size();
  }

  Sets.SetView<String> missingFlows(Set<String> foundFlowIds) {
    return Sets.difference(flows.asMap().keySet(), foundFlowIds);
  }

  IssuableSubscriptionVisitor parser() {
    return new Parser();
  }

  private class Parser extends IssuableSubscriptionVisitor {
    @Override
    public List<Tree.Kind> nodesToVisit() {
      return ImmutableList.of(Tree.Kind.TRIVIA);
    }

    @Override
    public void visitTrivia(SyntaxTrivia syntaxTrivia) {
      collectExpectedIssues(syntaxTrivia.comment(), syntaxTrivia.startLine());
    }

    private void collectExpectedIssues(String comment, int line) {
      if (comment.startsWith(NONCOMPLIANT_COMMENT)) {
        parseIssue(comment, line);
      }
      if (comment.startsWith(FLOW_COMMENT)) {
        parseFlow(comment, line);
      }
    }

    private void parseFlow(String comment, int line) {
      int atIdx = comment.indexOf('@');
      int followingSpaceIdx = comment.indexOf(' ', atIdx);
      String flowId = comment.substring(atIdx + 1, followingSpaceIdx);
      // TODO parse msg and textspan
      FlowItem flowItem = new FlowItem(flowId, "", new AnalyzerMessage.TextSpan(line, 0, line, 0));
      flows.put(flowId, flowItem);
    }

    private void parseIssue(String comment, int line) {
      String cleanedComment = StringUtils.remove(comment, NONCOMPLIANT_COMMENT);

      EnumMap<IssueAttribute, String> attr = new EnumMap<>(IssueAttribute.class);
      String expectedMessage = StringUtils.substringBetween(cleanedComment, "{{", "}}");
      if (StringUtils.isNotEmpty(expectedMessage)) {
        attr.put(MESSAGE, expectedMessage);
      }
      int expectedLine = line;
      String attributesSubstr = extractAttributes(comment, attr);

      cleanedComment = StringUtils.stripEnd(StringUtils.remove(StringUtils.remove(cleanedComment, "[[" + attributesSubstr + "]]"), "{{" + expectedMessage + "}}"), " \t");
      if (StringUtils.startsWith(cleanedComment, "@")) {
        final int lineAdjustment;
        final char firstChar = cleanedComment.charAt(1);
        final int endIndex = cleanedComment.indexOf(' ');
        if (endIndex == -1) {
          lineAdjustment = Integer.parseInt(cleanedComment.substring(2));
        } else {
          lineAdjustment = Integer.parseInt(cleanedComment.substring(2, endIndex));
        }
        if (firstChar == '+') {
          expectedLine += lineAdjustment;
        } else if (firstChar == '-') {
          expectedLine -= lineAdjustment;
        } else {
          Fail.fail("Use only '@+N' or '@-N' to shifts messages.");
        }
      }
      updateEndLine(expectedLine, attr);
      expected.put(expectedLine, attr);
    }
  }

}
