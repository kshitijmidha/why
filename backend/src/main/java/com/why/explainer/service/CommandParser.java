package com.why.explainer.service;

import com.why.explainer.model.Arg;
import com.why.explainer.model.Command;
import com.why.explainer.model.Effect;
import com.why.explainer.model.Flag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Turns a raw shell-command string into a structured {@link Command}.
 *
 * <p>Steps: tokenize -&gt; identify tool/subcommand -&gt; match flags and args
 * against the {@link KnowledgeBase} -&gt; attach effects.
 *
 * <p>Unknown tools, subcommands, or flags never crash: they are returned
 * with an "unknown" meaning so the caller can show a helpful message.
 * Results are cached in memory by normalized command string.
 */
@Service
public class CommandParser {

  private final KnowledgeBase knowledgeBase;

  // Simple in-memory cache: normalized command -> parsed result.
  private final Map<String, Command> cache = new ConcurrentHashMap<>();

  public CommandParser(KnowledgeBase knowledgeBase) {
    this.knowledgeBase = knowledgeBase;
  }

  /** Parse a command, using the cache when the same command was seen before. */
  public Command parse(String rawCommand) {
    String normalized = normalize(rawCommand);
    if (normalized.isBlank()) {
      return unknownCommand("", "empty", "Empty command. Please type something like 'git reset --soft HEAD~1'.");
    }
    Command cached = cache.get(normalized);
    if (cached != null) {
      return cached;
    }
    Command parsed = parseFresh(normalized);
    cache.put(normalized, parsed);
    return parsed;
  }

  /** Collapse whitespace so "git  reset" and "git reset" share a cache entry. */
  public static String normalize(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.trim().replaceAll("\\s+", " ");
  }

  private Command parseFresh(String normalized) {
    List<String> tokens = tokenize(normalized);
    if (tokens.isEmpty()) {
      return unknownCommand("", "empty", "Empty command.");
    }

    String tool = tokens.get(0);

    // Unknown tool: still return a Command shaped object, marked unknown.
    if (!knowledgeBase.knownTools().contains(tool)) {
      List<Arg> args = new ArrayList<>();
      for (int i = 1; i < tokens.size(); i++) {
        args.add(new Arg(tokens.get(i), "operand"));
      }
      return new Command(
          tool,
          "unknown",
          List.of(),
          args,
          List.of(new Effect(
              "unknown",
              "Unknown tool '" + tool + "'. Currently supported: "
                  + String.join(", ", knowledgeBase.knownTools()) + ".",
              null)));
    }

    // Identify subcommand. "docker compose up/down" is two words.
    String subcommand = "unknown";
    int nextIndex = 1; // first token after tool/subcommand
    if (tool.equals("docker") && tokens.size() >= 3
        && tokens.get(1).equals("compose")
        && (tokens.get(2).equals("up") || tokens.get(2).equals("down"))) {
      subcommand = "compose " + tokens.get(2);
      nextIndex = 3;
    } else if (tokens.size() >= 2 && !tokens.get(1).startsWith("-")) {
      subcommand = tokens.get(1);
      nextIndex = 2;
    }

    KnowledgeBase.SubcommandInfo info = knowledgeBase.lookup(tool, subcommand);
    if (info == null) {
      // Unknown subcommand, but keep the leftover tokens as args.
      List<Arg> args = new ArrayList<>();
      for (int i = nextIndex; i < tokens.size(); i++) {
        String token = tokens.get(i);
        if (token.startsWith("-")) {
          continue; // flags of unknown subcommands are skipped; message below explains
        }
        args.add(new Arg(token, "operand"));
      }
      return new Command(
          tool,
          "unknown",
          List.of(),
          args,
          List.of(new Effect(
              "unknown",
              "Unknown " + tool + " subcommand '" + subcommand + "'. Known: "
                  + String.join(", ", knowledgeBase.subcommandsFor(tool)) + ".",
              null)));
    }

    List<Flag> flags = new ArrayList<>();
    List<Arg> args = new ArrayList<>();
    int positional = 0; // counts true positional args (flag values don't count)
    String expectValueFor = null; // a value-taking flag waiting for its value

    for (int i = nextIndex; i < tokens.size(); i++) {
      String token = tokens.get(i);
      // Support --flag=value form.
      String flagName = token.contains("=") && token.startsWith("-")
          ? token.substring(0, token.indexOf('='))
          : token;
      String inlineValue = token.contains("=") && token.startsWith("-")
          ? token.substring(token.indexOf('=') + 1)
          : null;

      if (expectValueFor != null && !flagName.startsWith("-")) {
        // The previous flag takes a value, e.g. "-p 8080:80" or "-t myapp:1.0".
        args.add(new Arg(token, roleForInlineValue(tool, subcommand, expectValueFor)));
        expectValueFor = null;
      } else if (flagName.startsWith("-")) {
        expectValueFor = null;
        KnowledgeBase.FlagInfo flagInfo = info.flags().get(flagName);
        if (flagInfo == null) {
          flags.add(new Flag(flagName, "Unknown flag (not in knowledge base).", null));
        } else {
          flags.add(new Flag(flagName, flagInfo.meaning(), flagInfo.risk()));
        }
        if (inlineValue != null && !inlineValue.isBlank()) {
          args.add(new Arg(inlineValue, roleForInlineValue(tool, subcommand, flagName)));
        } else if (takesValue(tool, subcommand, flagName)) {
          expectValueFor = flagName;
        }
      } else {
        args.add(new Arg(token, roleForArg(tool, subcommand, token, positional)));
        positional++;
      }
    }

    // Effects come from the knowledge base: baseline effects always apply,
    // conditional ones only when one of their trigger flags was passed.
    // (This used to include every effect for the subcommand, e.g. --hard
    // effects showing up for "--soft" — that was a bug.)
    List<String> usedFlags = flags.stream().map(Flag::flag).toList();
    List<Effect> relevant = new ArrayList<>();
    for (KnowledgeBase.ConditionalEffect candidate : info.effects()) {
      if (candidate.whenFlags().isEmpty()
          || candidate.whenFlags().stream().anyMatch(usedFlags::contains)) {
        relevant.add(candidate.effect());
      }
    }
    List<Effect> effects = withTarget(relevant, args);

    return new Command(tool, subcommand, flags, args, effects);
  }

