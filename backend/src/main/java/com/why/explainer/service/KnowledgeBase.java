package com.why.explainer.service;

import com.why.explainer.model.Effect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads the rules-based knowledge base from knowledge/*.yaml.
 *
 * <p>Kept deliberately simple: each YAML file maps subcommands to their
 * known flags (meaning + risk) and the effects of running the subcommand.
 */
@Service
public class KnowledgeBase {

  /** One flag entry from YAML: what it means and whether it is risky. */
  public record FlagInfo(String meaning, String risk) {
  }

  /** One subcommand entry from YAML: flags + effects. */
  public record SubcommandInfo(
      String summary,
      Map<String, FlagInfo> flags,
      List<ConditionalEffect> effects) {
  }

  /**
   * One effect plus the flags that trigger it. An empty {@code whenFlags}
   * means baseline behavior: the effect always applies. Otherwise the
   * effect only applies when at least one of the listed flags was passed.
   */
  public record ConditionalEffect(List<String> whenFlags, Effect effect) {
  }

  // tool -> (subcommand -> info), e.g. "git" -> ("reset" -> info)
  private final Map<String, Map<String, SubcommandInfo>> tools = new HashMap<>();

  public KnowledgeBase() {
    loadAll();
  }

  @SuppressWarnings("unchecked")
  private void loadAll() {
    try {
      PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
      Resource[] resources = resolver.getResources("classpath:knowledge/*.yaml");
      Yaml yaml = new Yaml();
      for (Resource resource : resources) {
        Map<String, Object> doc;
        try (var in = resource.getInputStream()) {
          doc = yaml.load(in);
        }
        if (doc == null) {
          continue;
        }
        String tool = String.valueOf(doc.get("tool"));
        Map<String, Object> subcommands = (Map<String, Object>) doc.get("subcommands");
        if (tool == null || subcommands == null) {
          continue;
        }
        Map<String, SubcommandInfo> bySubcommand = tools.computeIfAbsent(tool, k -> new HashMap<>());
        for (Map.Entry<String, Object> entry : subcommands.entrySet()) {
          String subcommand = entry.getKey();
          Map<String, Object> sub = (Map<String, Object>) entry.getValue();
          String summary = sub.get("summary") == null ? "" : String.valueOf(sub.get("summary"));

          Map<String, FlagInfo> flags = new HashMap<>();
          Map<String, Object> rawFlags = (Map<String, Object>) sub.get("flags");
          if (rawFlags != null) {
            for (Map.Entry<String, Object> flagEntry : rawFlags.entrySet()) {
              Map<String, Object> flagDef = (Map<String, Object>) flagEntry.getValue();
              String meaning = flagDef.get("meaning") == null
                  ? "No description available."
                  : String.valueOf(flagDef.get("meaning"));
              Object riskObj = flagDef.get("risk");
              String risk = riskObj == null ? null : String.valueOf(riskObj);
              if ("null".equals(risk)) {
                risk = null;
              }
              flags.put(flagEntry.getKey(), new FlagInfo(meaning, risk));
            }
          }

          List<ConditionalEffect> effects = new ArrayList<>();
          List<Object> rawEffects = (List<Object>) sub.get("effects");
          if (rawEffects != null) {
            for (Object rawEffect : rawEffects) {
              Map<String, Object> effectMap = (Map<String, Object>) rawEffect;
              String subsystem = String.valueOf(effectMap.getOrDefault("subsystem", "unknown"));
              String change = String.valueOf(effectMap.getOrDefault("change", ""));
              Object targetObj = effectMap.get("target");
              String target = targetObj == null ? null : String.valueOf(targetObj);
              List<String> whenFlags = new ArrayList<>();
              Object whenObj = effectMap.get("whenFlags");
              if (whenObj instanceof List<?> whenList) {
                for (Object flag : whenList) {
                  whenFlags.add(String.valueOf(flag));
                }
              }
              effects.add(new ConditionalEffect(List.copyOf(whenFlags),
                  new Effect(subsystem, change, target)));
            }
          }

          bySubcommand.put(subcommand, new SubcommandInfo(summary, flags, effects));
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load knowledge base from knowledge/*.yaml", e);
    }
  }

  /** All known tool names (e.g. git, docker). */
  public List<String> knownTools() {
    return List.copyOf(tools.keySet());
  }

  /** Look up one subcommand, or null if the tool/subcommand is unknown. */
  public SubcommandInfo lookup(String tool, String subcommand) {
    Map<String, SubcommandInfo> bySubcommand = tools.get(tool);
    if (bySubcommand == null) {
      return null;
    }
    return bySubcommand.get(subcommand);
  }

  /** All subcommand names known for a tool (used for "unknown" error messages). */
  public List<String> subcommandsFor(String tool) {
    Map<String, SubcommandInfo> bySubcommand = tools.get(tool);
    if (bySubcommand == null) {
      return Collections.emptyList();
    }
    return List.copyOf(bySubcommand.keySet());
  }
}
