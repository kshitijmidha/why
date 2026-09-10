package com.why.explainer.model;

import java.util.List;

/**
 * Structured breakdown of a shell command.
 *
 * <p>Example: "git reset --soft HEAD~1" becomes
 * tool=git, subcommand=reset, flags=[--soft], args=[HEAD~1], effects=[...].
 */
public record Command(
        String tool,
        String subcommand,
        List<Flag> flags,
        List<Arg> args,
        List<Effect> effects) {
}