  private List<Effect> withTarget(List<Effect> base, List<Arg> args) {
    if (base.isEmpty() || args.isEmpty()) {
      return List.copyOf(base);
    }
    String target = primaryTarget(args);
    List<Effect> out = new ArrayList<>();
    for (Effect e : base) {
      String t = e.target() == null ? target : e.target();
      out.add(new Effect(e.subsystem(), e.change(), t));
    }
    return out;
  }

  /**
   * The arg most effects act on. Flag values (e.g. "8080:80" from "-p")
   * are skipped so the main positional target (image, ref, ...) wins.
   */
  private String primaryTarget(List<Arg> args) {
    for (Arg a : args) {
      switch (a.role()) {
        case "port-mapping", "volume-mapping", "image-tag", "env-var",
            "message", "container-name" -> {
          continue;
        }
        default -> {
          return a.value();
        }
      }
    }
    return args.get(0).value();
  }

  private Command unknownCommand(String tool, String subcommand, String message) {
    return new Command(tool, subcommand, List.of(), List.of(),
        List.of(new Effect("unknown", message, null)));
  }

  /** Simple role guess so args are more useful than just "operand". */
  static String roleForArg(String tool, String subcommand, String value, int position) {
    if (tool.equals("git")) {
      if (value.equals("--")) {
        return "separator";
      }
      if (value.matches("HEAD(\\^|~.*)?") || value.matches("[0-9a-f]{7,40}")) {
        return "commit-ref";
      }
      if (value.contains("/") && value.contains(":")) {
        return "ref";
      }
      if (subcommand.equals("push") && position == 0) {
        return "remote";
      }
      if (subcommand.equals("push") && position == 1) {
        return "branch";
      }
      if (value.contains("/") || value.contains(".")) {
        return "path";
      }
      return "ref";
    }
    if (tool.equals("docker")) {
      if (value.equals(".")) {
        return "build-context";
      }
      if (subcommand.equals("exec") && position == 0) {
        return "container";
      }
      if (subcommand.equals("exec")) {
        return "command";
      }
      if (subcommand.equals("run") && position == 0) {
        return "image";
      }
      return "operand";
    }
    return "operand";
  }

  /**
   * Does this flag take a separate value token (e.g. "-p 8080:80")?
   * Kept explicit per subcommand so "-t" means image-tag for
   * "docker build" but stays a boolean terminal flag for "docker run".
   */
  static boolean takesValue(String tool, String subcommand, String flag) {
    return switch (flag) {
      case "-p", "--publish", "-v", "--volume", "--name",
          "-m", "--message", "-e", "--env", "-f", "--file",
          "-w", "--workdir" -> true;
      case "-t", "--tag" -> tool.equals("docker") && subcommand.equals("build");
      default -> false;
    };
  }

  private String roleForInlineValue(String tool, String subcommand, String flagName) {
    if (flagName.equals("--tag") || flagName.equals("-t")) {
      return "image-tag";
    }
    if (flagName.equals("--file") || flagName.equals("-f")) {
      return "path";
    }
    if (flagName.equals("--publish") || flagName.equals("-p")) {
      return "port-mapping";
    }
    if (flagName.equals("--volume") || flagName.equals("-v")) {
      return "volume-mapping";
    }
    if (flagName.equals("--name")) {
      return "container-name";
    }
    if (flagName.equals("-e") || flagName.equals("--env")) {
      return "env-var";
    }
    if (flagName.equals("-m") || flagName.equals("--message")) {
      return "message";
    }
    if (flagName.equals("-w") || flagName.equals("--workdir")) {
      return "path";
    }
    return roleForArg(tool, subcommand, flagName, 0);
  }

  /**
   * Split on whitespace but respect single/double quotes, so
   * {@code git stash -m "my message"} keeps "my message" as one token.
   */
  static List<String> tokenize(String command) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inSingle = false;
    boolean inDouble = false;
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (c == '\'' && !inDouble) {
        inSingle = !inSingle;
        continue;
      }
      if (c == '"' && !inSingle) {
        inDouble = !inDouble;
        continue;
      }
      if (Character.isWhitespace(c) && !inSingle && !inDouble) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(c);
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  /** Visible for tests: how many distinct commands are cached. */
  int cacheSize() {
    return cache.size();
  }
}
