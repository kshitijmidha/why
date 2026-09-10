package com.why.explainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.why.explainer.model.Command;
import com.why.explainer.service.CommandParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Basic coverage for the deterministic parser: a mix of git and docker,
 * including one destructive command and one unknown command.
 */
@SpringBootTest
class CommandParserTest {

  @Autowired
  CommandParser parser;

  @Test
  void parsesGitResetSoft() {
    Command cmd = parser.parse("git reset --soft HEAD~1");

    assertEquals("git", cmd.tool());
    assertEquals("reset", cmd.subcommand());
    assertEquals(1, cmd.flags().size());
    assertEquals("--soft", cmd.flags().get(0).flag());
    assertTrue(cmd.flags().get(0).risk() == null);
    assertTrue(cmd.args().stream().anyMatch(a -> a.value().equals("HEAD~1")));
    assertTrue(!cmd.effects().isEmpty());
  }

  @Test
  void flagsDestructiveGitResetHard() {
    Command cmd = parser.parse("git reset --hard HEAD");

    assertEquals("reset", cmd.subcommand());
    assertEquals("destructive", cmd.flags().get(0).risk());
  }

  @Test
  void flagsDestructiveGitPushForce() {
    Command cmd = parser.parse("git push --force origin main");

    assertEquals("git", cmd.tool());
    assertEquals("push", cmd.subcommand());
    assertTrue(cmd.flags().stream().anyMatch(f -> f.flag().equals("--force")));
    assertTrue(cmd.flags().stream()
        .filter(f -> f.flag().equals("--force"))
        .allMatch(f -> "destructive".equals(f.risk())));
  }

  @Test
  void parsesDockerRunWithPublishAndDetach() {
    Command cmd = parser.parse("docker run -d -p 8080:80 nginx");

    assertEquals("docker", cmd.tool());
    assertEquals("run", cmd.subcommand());
    assertTrue(cmd.flags().stream().anyMatch(f -> f.flag().equals("-d")));
    assertTrue(cmd.flags().stream().anyMatch(f -> f.flag().equals("-p")));
    assertTrue(cmd.args().stream()
        .anyMatch(a -> a.value().equals("8080:80") && a.role().equals("port-mapping")));
    assertTrue(cmd.args().stream()
        .anyMatch(a -> a.value().equals("nginx") && a.role().equals("image")));
    assertTrue(!cmd.effects().isEmpty());
  }

  @Test
  void parsesDockerComposeUp() {
    Command cmd = parser.parse("docker compose up -d");

    assertEquals("docker", cmd.tool());
    assertEquals("compose up", cmd.subcommand());
    assertTrue(cmd.flags().stream().anyMatch(f -> f.flag().equals("-d")));
  }

  @Test
  void flagValuesGetSpecificRoles() {
    Command cmd = parser.parse("docker build -t myapp:1.0 .");

    assertEquals("build", cmd.subcommand());
    assertTrue(cmd.args().stream()
        .anyMatch(a -> a.value().equals("myapp:1.0") && a.role().equals("image-tag")));
    assertTrue(cmd.args().stream()
        .anyMatch(a -> a.value().equals(".") && a.role().equals("build-context")));
  }

  @Test
  void unknownCommandDoesNotCrash() {
    Command cmd = parser.parse("kubectl get pods");

    assertNotNull(cmd);
    assertEquals("kubectl", cmd.tool());
    assertEquals("unknown", cmd.subcommand());
    assertTrue(!cmd.effects().isEmpty());
  }

  @Test
  void unknownFlagDoesNotCrash() {
    Command cmd = parser.parse("git reset --frobnicate HEAD");

    assertEquals("reset", cmd.subcommand());
    assertTrue(cmd.flags().stream().anyMatch(f -> f.flag().equals("--frobnicate")));
    assertTrue(cmd.flags().stream()
        .filter(f -> f.flag().equals("--frobnicate"))
        .allMatch(f -> f.meaning().toLowerCase().contains("unknown")));
  }
  @Test
  void repeatedParseUsesCache() {
    int before = parser.parse("git stash").hashCode(); // warm up, value unused
    Command first = parser.parse("git stash -u");
    Command second = parser.parse("  git   stash -u  "); // same after normalization
    assertTrue(first == second, "Expected same cached instance for normalized duplicates.");
  }

  @Test
  void softResetExcludesHardEffects() {
    Command cmd = parser.parse("git reset --soft HEAD~1");

    assertTrue(cmd.effects().stream()
        .noneMatch(e -> e.change().contains("--hard")),
        "soft reset must not include any --hard-related effect, got: " + cmd.effects());
  }

  @Test
  void hardResetIncludesDestructiveWorkingTreeEffect() {
    Command cmd = parser.parse("git reset --hard HEAD");

    assertTrue(cmd.effects().stream()
        .anyMatch(e -> e.subsystem().equals("working-tree")
            && e.change().toLowerCase().contains("discard")),
        "hard reset must include the destructive working-tree effect, got: " + cmd.effects());
  }

  @Test
  void conditionalEffectsFollowUsedFlags() {
    Command withoutPublish = parser.parse("docker run nginx");
    assertTrue(withoutPublish.effects().stream()
        .noneMatch(e -> e.subsystem().equals("network")),
        "docker run without -p must not include the port-publishing effect.");

    Command withPublish = parser.parse("docker run -p 8080:80 nginx");
    assertTrue(withPublish.effects().stream()
        .anyMatch(e -> e.subsystem().equals("network")),
        "docker run with -p must include the port-publishing effect.");
  }
}
