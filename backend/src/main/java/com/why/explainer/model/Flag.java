package com.why.explainer.model;

/** A single command-line flag and what our knowledge base says about it. */
public record Flag(String flag, String meaning, String risk) {
}
