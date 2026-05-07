package com.gutenberg;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WordHistogram {

    private static final int TOP_N = 20;
    private static final int BAR_WIDTH = 60;
    private static final String CACHE_DIR = "book_cache";
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2000;

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "their", "which", "would", "about", "could", "shall", "there", "these",
        "those", "after", "where", "being", "other", "every", "great", "under",
        "never", "again", "above", "below", "first", "while", "still", "since",
        "might", "often", "think", "asked", "right", "place", "though", "found",
        "going", "comes", "taken", "given", "later", "until", "whose", "whether",
        "before", "himself", "herself", "itself", "myself", "themselves", "ourselves",
        "yourself", "yourselves", "something", "nothing", "anything", "everything",
        "someone", "anyone", "everyone", "without", "within", "across", "between",
        "through", "against", "during", "around", "because", "having", "making",
        "coming", "saying", "looking", "getting", "putting", "seemed", "should",
        "little", "really", "always", "almost", "rather", "quite", "perhaps",
        "either", "neither", "indeed", "anyway", "however", "therefore", "although",
        "already", "another", "became", "become", "behind", "beside", "beyond",
        "cannot", "certain", "certainly", "doing", "enough", "except", "finally",
        "former", "further", "inside", "instead", "latter", "merely", "moment",
        "mostly", "namely", "nearly", "notwithstanding", "otherwise", "outside",
        "owing", "partly", "please", "presently", "prior", "probably", "regarding",
        "remain", "remains", "seems", "simply", "somebody", "sometime", "somewhat",
        "somewhere", "thereby", "therein", "thereof", "thereto", "throughout", "thence",
        "toward", "towards", "unless", "unlike", "usually", "various", "versus",
        "whereby", "wherein", "whereof", "whereto", "whither", "worth", "yours",
        "thought", "things", "world"
    ));

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        // Parse optional flags; collect remaining tokens as the book title
        int topN = TOP_N;
        int barWidth = BAR_WIDTH;
        int minLength = 5;
        boolean pipeMode = false;
        List<String> titleTokens = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--top":
                    if (++i < args.length) topN = Integer.parseInt(args[i]);
                    break;
                case "--bar-width":
                    if (++i < args.length) barWidth = Integer.parseInt(args[i]);
                    break;
                case "--min-length":
                    if (++i < args.length) minLength = Integer.parseInt(args[i]);
                    break;
                case "--pipe":
                    pipeMode = true;
                    break;
                case "--help":
                    printUsage();
                    System.exit(0);
                default:
                    titleTokens.add(args[i]);
            }
        }

        if (titleTokens.isEmpty()) {
            printUsage();
            System.exit(1);
        }

        String title = String.join(" ", titleTokens);

        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            int bookId;
            String author;
            String foundTitle;

            if (title.matches("\\d+")) {
                bookId = Integer.parseInt(title);
                if (!pipeMode) System.out.println("Looking up Project Gutenberg eBook #" + bookId + "...");
                String[] meta = fetchBookMetadata(client, bookId);
                if (meta == null) {
                    throw new RuntimeException("Could not retrieve metadata for eBook #" + bookId);
                }
                author = meta[0];
                foundTitle = meta[1].isEmpty() ? "eBook #" + bookId : meta[1];
            } else {
                if (!pipeMode) System.out.println("Searching Project Gutenberg for: " + title);
                String[] bookInfo = searchForBook(client, title);
                if (bookInfo == null) {
                    throw new RuntimeException("No matching book found on Project Gutenberg");
                }
                bookId = Integer.parseInt(bookInfo[0]);
                author = bookInfo[1];
                foundTitle = bookInfo[2];
            }

            if (!pipeMode) System.out.println("Found: " + foundTitle + " (eBook #" + bookId + "), downloading...");
            String rawText = downloadText(client, bookId, pipeMode);
            String text = stripGutenbergBoilerplate(rawText);

            Map<String, Integer> freq = countWords(text, minLength);
            List<Map.Entry<String, Integer>> topWords = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .collect(Collectors.toList());

            if (pipeMode) {
                for (Map.Entry<String, Integer> entry : topWords) {
                    System.out.println(entry.getKey() + "\t" + entry.getValue());
                }
            } else {
                printHistogram(topWords, freq.size(), topN, barWidth, minLength);
                double[] readability = computeReadability(text);
                printReadability(readability[0], readability[1]);
            }
            int prevBookId = lastLoggedBookId();
            logRun(bookId, foundTitle, author, freq.size());
            if (prevBookId > 0 && prevBookId != bookId) {
                crossPoll(client, bookId, topWords, prevBookId, topN, pipeMode);
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            System.err.println("Error: " + msg);
            logError(title, msg);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar word-histogram.jar [options] \"<book title>\"");
        System.err.println("  --top N          Words to show in histogram (default: " + TOP_N + ")");
        System.err.println("  --bar-width N    Max bar width in asterisks  (default: " + BAR_WIDTH + ")");
        System.err.println("  --min-length N   Minimum word length         (default: 5)");
        System.err.println("  --pipe           Output word<TAB>count lines only, suitable for piping");
    }

    // Returns {bookId, author} or null if not found
    private static String[] searchForBook(HttpClient client, String title) throws Exception {
        String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String searchUrl = "https://www.gutenberg.org/ebooks/search/?query=" + encoded;

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(searchUrl))
            .header("User-Agent", "Mozilla/5.0 (compatible; GutenbergHistogram/1.0)")
            .GET()
            .build();

        HttpResponse<String> resp = sendWithRetry(client, req, false);
        if (resp == null || resp.statusCode() != 200) {
            return null;
        }

        String html = resp.body();
        Pattern idPattern = Pattern.compile("/ebooks/(\\d+)");
        Matcher idMatcher = idPattern.matcher(html);
        if (!idMatcher.find()) {
            return null;
        }
        String bookId = idMatcher.group(1);

        // Title and author appear inside the first book listing
        Pattern titleAuthorPattern = Pattern.compile(
            "/ebooks/" + bookId + "\".*?<span class=\"title\">([^<]+)</span>.*?<span class=\"subtitle\">([^<]+)",
            Pattern.DOTALL);
        Matcher titleAuthorMatcher = titleAuthorPattern.matcher(html);
        String bookTitle = "";
        String author = "";
        if (titleAuthorMatcher.find()) {
            bookTitle = titleAuthorMatcher.group(1).trim();
            author = titleAuthorMatcher.group(2).trim();
        }

        return new String[]{bookId, author, bookTitle};
    }

    // Returns {author, title} by fetching the book's detail page directly
    private static String[] fetchBookMetadata(HttpClient client, int bookId) throws Exception {
        String url = "https://www.gutenberg.org/ebooks/" + bookId;
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (compatible; GutenbergHistogram/1.0)")
            .GET()
            .build();
        HttpResponse<String> resp = sendWithRetry(client, req, false);
        if (resp == null || resp.statusCode() != 200) return null;

        String html = resp.body();
        String bookTitle = "";
        String author = "";

        Matcher titleMatcher = Pattern.compile("<title>([^|<]+)").matcher(html);
        if (titleMatcher.find()) bookTitle = titleMatcher.group(1).trim();

        Matcher authorMatcher = Pattern.compile(
            "itemprop=\"creator\"[^>]*>\\s*<a[^>]*>([^<]+)</a>", Pattern.DOTALL).matcher(html);
        if (authorMatcher.find()) author = authorMatcher.group(1).trim();

        return new String[]{author, bookTitle};
    }

    private static String downloadText(HttpClient client, int bookId, boolean pipeMode) throws Exception {
        Path cacheFile = Path.of(CACHE_DIR, bookId + ".txt");
        if (Files.exists(cacheFile)) {
            if (!pipeMode) System.out.println("(cache hit)");
            return Files.readString(cacheFile);
        }

        String[] candidates = {
            "https://www.gutenberg.org/files/" + bookId + "/" + bookId + "-0.txt",
            "https://www.gutenberg.org/files/" + bookId + "/" + bookId + ".txt"
        };

        for (String url : candidates) {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (compatible; GutenbergHistogram/1.0)")
                .GET()
                .build();
            HttpResponse<String> resp = sendWithRetry(client, req, pipeMode);
            if (resp != null && resp.statusCode() == 200) {
                String text = resp.body();
                Files.createDirectories(Path.of(CACHE_DIR));
                Files.writeString(cacheFile, text);
                return text;
            }
        }

        throw new RuntimeException("Could not download text for eBook #" + bookId);
    }

    private static HttpResponse<String> sendWithRetry(HttpClient client, HttpRequest req, boolean pipeMode) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return client.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    if (!pipeMode) System.out.println("(network error, retrying " + attempt + "/" + (MAX_RETRIES - 1) + "...)");
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                }
            }
        }
        throw lastException;
    }

    private static int lastLoggedBookId() {
        try {
            Path log = Path.of("books.log");
            if (!Files.exists(log)) return -1;
            List<String> lines = Files.readAllLines(log);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    return Integer.parseInt(line.split(",")[0]);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    // CrossPoll: compare current book's top-N with previous book's top-N,
    // append words in common that aren't already stop words to common.dat
    private static void crossPoll(HttpClient client, int currentId,
            List<Map.Entry<String, Integer>> currentTop, int prevId, int topN, boolean pipeMode) {
        try {
            if (!pipeMode) System.out.println("\n[CrossPoll] Comparing with eBook #" + prevId + "...");
            String prevText = stripGutenbergBoilerplate(downloadText(client, prevId, false));
            Set<String> prevTopWords = countWords(prevText, 5).entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<String> currentTopWords = currentTop.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

            List<String> common = prevTopWords.stream()
                .filter(currentTopWords::contains)
                .filter(w -> !STOP_WORDS.contains(w))
                .sorted()
                .collect(Collectors.toList());

            if (common.isEmpty()) {
                if (!pipeMode) System.out.println("[CrossPoll] No new stop-word candidates found.");
                return;
            }

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            StringBuilder sb = new StringBuilder();
            for (String word : common) {
                sb.append(word).append("  # books ").append(currentId)
                  .append("+").append(prevId).append(" ").append(ts).append("\n");
            }
            Files.writeString(Path.of("common.dat"), sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!pipeMode) System.out.println("[CrossPoll] " + common.size() + " candidate(s) appended to common.dat: " + common);
        } catch (Exception e) {
            if (!pipeMode) System.out.println("[CrossPoll] Skipped: " + e.getMessage());
        }
    }

    private static void logRun(int bookId, String title, String author, int uniqueWords) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String line = bookId + "," + csvQuote(title) + "," + csvQuote(author) + "," + uniqueWords + "," + ts + "\n";
        Files.writeString(Path.of("books.log"), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void logError(String title, String message) {
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String entry = "[" + ts + "] title=\"" + title + "\" error=" + message + "\n";
            Files.writeString(Path.of("error.log"), entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static String csvQuote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String stripGutenbergBoilerplate(String text) {
        int start = text.indexOf("*** START OF");
        if (start >= 0) {
            int newline = text.indexOf('\n', start);
            if (newline >= 0) text = text.substring(newline + 1);
        }
        int end = text.indexOf("*** END OF");
        if (end >= 0) {
            text = text.substring(0, end);
        }
        return text;
    }

    private static Map<String, Integer> countWords(String text, int minLength) {
        Map<String, Integer> freq = new HashMap<>();
        for (String token : text.split("[^a-zA-Z]+")) {
            String word = token.toLowerCase();
            if (word.length() >= minLength && !STOP_WORDS.contains(word)) {
                freq.merge(word, 1, Integer::sum);
            }
        }
        return freq;
    }

    private static void printHistogram(List<Map.Entry<String, Integer>> topWords, int totalUnique,
            int topN, int barWidth, int minLength) {
        int maxCount = topWords.get(0).getValue();
        int labelWidth = topWords.stream().mapToInt(e -> e.getKey().length()).max().orElse(10);

        System.out.println("\nWord frequency histogram (top " + topN + " words, " + minLength + "+ letters, filtered):\n");
        for (Map.Entry<String, Integer> entry : topWords) {
            int bars = (int) Math.round(entry.getValue() * (double) barWidth / maxCount);
            String bar = "*".repeat(bars);
            System.out.printf("%-" + labelWidth + "s : %-" + barWidth + "s  (%d)%n",
                entry.getKey(), bar, entry.getValue());
        }
        System.out.printf("%nTotal unique words (%d+ letters, filtered): %,d%n", minLength, totalUnique);
    }

    // Returns [avgWordLength, mattr] computed over raw (unfiltered) tokens.
    // MATTR uses a 500-word sliding window to neutralise corpus-size bias.
    private static double[] computeReadability(String text) {
        String[] tokens = text.split("[^a-zA-Z]+");
        List<String> words = new ArrayList<>();
        long totalChars = 0;
        for (String t : tokens) {
            if (!t.isEmpty()) {
                String w = t.toLowerCase();
                words.add(w);
                totalChars += w.length();
            }
        }
        if (words.isEmpty()) return new double[]{0, 0};

        double avgLen = (double) totalChars / words.size();

        // MATTR: slide a window of size W, average the per-window TTR
        final int W = 500;
        double mattrSum = 0;
        int windows = 0;
        if (words.size() < W) {
            // corpus smaller than window — fall back to plain TTR
            long unique = words.stream().distinct().count();
            mattrSum = (double) unique / words.size();
            windows = 1;
        } else {
            Map<String, Integer> window = new HashMap<>();
            // prime the first window
            for (int i = 0; i < W; i++) {
                window.merge(words.get(i), 1, Integer::sum);
            }
            mattrSum += (double) window.size() / W;
            windows = 1;
            for (int i = W; i < words.size(); i++) {
                // add incoming word
                window.merge(words.get(i), 1, Integer::sum);
                // remove outgoing word
                String out = words.get(i - W);
                int cnt = window.get(out);
                if (cnt == 1) window.remove(out);
                else window.put(out, cnt - 1);
                mattrSum += (double) window.size() / W;
                windows++;
            }
        }
        double mattr = mattrSum / windows;
        return new double[]{avgLen, mattr};
    }

    private static void printReadability(double avgLen, double mattr) {
        String gradeLabel;
        if (avgLen < 4.0)      gradeLabel = "early elementary (~grade 1-2)";
        else if (avgLen < 4.5) gradeLabel = "elementary (~grade 3-4)";
        else if (avgLen < 5.0) gradeLabel = "middle school (~grade 5-6)";
        else if (avgLen < 5.5) gradeLabel = "middle/high school (~grade 7-8)";
        else if (avgLen < 6.0) gradeLabel = "high school";
        else                   gradeLabel = "college / literary";

        String diversityLabel;
        if (mattr < 0.10)      diversityLabel = "repetitive";
        else if (mattr < 0.20) diversityLabel = "average";
        else if (mattr < 0.30) diversityLabel = "varied";
        else                   diversityLabel = "highly varied";

        System.out.println("\nReadability estimate:");
        System.out.printf("  Avg word length  : %.1f chars  (%s)%n", avgLen, gradeLabel);
        System.out.printf("  Lexical diversity: %.2f MATTR  (%s vocabulary)%n", mattr, diversityLabel);
    }
}
