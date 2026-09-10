package com.why.explainer.model;

/** A positional argument (anything that is not the tool, subcommand, or a flag). */
public record Arg(String value, String role) {
}
