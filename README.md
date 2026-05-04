# Gutenberg Word Histogram

A Java command-line tool that downloads any public-domain book from [Project Gutenberg](https://www.gutenberg.org/) by title and prints a text histogram of its 20 most frequently used words (5 letters or longer).

## Example output

```
Searching Project Gutenberg for: Robinson Crusoe
Found eBook #521, downloading...

Word frequency histogram (top 20 words, 5+ letters, filtered):

shore     : ************************************************************  (269)
three     : **********************************************                (206)
friday    : *******************************************                   (192)
island    : ******************************************                    (187)
began     : *****************************************                     (185)
thought   : *************************************                         (165)
...

Total unique words (5+ letters, filtered): 4,924
```

## Requirements

- Java 11 or later
- Apache Maven 3.6+

## Build

```bash
mvn package -q
```

This produces `target/word-histogram-1.0-SNAPSHOT.jar`.

## Usage

```bash
java -jar target/word-histogram-1.0-SNAPSHOT.jar "<book title>"
```

Examples:

```bash
java -jar target/word-histogram-1.0-SNAPSHOT.jar "Robinson Crusoe"
java -jar target/word-histogram-1.0-SNAPSHOT.jar "Pride and Prejudice"
java -jar target/word-histogram-1.0-SNAPSHOT.jar "Moby Dick"
```

The title is passed directly to the Gutenberg search engine. Use a specific title to ensure the right book is matched; the first search result is always used.

## How it works

1. **Search** — queries `gutenberg.org/ebooks/search/?query=<title>` and extracts the first eBook ID from the HTML response.
2. **Download** — fetches the plain-text file at `gutenberg.org/files/<id>/<id>-0.txt` (falls back to `<id>.txt`).
3. **Strip boilerplate** — trims the Gutenberg header and footer (between `*** START OF` and `*** END OF` markers).
4. **Tokenise** — splits on non-letter characters, lowercases all tokens, keeps only words with 5 or more letters.
5. **Filter** — removes common English stop words (their, would, which, before, because, etc.) so the histogram reflects meaningful vocabulary.
6. **Histogram** — selects the top 20 by frequency, scales bars proportionally to a 60-character max width, and prints the total count of unique qualifying words.
7. **CrossPoll** — after each successful run, compares the current book's top-N words against the previous book's top-N. Any words that appear in both lists and are not already stop words are appended to `common.dat` as candidates to improve the stop-word list over time (see below).

## CrossPoll: stop-word discovery

Each run automatically cross-references the new book's top words with those of the last book in `books.log` (skipped if it's the same book). Words that appear in both top-N lists but are not already filtered are recorded in `common.dat`:

```
thought  # books 120+84 2026-05-04 10:25:48
world    # books 45304+2641 2026-05-04 10:27:06
```

The format is `word  # books <currentId>+<prevId> <timestamp>`. Words that accumulate multiple entries across different book pairs are strong candidates to add to `STOP_WORDS` in `WordHistogram.java`, making future histograms progressively more meaningful. `common.dat` is local-only and not committed to the repository.

## No external dependencies

The program uses only the Java standard library (`java.net.http.HttpClient`, introduced in Java 11). The Maven build has no third-party dependencies.

## Customising

All tunable values are constants at the top of `WordHistogram.java`:

| Constant | Default | Effect |
|----------|---------|--------|
| `TOP_N` | `20` | Number of words shown in the histogram |
| `BAR_WIDTH` | `60` | Maximum `*` characters for the most frequent word |
| `STOP_WORDS` | ~100 words | Edit the `HashSet` to add or remove filtered words |

## VS Code

The `.vscode/` directory contains a pre-configured **Run** and **Debug** launch profile. Open the Run & Debug panel (`⇧⌘D`), select a configuration, and edit `args` in `.vscode/launch.json` to change the book title. Requires the [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack).

## License

This project is released under the [MIT License](https://opensource.org/licenses/MIT). Books downloaded from Project Gutenberg are in the public domain.
