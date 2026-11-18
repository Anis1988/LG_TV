package com.claude.tvremote;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what the phone heard ("hey LG turn off", "hey LG volume 20", "hey LG open Netflix")
 * into a command. Plain Java, no Android classes, so it can be tested on its own.
 */
final class VoiceParser {
    private VoiceParser() {}

    static final String DEFAULT_WAKE = "hey lg";

    /** A parsed command: what to do plus an optional number or text. */
    static final class Cmd {
        final String type;
        final int num;
        final String text;

        Cmd(String type, int num, String text) {
            this.type = type;
            this.num = num;
            this.text = text;
        }

        @Override
        public String toString() {
            return type + (num != 0 ? "(" + num + ")" : "") + (text != null ? "[" + text + "]" : "");
        }
    }

    // Speech engines spell "LG" many ways; "hey" too.
    private static final String LG_WORDS = "(?:lg|l g|elgie|elgy|elgee|el gee|l gee|el g|algae|ellie g|lgtv|lg tv)";
    private static final String HEY_WORDS = "(?:hey|hi|hay|hey there|ok|okay|hello|a)";

    /** Lowercase, drop punctuation, words for numbers become digits. */
    static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.US)
            .replace("l.g.", "lg").replace("l.g", "lg")
            .replace("+", " plus ")
            .replaceAll("[^a-z0-9% ]+", " ")
            .replaceAll("\\s+", " ").trim();
        return numbersToDigits(t);
    }

    private static Pattern wakePattern(String wake) {
        String w = normalize(wake == null || wake.trim().isEmpty() ? DEFAULT_WAKE : wake);
        StringBuilder re = new StringBuilder("(?:^|\\s)");
        String[] words = w.split(" ");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) re.append("\\s*");
            String word = words[i];
            if (word.equals("lg")) re.append(LG_WORDS);
            else if (word.matches("hey|hi|ok|okay|hello")) re.append(HEY_WORDS);
            else re.append(Pattern.quote(word));
        }
        re.append("(?=\\s|$)");
        return Pattern.compile(re.toString());
    }

    /**
     * If the wake phrase was said, returns what came after it ("" when only the wake
     * phrase was heard). Returns null when the wake phrase wasn't said.
     */
    static String afterWake(String heard, String wake) {
        String n = normalize(heard);
        Matcher m = wakePattern(wake).matcher(n);
        if (!m.find()) return null;
        return n.substring(m.end()).trim();
    }

    /** True if text is the wake phrase and nothing else (so wait for the command). */
    static boolean onlyWake(String heard, String wake) {
        String rest = afterWake(heard, wake);
        return rest != null && rest.isEmpty();
    }

    private static boolean has(String s, String regex) {
        return Pattern.compile("(?:^|\\s)(?:" + regex + ")(?=\\s|$)").matcher(s).find();
    }

    private static int firstNumber(String s) {
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Parses a command with the wake phrase already removed (also works if it's still there). */
    static Cmd parse(String spoken) {
        String s = normalize(spoken);
        // Drop a leading wake phrase and polite filler so "hey lg could you please turn it off" works.
        String rest = afterWake(s, DEFAULT_WAKE);
        if (rest != null) s = rest;
        s = s.replaceAll("^(?:(?:can|could|would|will) you |please |just |now |then |and )+", "")
            .replaceAll(" (?:please|thanks|thank you|now)$", "").trim();
        if (s.isEmpty()) return new Cmd("NONE", 0, null);

        // Sleep timer: "sleep in 30 minutes", "turn off in an hour", "cancel the sleep timer"
        if (has(s, "cancel|stop|turn off|remove|clear") && has(s, "sleep timer|sleep|timer")) return new Cmd("SLEEP_CANCEL", 0, null);
        Matcher sleep = Pattern.compile("(?:sleep|turn off|switch off|shut down|power off|off)(?: the tv| it)?(?: timer)?(?: in| for| after)? (?:(\\d+)|an?|half an?) ?(minutes?|mins?|hours?|hrs?|h)\\b").matcher(s);
        if (sleep.find()) {
            String n = sleep.group(1);
            boolean hours = sleep.group(2).startsWith("h");
            int minutes;
            if (n != null) minutes = Integer.parseInt(n) * (hours ? 60 : 1);
            else if (s.contains("half")) minutes = hours ? 30 : 1;
            else minutes = hours ? 60 : 1;
            return new Cmd("SLEEP", Math.max(1, Math.min(600, minutes)), null);
        }
        Matcher sleep2 = Pattern.compile("^sleep(?: timer)?(?: for| to)? (\\d+)$").matcher(s);
        if (sleep2.find()) return new Cmd("SLEEP", Math.max(1, Math.min(600, Integer.parseInt(sleep2.group(1)))), null);

        // Volume
        boolean volWord = has(s, "volume|sound|louder|quieter|softer|lower|raise|loud|quiet");
        if (volWord || has(s, "turn it up|turn it down|turn up|turn down")) {
            if (has(s, "unmute")) return new Cmd("UNMUTE", 0, null);
            if (has(s, "mute") && !has(s, "unmute")) return new Cmd("MUTE", 0, null);
            int n = firstNumber(s);
            boolean down = has(s, "down|quieter|softer|lower|decrease|reduce|less|quiet");
            boolean up = has(s, "up|louder|raise|increase|more|loud");
            boolean setTo = has(s, "set|to|at|change") || (!up && !down);
            if (n >= 0 && (setTo && !has(s, "by"))) return new Cmd("VOL_SET", Math.min(100, n), null);
            int step = n > 0 ? Math.min(50, n) : 3;
            if (down) return new Cmd("VOL_DOWN", step, null);
            if (up) return new Cmd("VOL_UP", step, null);
        }
        if (has(s, "unmute|sound on|turn the sound on|sound back")) return new Cmd("UNMUTE", 0, null);
        if (has(s, "mute|silence|quiet|shut up|sound off")) return new Cmd("MUTE", 0, null);

        // Screen off (sound keeps playing) before plain power off
        if (has(s, "screen off|turn off the screen|turn the screen off|switch off the screen|blank the screen|picture off"))
            return new Cmd("SCREEN_OFF", 0, null);

        // Power
        if (s.matches("(?:(?:turn|switch|shut|power) (?:the tv |tv |it |everything )?(?:off|down)(?: the tv| tv| it| everything)?|off|shutdown|shut it down|good ?night|go to sleep|tv off|bye|goodbye)"))
            return new Cmd("POWER_OFF", 0, null);
        if (s.matches("(?:(?:turn|switch|power) (?:the tv |tv |it )?on(?: the tv| tv| it)?|on|wake up|tv on|start the tv|wake the tv)"))
            return new Cmd("POWER_ON", 0, null);

        // YouTube search: "search youtube for cats", "play cats on youtube", "youtube cats"
        Matcher yt = Pattern.compile("^(?:search |find |look up )?(?:on )?youtube (?:for |search )?(.+)$").matcher(s);
        if (yt.find() && !yt.group(1).matches("app|please")) return new Cmd("YOUTUBE", 0, yt.group(1));
        Matcher yt2 = Pattern.compile("^(?:search for |search |play |find |look up )(.+?) on youtube$").matcher(s);
        if (yt2.find()) return new Cmd("YOUTUBE", 0, yt2.group(1));
        Matcher yt3 = Pattern.compile("^search youtube for (.+)$").matcher(s);
        if (yt3.find()) return new Cmd("YOUTUBE", 0, yt3.group(1));

        // Inputs: "hdmi 2", "switch to hdmi 1", "input 3"
        Matcher hdmi = Pattern.compile("(?:hdmi|html|h d m i|hdm i|input|source) ?(\\d)").matcher(s);
        if (hdmi.find()) return new Cmd("INPUT", Integer.parseInt(hdmi.group(1)), null);

        // Channels
        if (has(s, "channel up|next channel|channel plus")) return new Cmd("CH_UP", 0, null);
        if (has(s, "channel down|previous channel|last channel|channel minus")) return new Cmd("CH_DOWN", 0, null);
        Matcher ch = Pattern.compile("^(?:go to |switch to |put on |change to )?channel (\\d+)$").matcher(s);
        if (ch.find()) return new Cmd("CH_SET", Integer.parseInt(ch.group(1)), null);

        // Playback
        if (s.matches("(?:hit |press )?(?:pause|pause it|pause the (?:movie|show|video)|hold on|wait)")) return new Cmd("PAUSE", 0, null);
        if (s.matches("(?:hit |press )?(?:play|resume|continue|unpause|keep playing|play it|start playing)")) return new Cmd("PLAY", 0, null);
        if (s.matches("(?:stop|stop it|stop playing|stop the (?:movie|show|video))")) return new Cmd("STOP", 0, null);
        if (has(s, "rewind|go back \\d* ?seconds")) return new Cmd("REWIND", 0, null);
        if (has(s, "fast forward|forward|skip ahead")) return new Cmd("FFWD", 0, null);

        // Remote buttons
        if (s.matches("(?:go |press |hit )?(?:home|home screen|go home|main menu)")) return new Cmd("BUTTON", 0, "HOME");
        if (s.matches("(?:go |press |hit )?(?:back|go back|return)")) return new Cmd("BUTTON", 0, "BACK");
        if (s.matches("(?:press |hit )?(?:ok|okay|select|enter|choose|click|confirm|ok button)")) return new Cmd("BUTTON", 0, "ENTER");
        Matcher dir = Pattern.compile("^(?:go |move |press |hit )?(up|down|left|right)(?: (\\d+)(?: times)?)?$").matcher(s);
        if (dir.find()) return new Cmd("BUTTON", dir.group(2) == null ? 1 : Math.min(10, Integer.parseInt(dir.group(2))), dir.group(1).toUpperCase(Locale.US));
        if (s.matches("(?:open |show )?(?:settings|the settings|menu)")) return new Cmd("BUTTON", 0, "MENU");
        if (s.matches("exit|close|close (?:it|the app|this)|quit")) return new Cmd("BUTTON", 0, "EXIT");

        // Apps: "open netflix", "launch youtube", "put on disney plus", or just "netflix"
        Matcher app = Pattern.compile("^(?:open|launch|start|run|go to|switch to|put on|show me|show|play|watch|i want to watch)(?: the)? (.+?)(?: app)?$").matcher(s);
        if (app.find()) return new Cmd("APP", 0, app.group(1));
        return new Cmd("APP_GUESS", 0, s.replaceAll(" app$", ""));
    }

    // ---- number words ----

    private static final String[] ONES = { "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen" };
    private static final String[] TENS = { "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };

    private static int ones(String w) {
        for (int i = 0; i < ONES.length; i++) if (ONES[i].equals(w)) return i;
        return -1;
    }

    private static int tens(String w) {
        for (int i = 2; i < TENS.length; i++) if (TENS[i].equals(w)) return i * 10;
        return -1;
    }

    /** "volume twenty five" -> "volume 25", "hdmi two" -> "hdmi 2". "to" and "for" are left alone. */
    static String numbersToDigits(String s) {
        String[] w = s.split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            String word = w[i];
            int n = -1;
            if (word.equals("hundred") || word.equals("a hundred")) n = 100;
            else if (tens(word) > 0) {
                n = tens(word);
                if (i + 1 < w.length && ones(w[i + 1]) > 0 && ones(w[i + 1]) < 10) n += ones(w[++i]);
            } else if (ones(word) >= 0) {
                n = ones(word);
                if (i + 1 < w.length && w[i + 1].equals("hundred")) { n *= 100; i++; }
            }
            if (out.length() > 0) out.append(' ');
            out.append(n >= 0 ? String.valueOf(n) : word);
        }
        return out.toString();
    }

    // ---- app name matching ----

    /** Spoken name to a form that compares well with TV app titles ("disney plus" ~ "Disney+"). */
    static String appKey(String name) {
        String n = normalize(name)
            .replaceAll("\\b(?:the|app|tv app)\\b", " ")
            .replace("amazon prime video", "prime video").replace("amazon prime", "prime video").replace("amazon video", "prime video")
            .replace("hbo max", "max").replace("hbo", "max")
            .replace("you tube", "youtube").replace("u tube", "youtube")
            .replace("net flix", "netflix")
            .replace("plus", "")
            .replaceAll("\\s+", "");
        return n;
    }

    /** 0..1: how well a spoken app name matches a TV app title. */
    static double appScore(String spoken, String title) {
        String a = appKey(spoken), b = appKey(title);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (b.startsWith(a) || a.startsWith(b)) return 0.9;
        if (b.contains(a) || a.contains(b)) return 0.8;
        int d = levenshtein(a, b);
        return 1.0 - (double) d / Math.max(a.length(), b.length());
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
