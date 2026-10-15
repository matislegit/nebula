package com.example.nebula;

import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;

/**
 * Wraps the game's text sink and swaps your real username for the fake one as characters stream through.
 * The swapped characters keep the position of the first character of the real name, so code that works with
 * character positions (line wrapping, text box cursors, ...) keeps working on the original text.
 * Only whole words match: "Bob" is replaced in "<Bob> hi" but not inside "Bobby".
 */
public final class NameProtectSink implements FormattedCharSink {
    private final FormattedCharSink out;
    private final int[] real;     // lower-case code points of the real name
    private final int[] fake;     // code points of the replacement
    private final int n;

    // characters that so far match the start of the real name
    private final int[] bufCp;
    private final int[] bufPos;
    private final Style[] bufStyle;
    private int matched;

    private boolean prevName;     // was the previous character part of a word?
    private boolean stopped;

    public NameProtectSink(FormattedCharSink out) {
        this.out = out;
        this.real = NameProtectModule.realLower;
        this.fake = NameProtectModule.fakeCps;
        this.n = real.length;
        this.bufCp = new int[n];
        this.bufPos = new int[n];
        this.bufStyle = new Style[n];
    }

    public boolean isStopped() {
        return stopped;
    }

    private static boolean nameChar(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9') || cp == '_';
    }

    private static int lower(int cp) {
        return Character.toLowerCase(cp);
    }

    @Override
    public boolean accept(int position, Style style, int cp) {
        if (stopped) return false;

        if (matched == n) {                       // the whole name is buffered, waiting to see what comes next
            if (nameChar(cp)) {
                if (!flush()) return false;       // it continues into a longer word, so it's not our name
            } else if (!emitFake()) {
                return false;                     // a real word boundary: swap it
            }
        } else if (matched > 0) {
            if (lower(cp) == real[matched]) {
                push(position, style, cp);
                return true;
            }
            if (!flush()) return false;           // not the name after all
        }

        if (!prevName && lower(cp) == real[0]) {  // a possible start of the name
            push(position, style, cp);
            return true;
        }
        prevName = nameChar(cp);
        return send(position, style, cp);
    }

    /** Call after the last character so a name at the very end of the text is swapped too. */
    public void finish() {
        if (stopped) return;
        if (matched == n && n > 0) emitFake();
        else if (matched > 0) flush();
    }

    private void push(int position, Style style, int cp) {
        bufPos[matched] = position;
        bufStyle[matched] = style;
        bufCp[matched] = cp;
        matched++;
    }

    private boolean flush() {
        int count = matched;
        matched = 0;
        for (int i = 0; i < count; i++) {
            if (!send(bufPos[i], bufStyle[i], bufCp[i])) return false;
        }
        prevName = true;
        return true;
    }

    private boolean emitFake() {
        int position = bufPos[0];
        Style style = bufStyle[0];
        matched = 0;
        for (int cp : fake) {
            if (!send(position, style, cp)) return false;
        }
        prevName = fake.length > 0 && nameChar(fake[fake.length - 1]);
        return true;
    }

    private boolean send(int position, Style style, int cp) {
        boolean ok = out.accept(position, style, cp);
        if (!ok) stopped = true;
        return ok;
    }
}
