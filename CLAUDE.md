# Project: Gutenberg Word Histogram

## What this is
A single-class Java Maven program that accepts a book title, searches Project Gutenberg for a matching plain-text file, downloads it, and prints a text histogram of the 20 most frequent words (5+ letters, stop-word filtered) with `*` bars, followed by a total unique-word count.

## Project layout
```
cc_jgh/
├── pom.xml                                          Maven build, Java 17, runnable jar
├── src/main/java/com/gutenberg/WordHistogram.java   Entire implementation (~160 lines)
├── .vscode/
│   ├── launch.json                                  Run + Debug profiles
│   └── settings.json                                Points to local JDK 21
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
| `main` | Entry point — orchestrates search → download → count → print |
| `searchForBookId` | GETs `gutenberg.org/ebooks/search/?query=<title>`, regex-extracts first numeric eBook ID |
| `downloadText` | Tries `files/<id>/<id>-0.txt`, falls back to `<id>.txt` |
| `stripGutenbergBoilerplate` | Trims content to between `*** START OF` and `*** END OF` markers |
| `countWords` | Splits on non-letters, lowercases, keeps ≥5-letter tokens not in stop-word set |
| `printHistogram` | Scales bars to 60 `*` max, pads label column to longest word width |

## Key constants (easy to tune)
- `TOP_N = 20` — number of words in histogram
- `BAR_WIDTH = 60` — max asterisks for the top word
- `STOP_WORDS` — hard-coded `HashSet<String>` of ~100 common English words to filter out

## Java environment
- Runtime: `/Users/bill.hamilton/java/openjdk_21.0.8.0.121_21.45.56_aarch64`
- Maven: `/opt/homebrew/bin/mvn` (3.9.15, installed via Homebrew)
- Compiler target: Java 17 (runs fine on JDK 21)
- No external dependencies — uses only `java.net.http.HttpClient` (built-in since Java 11)

## Known behaviour / gotchas
- Gutenberg search returns the **first** result; if the title is ambiguous the wrong book may be picked. Pass a precise title to avoid this.
- Some older Gutenberg files use different filename patterns and may fail the two-candidate download fallback.
- The stop-word list is opinionated — edit `STOP_WORDS` in the source to add or remove words.
