#!/usr/bin/env node
/**
 * "why" CLI — takes a shell command, asks the backend for its
 * structured breakdown, and pretty-prints it.
 *
 * Usage:
 *   why "git reset --soft HEAD~1"
 *   why git push --force origin main
 */

const API_URL = process.env.WHY_API_URL ?? "http://localhost:8080/api/explain";

interface Flag {
  flag: string;
  meaning: string;
  risk: string | null;
}

interface Arg {
  value: string;
  role: string;
}

interface Effect {
  subsystem: string;
  change: string;
  target: string | null;
}

interface Command {
  tool: string;
  subcommand: string;
  flags: Flag[];
  args: Arg[];
  effects: Effect[];
}

interface ExplainResponse {
  command: Command;
  explanation: string | null;
  explanationError: boolean;
  confidence: "verified" | "inferred";
}

// Tiny ANSI helpers (no dependencies on purpose).
const reset = "\x1b[0m";
const bold = (s: string) => `\x1b[1m${s}${reset}`;
const dim = (s: string) => `\x1b[2m${s}${reset}`;
const cyan = (s: string) => `\x1b[36m${s}${reset}`;
const green = (s: string) => `\x1b[32m${s}${reset}`;
const yellow = (s: string) => `\x1b[33m${s}${reset}`;
const red = (s: string) => `\x1b[31m${s}${reset}`;

async function main(): Promise<void> {
  const command = process.argv.slice(2).join(" ").trim();

  if (!command) {
    console.error(`Usage: why "<command>"`);
    console.error(`Example: why "git reset --soft HEAD~1"`);
    process.exit(1);
  }

  let res: Response;
  try {
    res = await fetch(API_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ command }),
    });
  } catch {
    console.error(
      red(`Cannot reach the backend at ${API_URL}.`)
      + `\nIs it running? Start it with:`
      + `\n  cd backend && mvn spring-boot:run`
    );
    process.exit(1);
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    console.error(red(`Backend returned ${res.status}: ${text || res.statusText}`));
    process.exit(1);
  }

  const result = (await res.json()) as ExplainResponse;
  printCommand(command, result.command);
  printExplanation(result);
}

function printExplanation(res: ExplainResponse): void {
  if (res.explanationError || !res.explanation) {
    console.log(dim("(AI explanation unavailable, showing structured breakdown only)"));
    console.log("");
    return;
  }
  if (res.confidence === "inferred") {
    console.log(yellow(bold("⚠ AI-inferred (not in knowledge base)")));
  }
  console.log(bold("Explanation:"));
  console.log(`  ${res.explanation}`);
  console.log("");
}

function printCommand(original: string, cmd: Command): void {
  console.log("");
  console.log(bold(cyan(`$ ${original}`)));
  console.log(bold(`tool: `) + green(cmd.tool) + dim(`   subcommand: `) + green(cmd.subcommand));
  console.log("");

  if (cmd.subcommand === "unknown" || cmd.tool === "") {
    console.log(yellow("Unknown command — not in the knowledge base yet."));
    for (const e of cmd.effects) {
      console.log(`  ${dim("•")} ${e.change}`);
    }
    console.log("");
    return;
  }

  console.log(bold("Flags:"));
  if (cmd.flags.length === 0) {
    console.log(`  ${dim("(none)")}`);
  } else {
    for (const f of cmd.flags) {
      const risky = f.risk === "destructive";
      const marker = risky ? red(" [! DESTRUCTIVE]") : "";
      const name = risky ? red(bold(f.flag)) : yellow(f.flag);
      console.log(`  ${name} — ${f.meaning}${marker}`);
    }
  }
  console.log("");

  console.log(bold("Args:"));
  if (cmd.args.length === 0) {
    console.log(`  ${dim("(none)")}`);
  } else {
    for (const a of cmd.args) {
      console.log(`  ${cyan(a.value)} ${dim(`(${a.role})`)}`);
    }
  }
  console.log("");

  console.log(bold("Effects:"));
  if (cmd.effects.length === 0) {
    console.log(`  ${dim("(none known)")}`);
  } else {
    for (const e of cmd.effects) {
      const target = e.target ? dim(` → ${e.target}`) : "";
      console.log(`  ${dim("•")} [${e.subsystem}] ${e.change}${target}`);
    }
  }
  console.log("");

  if (cmd.flags.some((f) => f.risk === "destructive")) {
    console.log(red(bold("Warning: this command includes a destructive flag. Double-check before running it.")));
    console.log("");
  }
}

main().catch((err) => {
  console.error(red(`Unexpected error: ${String(err)}`));
  process.exit(1);
});
