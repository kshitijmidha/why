package com.why.explainer.model;

/** One side effect the command has on the system (filesystem, git history, containers, ...). */
public record Effect(String subsystem, String change, String target) {
}
