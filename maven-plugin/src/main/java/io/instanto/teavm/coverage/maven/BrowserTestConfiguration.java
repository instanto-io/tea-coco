/*
 * Copyright 2026 Instanto contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0.
 */
package io.instanto.teavm.coverage.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.*;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Configures only this project's selected test executions, immediately before Mojo injection. */
final class BrowserTestConfiguration extends AbstractExecutionListener {
  private final ExecutionListener delegate;
  private final MavenProject project;
  private final String plugin;
  private final String executionId;
  private final String mode;
  private final Path directory;
  private final List<String> roots;
  private final List<String> runtime;

  private BrowserTestConfiguration(
      ExecutionListener delegate,
      MavenProject project,
      String plugin,
      String executionId,
      String mode,
      Path directory,
      List<String> roots,
      List<Artifact> artifacts) {
    this.delegate = delegate == null ? new AbstractExecutionListener() {} : delegate;
    this.project = project;
    this.plugin = plugin;
    this.executionId = executionId;
    this.mode = mode;
    this.directory = directory;
    this.roots = List.copyOf(roots);
    this.runtime =
        artifacts.stream()
            .map(Artifact::getFile)
            .filter(Objects::nonNull)
            .map(File::getAbsolutePath)
            .toList();
  }

  static void install(
      MavenSession session,
      MavenProject project,
      String plugin,
      String executionId,
      String mode,
      Path directory,
      List<String> roots,
      List<Artifact> artifacts) {
    // Reactor workers share the request. Each listener is restricted to its project and
    // delegates all events, preserving Maven's logging and other installed listeners.
    synchronized (session.getRequest()) {
      session
          .getRequest()
          .setExecutionListener(
              new BrowserTestConfiguration(
                  session.getRequest().getExecutionListener(),
                  project,
                  plugin,
                  executionId,
                  mode,
                  directory,
                  roots,
                  artifacts));
    }
  }

  @Override
  public void mojoStarted(ExecutionEvent event) {
    delegate.mojoStarted(event);
    var execution = event.getMojoExecution();
    if (event.getProject() != project
        || !"org.apache.maven.plugins".equals(execution.getGroupId())
        || !("maven-" + plugin + "-plugin").equals(execution.getArtifactId())
        || !(plugin.equals("surefire") ? "test" : "integration-test").equals(execution.getGoal())
        || (executionId != null && !executionId.equals(execution.getExecutionId()))) return;
    // Maven has already assembled its execution plan, so mutating the POM here would
    // be too late. Copy the selected execution's configuration before Maven injects it.
    var config =
        execution.getConfiguration() == null
            ? new Xpp3Dom("configuration")
            : new Xpp3Dom(execution.getConfiguration());
    try {
      var evaluator = new PluginParameterExpressionEvaluator(event.getSession(), execution);
      if ("jacoco".equals(mode)) {
        set(config, "classesDirectory", directory.resolve("jacoco/classes").toString());
        var args = config.getChild("argLine");
        String value = args == null ? "${argLine}" : args.getValue();
        Object evaluated = value == null ? null : evaluator.evaluate(value);
        set(
            config,
            "argLine",
            withoutJacocoAgent(
                evaluated == null ? "" : evaluated.toString(), project.getProperties()));
      }
      var properties = child(config, "systemPropertyVariables");
      set(properties, "teavm.coverage.enabled", "true");
      set(properties, "teavm.coverage.mode", mode);
      set(properties, "teavm.coverage.sourceRoots", String.join(File.pathSeparator, roots));
      set(properties, "teavm.coverage.dataDirectory", directory.toString());
      var classpath = child(config, "additionalClasspathElements");
      // A user-property expression may stand in for the whole array instead of children.
      if (classpath.getValue() != null) {
        Object value = evaluator.evaluate(classpath.getValue());
        if (value instanceof String text && !text.startsWith("${")) {
          for (String entry : text.split(",")) {
            if (!entry.isBlank()) {
              var item = new Xpp3Dom("additionalClasspathElement");
              item.setValue(entry.trim());
              classpath.addChild(item);
            }
          }
        }
        classpath.setValue(null);
      }
      var existing =
          Arrays.stream(classpath.getChildren()).map(Xpp3Dom::getValue).collect(Collectors.toSet());
      for (String path : runtime) {
        if (existing.add(path)) {
          var entry = new Xpp3Dom("additionalClasspathElement");
          entry.setValue(path);
          classpath.addChild(entry);
        }
      }
      set(config, "disableXmlReport", "false");
      set(config, "enableOutErrElements", "true");
      execution.setConfiguration(config);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Could not configure TeaCoCo browser tests: " + e.getMessage(), e);
    }
  }

