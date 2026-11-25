package dev.incusspawn.command;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The mechanical checks of .claude/rules/interface-text.md, run over a flow's transcript as a
 * terminal draws it (#2542), not over string literals, since one message is assembled from
 * several. A line fits its frame, avoids the words the flow never says, shows a step
 * header's number once, and negates the verb rather than the noun. Whether a term was introduced
 * before it is used is not mechanical, and stays with a reviewer.
 *
 * <p>Lines of the test's own ({@code ⟪...⟫}) and bare addresses are not the program's and are not
 * read; what the person typed is cut from the end of its line.
 */
final class TranscriptGate {

    private TranscriptGate() { }

    /** What the transcript shows after a question: the person's Enter, a secret, the end of input. */
    static final Pattern ECHO = Pattern.compile("(⏎|••••|⟪end of input⟫)$");
    /** A step header's counter in its corner, such as {@code [2/3]} or {@code [1/1+]}. */
    private static final Pattern COUNTER = Pattern.compile("\\[\\d+/\\d+\\+?]");
    /** A title that starts with a number as well: {@code 2  GitHub Account ... [2/3]}. */
    private static final Pattern NUMBERED_TITLE = Pattern.compile("^[│\\s]*\\d+[.):]?\\s");

    /** A negated noun: "none", "nothing", "nobody", "no one", or "no" before a word, a quoted one or a file name included. */
    private static final Pattern NEGATED = Pattern.compile(
            "(?i)\\b(?:none|nothing|nobody|no one)\\b|\\bno\\s+(?!longer\\b|more\\b|less\\b)[a-z.'\"]");
    /** What writing.md "Negate the verb" keeps before "no": "has no", "needs no", "there is no". */
    private static final Pattern KEPT_BEFORE_NO = Pattern.compile(
            "(?i)\\b(?:has|have|had|needs|need|needed|there (?:is|are|was|were))\\s+$");
    /** What it keeps before "nothing": the fixed phrases "costs nothing" and "does nothing". */
    private static final Pattern KEPT_BEFORE_NOTHING = Pattern.compile("(?i)\\b(?:costs?|does|do|did)\\s+$");
    /** "Nothing" as the subject, at the start of a sentence, is kept too. */
    private static final Pattern SENTENCE_START = Pattern.compile("(?:^|[.;?!]\\s+)\\W*$");

    /**
     * The negated nouns that are meant, each a label a person chooses or reads back, where a verb
     * would not fit. Each matches a whole line, its borders and indent cut, so the same words in
     * a sentence are still read. A new one is added here with what it labels.
     */
    private static final List<Pattern> MEANT = List.of(
            // The option that leaves a question for later
            Pattern.compile("^\\d+\\.\\s+None for now$"),
            // Its recap above the next header: a hyphen, the question's label, then the answer's column
            Pattern.compile("^-\\s+\\S.*\\S\\s{2,}none for now$"),
            // The answer n to "Use Claude Code?", which chooses no agent at all (#2528)
            Pattern.compile("^n\\s+no agent for now$"));

    /** A line's borders and indent: a box's, a step note's bar. */
    private static final Pattern FRAME = Pattern.compile("^[│┃\\s]+|[│\\s]+$");

    /** A box's own lines, which are as wide as the box; every other line is held to the frame. */
    private static boolean inBox(String line) {
        return line.startsWith("│") || line.startsWith("╭") || line.startsWith("╰");
    }

    /**
     * Every fault in {@code text}, one {@code <flow>:<line> <what>: <line>} each.
     *
     * @param never the words this flow never uses
     * @param until the line from which {@code never} no longer holds, or null for the whole flow
     */
    static List<String> faults(String flow, String text, Pattern never, String until) {
        var faults = new ArrayList<String>();
        var wordsHold = true;
        var lineNo = 0;
        // The sentence a line may continue: the flows wrap by hand, one line at a time
        var previous = "";
        for (var line : text.split("\n", -1)) {
            lineNo++;
            if (until != null && line.contains(until)) wordsHold = false;
            var shown = ECHO.matcher(line).replaceAll("");
            if (shown.strip().startsWith("⟪") || shown.strip().startsWith("https://")) {
                previous = "";
                continue;
            }
            var at = flow + ":" + lineNo + " ";
            var limit = inBox(shown) ? InitTranscriptsTest.BOX : InitTranscriptsTest.WIDTH;
            if (shown.codePointCount(0, shown.length()) > limit) faults.add(at + "wider than the frame: " + line);
            if (wordsHold && never.matcher(shown).find()) faults.add(at + "a word this flow never uses: " + line);
            if (COUNTER.matcher(shown).find() && NUMBERED_TITLE.matcher(shown).find())
                faults.add(at + "a step number shown twice: " + line);
            if (negatesANoun(previous, shown)) faults.add(at + "a negated noun, where the verb should be negated: " + line);
            // A blank line, a box's edge or the person's answer ends the sentence
            var body = body(shown);
            previous = shown.length() != line.length() || shown.startsWith("╭") || shown.startsWith("╰") ? "" : body;
        }
        return faults;
    }

    /** What {@code line} says, its borders and indent cut, or nothing when it is a label that is meant. */
    private static String body(String line) {
        var body = FRAME.matcher(line).replaceAll("");
        for (var meant : MEANT) if (meant.matcher(body).matches()) return "";
        return body;
    }

    static boolean negatesANoun(String line) {
        return negatesANoun("", line);
    }

    /** Whether {@code line} negates a noun, read after {@code previous}, the line whose sentence it may continue. */
    static boolean negatesANoun(String previous, String line) {
        var body = body(line);
        var before = body(previous);
        var text = before.isEmpty() ? body : before + " " + body;
        var from = text.length() - body.length();
        var m = NEGATED.matcher(text);
        while (m.find()) {
            // A negation in the previous line alone was that line's fault
            if (m.end() <= from) continue;
            var ahead = text.substring(0, m.start());
            var nothing = m.group().regionMatches(true, 0, "nothing", 0, 7);
            var no = m.group().regionMatches(true, 0, "no", 0, 2) && Character.isWhitespace(m.group().charAt(2));
            if (no && KEPT_BEFORE_NO.matcher(ahead).find()) continue;
            // "Nothing" capitalized at a line's start begins a sentence, whatever the line above ends with
            var opensLine = m.start() == from && Character.isUpperCase(text.charAt(m.start()));
            if (nothing && (KEPT_BEFORE_NOTHING.matcher(ahead).find() || SENTENCE_START.matcher(ahead).find() || opensLine)) continue;
            return true;
        }
        return false;
    }
}
