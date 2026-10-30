package io.instanto.teavm.coverage.maven;

import static org.junit.Assert.*;

import java.util.Properties;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.junit.Test;

public class BrowserTestConfigurationTest {
  @Test
  public void removesOnlyJacocoAgentAndPreservesQuotedArguments() throws Exception {
    var properties = new Properties();
    properties.setProperty(
        "argLine",
        "\"-javaagent:/path with spaces/org.jacoco.agent-0.8.15-runtime.jar=destfile=x\"");
    String actual =
        BrowserTestConfiguration.withoutJacocoAgent(
            "@{argLine} -Xmx512m \"-Dlabel=two words\" -javaagent:/other/agent.jar", properties);
    assertArrayEquals(
        new String[] {"-Xmx512m", "-Dlabel=two words", "-javaagent:/other/agent.jar"},
        CommandLineUtils.translateCommandline(actual));
    assertTrue(properties.getProperty("argLine").contains("org.jacoco.agent"));
  }

  @Test
  public void acceptsAbsentAgentAndRetainsOtherLateProperties() throws Exception {
    assertEquals("", BrowserTestConfiguration.withoutJacocoAgent("${argLine}", new Properties()));
    assertEquals(
        "-Xmx512m @{other}",
        BrowserTestConfiguration.withoutJacocoAgent("-Xmx512m @{other}", new Properties()));
  }

  @Test
  public void scopesWiringToTheSelectedProjectLauncherAndExecution() {
    var request = new org.apache.maven.execution.DefaultMavenExecutionRequest();
    var events = new java.util.concurrent.atomic.AtomicInteger();
    request.setExecutionListener(
        new org.apache.maven.execution.AbstractExecutionListener() {
          @Override
          public void mojoStarted(org.apache.maven.execution.ExecutionEvent event) {
            events.incrementAndGet();
          }
        });
    var project = new org.apache.maven.project.MavenProject();
    project.setFile(new java.io.File("pom.xml"));
    var build = new org.apache.maven.model.Build();
    build.setDirectory("target");
    build.setOutputDirectory("target/classes");
    project.getModel().setBuild(build);
    project
        .getProperties()
        .setProperty("argLine", "-javaagent:/org.jacoco.agent-runtime.jar -Xmx512m");
    var session =
        new org.apache.maven.execution.MavenSession(
            null,
            new org.eclipse.aether.DefaultRepositorySystemSession(),
            request,
            new org.apache.maven.execution.DefaultMavenExecutionResult());
    session.setCurrentProject(project);
    session.setProjects(java.util.List.of(project));
    BrowserTestConfiguration.install(
        session,
        project,
        new BrowserTestConfiguration.Target(
            "failsafe",
            "browser",
            "jacoco",
            java.nio.file.Path.of("target/coverage"),
            java.util.List.of("src/main/java"),
            java.util.List.of()));
    for (String artifact : java.util.List.of("maven-surefire-plugin", "maven-failsafe-plugin")) {
      for (String id : java.util.List.of("ordinary", "browser")) {
        var plugin = new org.apache.maven.model.Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId(artifact);
        var execution =
            new org.apache.maven.plugin.MojoExecution(
                plugin, artifact.contains("surefire") ? "test" : "integration-test", id);
        var original = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
        execution.setConfiguration(original);
        var event = event(session, execution);
        request.getExecutionListener().mojoStarted(event);
        if (artifact.contains("failsafe") && id.equals("browser")) {
          assertNotSame(original, execution.getConfiguration());
          assertEquals(
              "target/coverage/jacoco/classes",
              execution.getConfiguration().getChild("classesDirectory").getValue());
          assertEquals(
              "true",
              execution
                  .getConfiguration()
                  .getChild("systemPropertyVariables")
                  .getChild("teavm.coverage.enabled")
                  .getValue());
          assertEquals("-Xmx512m", execution.getConfiguration().getChild("argLine").getValue());
          // The same launcher in another reactor module must remain unchanged.
          session.setCurrentProject(new org.apache.maven.project.MavenProject());
          execution.setConfiguration(original);
          request.getExecutionListener().mojoStarted(event(session, execution));
          assertSame(original, execution.getConfiguration());
          session.setCurrentProject(project);
        } else assertSame(original, execution.getConfiguration());
      }
    }
    assertEquals(5, events.get());
    assertEquals("target/classes", project.getBuild().getOutputDirectory());
    assertTrue(project.getProperties().getProperty("argLine").contains("org.jacoco.agent"));
  }

  private static org.apache.maven.execution.ExecutionEvent event(
      org.apache.maven.execution.MavenSession session,
      org.apache.maven.plugin.MojoExecution execution) {
    return new org.apache.maven.execution.ExecutionEvent() {
      public Type getType() {
        return Type.MojoStarted;
      }

      public org.apache.maven.execution.MavenSession getSession() {
        return session;
      }

      public org.apache.maven.project.MavenProject getProject() {
        return session.getCurrentProject();
      }

      public org.apache.maven.plugin.MojoExecution getMojoExecution() {
        return execution;
      }

      public Exception getException() {
        return null;
      }
    };
  }
}
