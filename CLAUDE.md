# Project: Gutenberg Word Histogram

## What this is
A single-class Java Maven program that accepts a book title, searches Project Gutenberg for a matching plain-text file, downloads it, and prints a text histogram of the 20 most frequent words (5+ letters, stop-word filtered) with `*` bars, followed by a total unique-word count. Each run is logged to `books.log`. A CrossPoll feature compares consecutive books to surface stop-word candidates into `common.dat`. Downloaded texts are cached locally in `book_cache/` to avoid repeat network calls.

## Project layout
```
cc_jgh/
├── pom.xml                                          Maven build, Java 17, runnable jar
├── src/main/java/com/gutenberg/WordHistogram.java   Entire implementation (~270 lines)
├── .vscode/
│   ├── launch.json                                  Run + Debug profiles
│   └── settings.json                                Points to local JDK 21
├── book_cache/                                      Downloaded .txt files by book ID (gitignored)
├── books.log                                        CSV run history (gitignored)
├── common.dat                                       CrossPoll stop-word candidates (gitignored)
├── error.log                                        Failed run log (gitignored)
└── target/
    └── word-histogram-1.0-SNAPSHOT.jar              Built artifact
```

## Build & run
```bash
# Build (Maven must be on PATH — installed via brew)
/opt/homebrew/bin/mvn package -q

# Run
java -jar target/word-histogram-1.0-SNAPSHOT.jar "Robinson Crusoe"
java -jar target/word-histogram-1.0-SNAPSHOT.jar "Pride and Prejudice"
```

VS Code: open Run & Debug panel (⇧⌘D), select **Run WordHistogram** or **Debug WordHistogram**, edit `args` in `.vscode/launch.json` to change the book title.

## How it works (WordHistogram.java)

| Method | Purpose |
|--------|---------|
| `main` | Entry point — orchestrates search → download → count → print → log → CrossPoll |
| `searchForBook` | GETs `gutenberg.org/ebooks/search/?query=<title>`, regex-extracts first eBook ID and author from HTML |
| `downloadText` | Checks `book_cache/<id>.txt` first; on miss fetches from Gutenberg and saves to cache |
| `stripGutenbergBoilerplate` | Trims content to between `*** START OF` and `*** END OF` markers |
| `countWords` | Splits on non-letters, lowercases, keeps ≥5-letter tokens not in stop-word set |
| `printHistogram` | Scales bars to 60 `*` max, pads label column to longest word width |
| `lastLoggedBookId` | Reads last non-empty line of `books.log` and returns its book ID |
| `crossPoll` | Compares current book's top-N words with previous book's; appends shared non-stop-words to `common.dat` |
| `logRun` | Appends one CSV line to `books.log` |
| `logError` | Appends one entry to `error.log` on any failure |
| `csvQuote` | RFC 4180 CSV quoting (doubles internal `"`) |

## Key constants (easy to tune)
- `TOP_N = 20` — number of words in histogram
- `BAR_WIDTH = 60` — max asterisks for the top word
- `CACHE_DIR = "book_cache"` — folder for cached plain-text files
- `STOP_WORDS` — hard-coded `HashSet<String>` of ~100 common English words to filter out

## Local data files (all gitignored)

| File | Format | Purpose |
|------|--------|---------|
| `books.log` | CSV: `bookId,title,author,uniqueWords,timestamp` | Persistent run history |
| `error.log` | `[timestamp] title="..." error=...` | Failed run diagnostics |
| `common.dat` | `word  # books A+B timestamp` | CrossPoll stop-word candidates |
| `book_cache/<id>.txt` | Raw Gutenberg plain text | Cached downloads by book ID |

## Java environment
- Runtime: `/Users/bill.hamilton/java/openjdk_21.0.8.0.121_21.45.56_aarch64`
- Maven: `/opt/homebrew/bin/mvn` (3.9.15, installed via Homebrew)
- Compiler target: Java 17 (runs fine on JDK 21)
- No external dependencies — uses only `java.net.http.HttpClient` (built-in since Java 11)

## GitHub
- Repo: https://github.com/chamilto0516/gutenberg-word-histogram
- Branch: `main`
- Committed: source, pom.xml, .vscode/, CLAUDE.md, .gitignore
- Excluded: `target/`, `book_cache/`, `books.log`, `error.log`, `common.dat`

## Future ideas to consider

### Analytics
- **Book comparison** — run two titles and print a side-by-side histogram, highlighting words unique to each
- **books.log report mode** — a `--report` flag that reads the accumulated log and shows trends across all runs (most-run authors, word count distribution, etc.)
- **Bigrams/trigrams** — top two- or three-word phrases instead of single words (e.g. "captain ahab" vs just "captain")

### Usability
- **Local text cache** ✅ — implemented: `book_cache/<id>.txt` checked before any network call; created on first download; prints `(cache hit)` when served from disk. Cache directory is gitignored.
- **Retry on network failure** — auto-retry SSL handshake errors with a short backoff (currently these are caught and written to `error.log`)
- **Configurable flags** — `--top 30`, `--min-length 4`, `--bar-width 40` instead of editing source constants

### Stop-Word Discovery
- **CrossPoll** ✅ — implemented: after each successful run, the previous book ID is read from `books.log`. If it differs from the current book, that book's text is fetched (cache-aware), its top-N words computed, and any words appearing in both top-N lists that are not already in `STOP_WORDS` are appended to `common.dat` with provenance (`word  # books A+B timestamp`). Words accumulating multiple entries across different pairs are strong candidates to promote into `STOP_WORDS`.

### Output
- **HTML output** — write a self-contained `histogram.html` with a real bar chart (CSS only, no dependencies)
- **Reading level estimate** — average word length + type-token ratio gives a rough complexity score per book

## Known behaviour / gotchas
- Gutenberg search returns the **first** result; if the title is ambiguous the wrong book may be picked. Pass a precise title to avoid this.
- Some older Gutenberg files use different filename patterns and may fail the two-candidate download fallback.
- The stop-word list is opinionated — edit `STOP_WORDS` in the source to add or remove words.
- CrossPoll re-downloads the previous book only if it is not already in `book_cache/`, so the extra network cost disappears after the first comparison.
- `error.log` captures the full exception message including SSL handshake failures, which are the most common transient Gutenberg error.
