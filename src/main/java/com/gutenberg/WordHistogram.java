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
        "whereby", "wherein", "whereof", "whereto", "whither", "worth", "yours"
    ));

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java -jar word-histogram.jar \"<book title>\"");
            System.exit(1);
        }

        String title = String.join(" ", args);
        System.out.println("Searching Project Gutenberg for: " + title);

        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            String[] bookInfo = searchForBook(client, title);
            if (bookInfo == null) {
                throw new RuntimeException("No matching book found on Project Gutenberg");
            }
            int bookId = Integer.parseInt(bookInfo[0]);
            String author = bookInfo[1];

            System.out.println("Found eBook #" + bookId + ", downloading...");
            String rawText = downloadText(client, bookId);
            String text = stripGutenbergBoilerplate(rawText);

            Map<String, Integer> freq = countWords(text);
            List<Map.Entry<String, Integer>> top20 = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_N)
                .collect(Collectors.toList());

            printHistogram(top20, freq.size());
            int prevBookId = lastLoggedBookId();
            logRun(bookId, title, author, freq.size());
            if (prevBookId > 0 && prevBookId != bookId) {
                crossPoll(client, bookId, top20, prevBookId);
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            System.err.println("Error: " + msg);
            logError(title, msg);
            System.exit(1);
        }
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

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return null;
        }

        String html = resp.body();
        Pattern idPattern = Pattern.compile("/ebooks/(\\d+)");
        Matcher idMatcher = idPattern.matcher(html);
        if (!idMatcher.find()) {
            return null;
        }
        String bookId = idMatcher.group(1);

        // Author appears as <span class="subtitle"> shortly after the first book link
        Pattern authorPattern = Pattern.compile(
            "/ebooks/" + bookId + "[^\"]*\"[^>]*>.*?<span class=\"subtitle\">([^<]+)",
            Pattern.DOTALL);
        Matcher authorMatcher = authorPattern.matcher(html);
        String author = authorMatcher.find() ? authorMatcher.group(1).trim() : "";

        return new String[]{bookId, author};
    }

    private static String downloadText(HttpClient client, int bookId) throws Exception {
        // Try <id>-0.txt first, fall back to <id>.txt
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
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
        }

        throw new RuntimeException("Could not download text for eBook #" + bookId);
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
            List<Map.Entry<String, Integer>> currentTop, int prevId) {
        try {
            System.out.println("\n[CrossPoll] Comparing with eBook #" + prevId + "...");
            String prevText = stripGutenbergBoilerplate(downloadText(client, prevId));
            Set<String> prevTopWords = countWords(prevText).entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_N)
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
                System.out.println("[CrossPoll] No new stop-word candidates found.");
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
            System.out.println("[CrossPoll] " + common.size() + " candidate(s) appended to common.dat: " + common);
        } catch (Exception e) {
            System.out.println("[CrossPoll] Skipped: " + e.getMessage());
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

    private static Map<String, Integer> countWords(String text) {
        Map<String, Integer> freq = new HashMap<>();
        for (String token : text.split("[^a-zA-Z]+")) {
            String word = token.toLowerCase();
            if (word.length() >= 5 && !STOP_WORDS.contains(word)) {
                freq.merge(word, 1, Integer::sum);
            }
        }
        return freq;
    }

    private static void printHistogram(List<Map.Entry<String, Integer>> top20, int totalUnique) {
        int maxCount = top20.get(0).getValue();
        int labelWidth = top20.stream().mapToInt(e -> e.getKey().length()).max().orElse(10);

        System.out.println("\nWord frequency histogram (top " + TOP_N + " words, 5+ letters, filtered):\n");
        for (Map.Entry<String, Integer> entry : top20) {
            int bars = (int) Math.round(entry.getValue() * (double) BAR_WIDTH / maxCount);
            String bar = "*".repeat(bars);
            System.out.printf("%-" + labelWidth + "s : %-" + BAR_WIDTH + "s  (%d)%n",
                entry.getKey(), bar, entry.getValue());
        }
        System.out.printf("%nTotal unique words (5+ letters, filtered): %,d%n", totalUnique);
    }
}
