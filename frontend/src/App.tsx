import { useEffect, useState } from "react";

// Backend base URL comes from the build-time env var VITE_API_BASE_URL
// (set to the deployed Render URL on Vercel/Netlify); locally it falls
// back to the default Spring Boot port so dev needs no env vars at all.
const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:8080").replace(
  /\/+$/,
  ""
);
const API_URL = `${API_BASE}/api/explain`;

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

type Status = "idle" | "loading" | "error";
type Theme = "light" | "dark" | "system";

const THEME_ORDER: Theme[] = ["light", "dark", "system"];

const EXAMPLES = [
  "git reset --hard HEAD~1",
  "git push --force origin main",
  "docker run -d -p 8080:80 nginx",
];

function ThemeIcon({ theme }: { theme: Theme }) {
  const common = {
    width: 16,
    height: 16,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.5,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true,
  };

  if (theme === "light") {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
      </svg>
    );
  }

  if (theme === "dark") {
    return (
      <svg {...common}>
        <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
      </svg>
    );
  }

  return (
    <svg {...common}>
      <rect x="2.5" y="4" width="19" height="13" rx="2" />
      <path d="M8.5 20.5h7M12 17.5v3" />
    </svg>
  );
}

function ChevronIcon() {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="m9 6 6 6-6 6" />
    </svg>
  );
}

function initialTheme(): Theme {
  const attr = document.documentElement.dataset.theme;
  if (attr === "light" || attr === "dark" || attr === "system") return attr;
  return "system";
}

