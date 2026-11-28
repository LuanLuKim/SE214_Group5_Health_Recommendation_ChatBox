package chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads the M1-aligned Vietnamese symptom vocabulary from {@code /chat/chat_lexicon.json} and
 * extracts explicit symptom tokens using Unicode-aware word boundaries.
 */
final class ChatLexicon {
  private static final String RESOURCE_PATH = "/chat/chat_lexicon.json";

  private final Map<Pattern, String> compiledKeywords;

  private ChatLexicon(Map<Pattern, String> compiledKeywords) {
    this.compiledKeywords = compiledKeywords;
  }

  static ChatLexicon loadDefault(ObjectMapper json) {
    try (InputStream in = ChatLexicon.class.getResourceAsStream(RESOURCE_PATH)) {
      if (in == null) {
        throw new IllegalStateException("Missing chat lexicon resource: " + RESOURCE_PATH);
      }
      JsonNode root = json.readTree(in);
      JsonNode symptoms = root.path("symptom_keywords");
      Map<Pattern, String> compiled = new LinkedHashMap<>();
      symptoms
          .fields()
          .forEachRemaining(
              entry -> {
                String keyword = normalize(entry.getKey()).toLowerCase(Locale.ROOT);
                String token = entry.getValue().asText("").trim();
                if (!keyword.isEmpty() && !token.isEmpty()) {
                  Pattern pattern =
                      Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(keyword) + "(?![\\p{L}\\p{N}_])");
                  compiled.put(pattern, token);
                }
              });
      return new ChatLexicon(compiled);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load chat lexicon resource", ex);
    }
  }

  static String normalize(String text) {
    if (text == null) {
      return "";
    }
    String nfc = Normalizer.normalize(text.trim(), Normalizer.Form.NFC);
    return nfc.replaceAll("\\s+", " ");
  }

  Set<String> extractFacts(String normalizedText) {
    if (normalizedText == null || normalizedText.isBlank()) {
      return Set.of();
    }
    String lower = normalize(normalizedText).toLowerCase(Locale.ROOT);
    Set<String> facts = new LinkedHashSet<>();
    for (Map.Entry<Pattern, String> entry : compiledKeywords.entrySet()) {
      if (entry.getKey().matcher(lower).find()) {
        facts.add(entry.getValue());
      }
    }
    return facts;
  }
}