  static String withoutJacocoAgent(String value, Properties properties) throws Exception {
    var matcher = Pattern.compile("@\\{([^}]+)}").matcher(value);
    var resolved = new StringBuffer();
    while (matcher.find()) {
      String replacement = properties.getProperty(matcher.group(1), matcher.group());
      matcher.appendReplacement(resolved, java.util.regex.Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(resolved);
    // An unset default argLine is represented by its expression in Maven's plan.
    if (resolved.toString().equals("${argLine}")) return "";
    String[] args = CommandLineUtils.translateCommandline(resolved.toString());
    var kept =
        Arrays.stream(args)
            .filter(
                arg ->
                    !(arg.startsWith("-javaagent:")
                        && (arg.contains("org.jacoco.agent") || arg.contains("jacocoagent.jar"))))
            .toArray(String[]::new);
    return kept.length == args.length ? resolved.toString() : CommandLineUtils.toString(kept);
  }

  private static Xpp3Dom child(Xpp3Dom parent, String name) {
    var node = parent.getChild(name);
    if (node == null) {
      node = new Xpp3Dom(name);
      parent.addChild(node);
    }
    return node;
  }

  private static void set(Xpp3Dom parent, String name, String value) {
    var node = child(parent, name);
    node.setValue(value);
    // Explicit values replace descriptor fallbacks.
    if (node.getAttribute("default-value") != null) node.removeAttribute("default-value");
  }

  @Override
  public void projectDiscoveryStarted(ExecutionEvent e) {
    delegate.projectDiscoveryStarted(e);
  }

  @Override
  public void sessionStarted(ExecutionEvent e) {
    delegate.sessionStarted(e);
  }

  @Override
  public void sessionEnded(ExecutionEvent e) {
    delegate.sessionEnded(e);
  }

  @Override
  public void projectSkipped(ExecutionEvent e) {
    delegate.projectSkipped(e);
  }

  @Override
  public void projectStarted(ExecutionEvent e) {
    delegate.projectStarted(e);
  }

  @Override
  public void projectSucceeded(ExecutionEvent e) {
    delegate.projectSucceeded(e);
  }

  @Override
  public void projectFailed(ExecutionEvent e) {
    delegate.projectFailed(e);
  }

  @Override
  public void mojoSkipped(ExecutionEvent e) {
    delegate.mojoSkipped(e);
  }

  @Override
  public void mojoSucceeded(ExecutionEvent e) {
    delegate.mojoSucceeded(e);
  }

  @Override
  public void mojoFailed(ExecutionEvent e) {
    delegate.mojoFailed(e);
  }

  @Override
  public void forkStarted(ExecutionEvent e) {
    delegate.forkStarted(e);
  }

  @Override
  public void forkSucceeded(ExecutionEvent e) {
    delegate.forkSucceeded(e);
  }

  @Override
  public void forkFailed(ExecutionEvent e) {
    delegate.forkFailed(e);
  }

  @Override
  public void forkedProjectStarted(ExecutionEvent e) {
    delegate.forkedProjectStarted(e);
  }

  @Override
  public void forkedProjectSucceeded(ExecutionEvent e) {
    delegate.forkedProjectSucceeded(e);
  }

  @Override
  public void forkedProjectFailed(ExecutionEvent e) {
    delegate.forkedProjectFailed(e);
  }
}