export default function App() {
  const [theme, setTheme] = useState<Theme>(initialTheme);
  const [input, setInput] = useState("git reset --soft HEAD~1");
  const [result, setResult] = useState<ExplainResponse | null>(null);
  const [status, setStatus] = useState<Status>("idle");
  const [error, setError] = useState("");
  const [openNotes, setOpenNotes] = useState<Record<string, boolean>>({});

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem("why-theme", theme);
    } catch {
      /* storage unavailable */
    }
  }, [theme]);

  function cycleTheme() {
    const next = THEME_ORDER[(THEME_ORDER.indexOf(theme) + 1) % THEME_ORDER.length];
    setTheme(next);
  }

  function toggleNote(key: string) {
    setOpenNotes((prev) => ({ ...prev, [key]: !prev[key] }));
  }

  async function runExplain(raw: string) {
    const command = raw.trim();
    if (!command) {
      setError("type a command to explain.");
      setStatus("error");
      return;
    }
    setStatus("loading");
    setError("");
    setResult(null);
    setOpenNotes({});
    try {
      const res = await fetch(API_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ command }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `request failed (${res.status} ${res.statusText}).`);
      }
      const data = (await res.json()) as ExplainResponse;
      setResult(data);
      setStatus("idle");
    } catch (err) {
      setStatus("error");
      if (err instanceof TypeError) {
        setError(
          `can't reach the backend at ${API_BASE}. start it with: cd backend && mvn spring-boot:run`
        );
      } else {
        setError(err instanceof Error ? err.message : "something went wrong.");
      }
    }
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    runExplain(input);
  }

  function useExample(ex: string) {
    setInput(ex);
    runExplain(ex);
  }

  const isUnknown = result !== null && result.command.subcommand === "unknown";

  return (
    <div className="page">
      <header className="topbar">
        <span className="wordmark">why</span>
        <button
          type="button"
          className="theme-toggle"
          onClick={cycleTheme}
          aria-label={`theme: ${theme}`}
          title={`theme: ${theme}`}
        >
          <ThemeIcon theme={theme} />
        </button>
      </header>

      <section className="intro">
        <h1>explain a shell command</h1>

        <form className="command-form" onSubmit={onSubmit}>
          <div className="command-row">
            <span className="prompt" aria-hidden="true">
              $
            </span>
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="git reset --soft HEAD~1"
              spellCheck={false}
              autoComplete="off"
              aria-label="shell command to explain"
            />
            <button type="submit" className="command-submit" disabled={status === "loading"}>
              explain
            </button>
          </div>
        </form>

        <div className="examples">
          <span className="examples-label">examples</span>
          {EXAMPLES.map((ex) => (
            <button key={ex} type="button" className="example" onClick={() => useExample(ex)}>
              {ex}
            </button>
          ))}
        </div>

        <p className="host-note">
          running on render's free tier — the first request after a few minutes idle can take
          up to a minute while the server wakes up. clone the repo and run it locally for
          millisecond response times.
        </p>
      </section>

      {status === "loading" && (
        <p className="status-line" role="status">
          explaining…
        </p>
      )}

      {status === "error" && (
        <p className="error" role="alert">
          <span className="error-label">error:</span> {error}
        </p>
      )}

      {status === "idle" && result && (
        <main className="result">
          <div className="result-meta">
            <span>
              {result.command.tool} · {result.command.subcommand}
            </span>
            <span className="meta-dot" aria-hidden="true">
              ·
            </span>
            <span className="status-label">{result.confidence}</span>
          </div>

          {isUnknown && (
            <p className="unknown-note">
              not in the knowledge base. the explanation below is a best-effort guess.
            </p>
          )}

          {!isUnknown && (
            <section className="section">
              <h2 className="section-title">breakdown</h2>
              <div className="codeblock">
                <div className="blk-group">
                  <div className="blk-label">flags</div>
                  {result.command.flags.length === 0 ? (
                    <div className="blk-line blk-muted">none</div>
                  ) : (
                    result.command.flags.map((f, i) => {
                      const key = `flag-${i}`;
                      const destructive = f.risk === "destructive";
                      const open = !!openNotes[key];
                      return (
                        <div className="blk-entry" key={key}>
                          <div className="blk-line">
                            <span className="blk-token">{f.flag}</span>
                            <span className="blk-text">{f.meaning}</span>
                            {destructive && (
                              <button
                                type="button"
                                className="footnote-toggle"
                                aria-expanded={open}
                                onClick={() => toggleNote(key)}
                              >
                                risk note <ChevronIcon />
                              </button>
                            )}
                          </div>
                          {destructive && (
                            <div
                              className={`footnote-panel${open ? " is-open" : ""}`}
                              aria-hidden={!open}
                            >
                              <div className="footnote-inner">
                                <p className="footnote-body">
                                  this flag is marked destructive in the knowledge base. confirm you
                                  intend to discard data before running the command.
                                </p>
                              </div>
                            </div>
                          )}
                        </div>
                      );
                    })
                  )}
                </div>

                <div className="blk-group">
                  <div className="blk-label">args</div>
                  {result.command.args.length === 0 ? (
                    <div className="blk-line blk-muted">none</div>
                  ) : (
                    result.command.args.map((a, i) => (
                      <div className="blk-line" key={`${a.value}-${i}`}>
                        <span className="blk-token">{a.value}</span>
                        <span className="blk-text">{a.role}</span>
                      </div>
                    ))
                  )}
                </div>

                <div className="blk-group">
                  <div className="blk-label">effects</div>
                  {result.command.effects.length === 0 ? (
                    <div className="blk-line blk-muted">none</div>
                  ) : (
                    result.command.effects.map((e, i) => (
                      <div className="blk-line" key={i}>
                        <span className="blk-tag">{e.subsystem}</span>
                        <span className="blk-text">{e.change}</span>
                        {e.target ? <span className="blk-token">{e.target}</span> : null}
                      </div>
                    ))
                  )}
                </div>
              </div>
            </section>
          )}

          <section className="section">
            <h2 className="section-title">explanation</h2>
            {result.explanationError || !result.explanation ? (
              <p className="blk-muted">
                ai explanation unavailable. showing the structured breakdown only.
              </p>
            ) : (
              <p className="explanation">{result.explanation}</p>
            )}
          </section>
        </main>
      )}

      <footer className="footer">
        <a href={API_URL}>
          api <span aria-hidden="true">↗</span>
        </a>
      </footer>
    </div>
  );
}
