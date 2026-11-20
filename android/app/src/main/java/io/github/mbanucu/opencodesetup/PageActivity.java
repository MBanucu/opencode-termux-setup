package io.github.mbanucu.opencodesetup;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A page of this app: a column of text, a terminal for Termux output and a
 * place for problems.
 *
 * <p>Every screen extends it. A page hands a confirmed command to
 * {@link TerminalActivity}, whose page owns the terminal and prints Termux's
 * output there - the install, the removal and the check all report on that one
 * page, and no command raises the Termux window. Results arrive from Termux
 * through {@link RunCommandReceiver} while the page is resumed, and a result that
 * arrives while another page is on top is handed over on the next resume.
 *
 * <p>The terminal is xterm.js, bundled in {@code assets/terminal.html}. It is
 * given the PTY's bytes unchanged, so wrapping, colours, cursor movement and
 * scrollback are the work of the emulator rather than of this file.
 *
 * <p>Nothing here is a dialog: consent, failures and the refusal notice are all
 * part of the page, so there is no overlay to dismiss before reading what
 * happened.
 */
abstract class PageActivity extends Activity implements RunCommandReceiver.Listener {

    private static final int REQUEST_RUN_COMMAND = 7;
    /** The emulator's size as published in the tail report. */
    private static final Pattern COLS = Pattern.compile("\"cols\":(\\d+)");
    private static final Pattern ROWS = Pattern.compile("\"rows\":(\\d+)");
    private static final String TAG = "OpencodeSetup";
    /** The bytes the footer's keys send, as the terminal they reach expects. */
    private static final byte[] ESC = { 0x1b };
    private static final byte[] TAB = { 0x09 };
    private static final byte[] SLASH = { '/' };
    private static final byte[] ARROW_UP = { 0x1b, '[', 'A' };
    private static final byte[] ARROW_DOWN = { 0x1b, '[', 'B' };
    private static final byte[] ARROW_RIGHT = { 0x1b, '[', 'C' };
    private static final byte[] ARROW_LEFT = { 0x1b, '[', 'D' };
    private static final byte[] HOME = { 0x1b, '[', 'H' };
    private static final byte[] END = { 0x1b, '[', 'F' };
    private static final byte[] PG_UP = { 0x1b, '[', '5', '~' };
    private static final byte[] PG_DOWN = { 0x1b, '[', '6', '~' };

    protected LinearLayout column;

    private TextView notice;
    private TextView headerTitle;
    private Button noticeAction;
    private ScrollView page;
    private WebView output;
    private View terminalFrame;
    private ProgressBar busy;
    private Button copyButton;
    private Button ctrlButton;
    private Button keyboardButton;
    /** Ctrl, waiting for the one key it applies to. */
    private volatile boolean ctrlArmed;
    /** Alt, waiting for the one key it applies to. */
    private volatile boolean altArmed;
    /** The Alt key, so arming it can show that it waits. */
    private Button altButton;
    /** Whether the soft keyboard is covering part of the page. */
    private boolean keyboardShown;
    /** The tallest this window has been, which is its height without a keyboard. */
    private int tallestHeight;
    private boolean outputLoaded;
    private boolean fillerDone;
    private boolean fullScreen;
    // What the emulator is actually showing: the PTY is resized to this, so a
    // program that redraws a line with a carriage return fits instead of wrapping.
    private volatile int columns;
    private volatile int rows;
    /** Whether this page's keyboard is wired into the command's stdin. */
    private boolean inputEnabled;
    private volatile boolean streamed;
    /** True while a command runs: accessibility updates wait until it ends. */
    private volatile boolean commandRunning;
    // A command waiting for the terminal to report its size: run as soon as that
    // size arrives, or unconditionally after a few seconds.
    private Runnable pendingSend;
    private final Deque<String> waitingScripts = new ArrayDeque<>();
    private final List<Button> actionButtons = new ArrayList<>();
    private final List<View> collapseOnRun = new ArrayList<>();
    private final LiveStream liveStream = new LiveStream();
    /**
     * The second loopback port: {@code cols rows} lines Termux turns into a
     * resize of the running command's pseudo terminal. Nothing comes back on it.
     */
    private final LiveStream control = new LiveStream();
    /** The size the running command's PTY has, which is what it draws to. */
    private volatile int ptyColumns;
    private volatile int ptyRows;
    private volatile String screen = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(20);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        page = scroll;
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        Diagnostics.log(this, "page:" + getClass().getSimpleName());

        if (!(this instanceof MainActivity)) {
            // The sub-pages sit on top of the main screen; a visible way back
            // beats relying on the gesture alone.
            TextView back = new TextView(this);
            back.setText(getString(R.string.back) + "  ‹");
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            back.setPadding(0, 0, 0, dp(10));
            back.setOnClickListener(v -> finish());
            column.addView(back);
        }

        announceReady();
    }

    /**
     * Says the page is on screen and laid out: once when it is created, and
     * again every time it comes back to the front. The suite waits for this
     * line instead of polling accessibility, which cost it a whole hierarchy
     * snapshot for every page it opened.
     */
    private void announceReady() {
        View content = findViewById(android.R.id.content);
        if (content != null) {
            content.post(() ->
                    Diagnostics.log(this, "page:" + getClass().getSimpleName() + " ready"));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A page that runs no command of its own must not receive one either: a
        // result for a page that was covered when its command finished would
        // otherwise be printed into a terminal that is measuring something else.
        RunCommandReceiver.setListener(listensForResults() ? this : null);
        // A page that is brought forward again was already laid out; this line
        // is how the suite knows the tap has somewhere to land.
        announceReady();
    }

    /** Whether this page should receive a command's result while it is on top. */
    protected boolean listensForResults() {
        return true;
    }

    /**
     * Switches this page to a terminal-first layout.
     *
     * <p>A header with the way back, the terminal taking every pixel between it
     * and the footer, and a footer with the keys a terminal needs - Ctrl and the
     * keyboard - plus a small copy: after a command has been confirmed, the
     * output is the only thing left to look at.
     */
    protected void terminalPage(CharSequence title) {
        fullScreen = true;
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // No page margin: the terminal and the key strip under it run to the
        // edges the way Termux draws them, and the columns a margin used to take
        // are columns the program on the other side of the PTY gets.
        root.setPadding(0, 0, 0, 0);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, pad, pad, 0);

        TextView back = new TextView(this);
        back.setText(getString(R.string.back) + "  ‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        back.setPadding(0, 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerTitle = heading;
        root.addView(header);

        column = root;
        page = null;
        setContentView(root);
        // The keyboard covering part of the page is how the footer's key learns
        // whether tapping it shows or hides.
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::keyboardChanged);
    }

    /**
     * Fills this page's terminal with lines, when a test asks for it.
     *
     * <p>A clipping bug at the bottom edge only shows on a terminal that is full,
     * and the app's own commands are either two lines or a two-minute install.
     * {@code --ei filler_lines 200} makes the page write that many lines once, so
     * the end-to-end test can measure a full terminal at any height. Only pages
     * that actually own a terminal call this.
     */
    protected void fillTerminal(int lines) {
        if (lines <= 0 || fillerDone) {
            return;
        }
        fillerDone = true;
        outputBox();
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= lines; line++) {
            text.append("line ").append(line).append(" of ").append(lines).append('\n');
        }
        write(text.toString());
        // Shown, but not revealed: reveal() scrolls the page to the terminal, and
        // a tall one then pushes the page's actions out of the view hierarchy.
        // The measurement reads the DOM, which needs layout, not a visible
        // terminal on screen.
        if (terminalFrame != null) {
            terminalFrame.setVisibility(View.VISIBLE);
            terminalFrame.post(() -> script("fitNow()"));
        }
    }

    /** Replaces the header title of a terminal page that is being reused. */
    protected void retitle(CharSequence title) {
        if (headerTitle != null) {
            headerTitle.setText(title);
        }
    }

    /**
     * Resizes the terminal to the height pinned in the page's current intent.
     *
     * <p>A test that measures several heights hands over a new intent instead of
     * a new activity, so the existing frame is resized and re-fitted; creating
     * the page again was what tore the task down between cases.
     */
    protected void pinTerminalHeight() {
        View frame = terminalFrame;
        if (frame == null) {
            return;
        }
        float fraction = getIntent() == null
                ? 0f : getIntent().getFloatExtra("terminal_fraction", 0f);
        if (fraction <= 0f) {
            return;
        }
        ViewGroup.LayoutParams params = frame.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            // A weighted frame is given whatever space is left, so a height on its
            // own would be measured and then ignored; pinning has to take the
            // weight away first, or nothing moves and no resize is reported.
            ((LinearLayout.LayoutParams) params).weight = 0f;
        }
        params.height = Math.max(dp(120),
                Math.round(getResources().getDisplayMetrics().heightPixels * fraction));
        frame.setLayoutParams(params);
        frame.post(() -> script("fitNow()"));
    }

    /** Empties the terminal so a probe or a filler starts from a clean screen. */
    protected void clearTerminal() {
        fillerDone = false;
        script("resetTerm()");
    }

    /** Lets a reused page measure itself again for its new intent. */
    protected void thawTerminal() {
        script("thawFit()");
    }

    /** Drops the command this page is showing, so a new intent can take its place. */
    protected void abandonCommand() {
        pendingSend = null;
        stopListening();
        running(false);
        RunCommandReceiver.setListener(listensForResults() ? this : null);
    }

    @Override
    protected void onPause() {
        RunCommandReceiver.setListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopListening();
        if (output != null) {
            column.removeView(output);
            output.destroy();
            output = null;
        }
        super.onDestroy();
    }

    // --- pieces of a page ----------------------------------------------------

    protected TextView heading(int label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(view);
        return view;
    }

    protected TextView body(int label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setPadding(0, dp(6), 0, dp(16));
        column.addView(view);
        return view;
    }

    protected TextView bold(int label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, 0, 0, dp(16));
        column.addView(view);
        return view;
    }

    protected Button button(int label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(onClick);
        column.addView(button, buttonParams());
        actionButtons.add(button);
        return button;
    }

    /** The one action a page is for: filled, so the page has a clear next step. */
    protected Button primaryButton(int label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF232A34);
        shape.setCornerRadius(dp(10));
        button.setBackground(shape);
        button.setOnClickListener(onClick);
        column.addView(button, buttonParams());
        actionButtons.add(button);
        return button;
    }

    protected LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        return params;
    }

    /** A command shown the way the terminal prints it: dark, monospace, rounded. */
    protected TextView mono(CharSequence text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTextColor(0xFFDCDCDC);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF161B22);
        shape.setCornerRadius(dp(10));
        shape.setStroke(dp(1), 0xFF2A303A);
        view.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        params.bottomMargin = dp(4);
        column.addView(view, params);
        return view;
    }

    /** Hides a page's preamble once its command is on its way. */
    protected void collapseOnRun(View view) {
        collapseOnRun.add(view);
    }

    /**
     * The terminal this page prints Termux output into: the xterm.js emulator
     * loaded from the app's own assets, framed like a window in the page. The
     * page never navigates anywhere and the contents are never fetched from a
     * network, so the WebView is only a canvas for that asset.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected WebView outputBox() {
        if (output == null) {
            WebView web = new WebView(this);
            web.setId(R.id.output);
            web.setBackgroundColor(0xFF101418);
            // A page that takes typing is the exception: the keyboard is how its
            // bytes reach the PTY. Everything else stays unfocusable, so a tap
            // cannot raise a keyboard the command would have no use for.
            inputEnabled = getIntent() != null
                    && getIntent().getBooleanExtra("terminal_input", false);
            web.setFocusable(inputEnabled);
            web.setFocusableInTouchMode(inputEnabled);

            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setBlockNetworkLoads(true);
            settings.setAllowContentAccess(false);

            // The only bridge into the page: the emulator's screen text, so a
            // screen reader and the end-to-end test can read what it shows.
            web.addJavascriptInterface(new Screen(), "AndroidBridge");
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    outputLoaded = true;
                    while (true) {
                        String next;
                        synchronized (waitingScripts) {
                            next = waitingScripts.pollFirst();
                        }
                        if (next == null) {
                            break;
                        }
                        view.evaluateJavascript(next, null);
                    }
                    view.evaluateJavascript("fitNow()", null);
                }
            });

            // A page ScrollView would otherwise steal the terminal's own scroll.
            web.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            });

            // Square and unlidded: every pixel the rounded frame and its inset
            // used to spend on shape is a cell the program on the other side of
            // the PTY gets to draw on.
            FrameLayout frame = new FrameLayout(this);
            frame.addView(web, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            frame.setVisibility(View.GONE);
            terminalFrame = frame;

            // A test can pin the height through the intent, so the same terminal
            // can be measured tall and short on one device.
            float fraction = getIntent() == null
                    ? 0f : getIntent().getFloatExtra("terminal_fraction", 0f);
            LinearLayout.LayoutParams params;
            if (fullScreen) {
                // Everything between the header and the footer. A pinned height
                // only exists for the test that compares several of them.
                params = fraction > 0f
                        ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                Math.max(dp(120), Math.round(
                                        getResources().getDisplayMetrics().heightPixels * fraction)))
                        : new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
                params.topMargin = dp(8);
            } else {
                int height = fraction > 0f
                        ? Math.max(dp(120), Math.round(
                                getResources().getDisplayMetrics().heightPixels * fraction))
                        : Math.max(dp(300), Math.round(
                                getResources().getDisplayMetrics().heightPixels * 0.45f));
                params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, height);
                params.topMargin = dp(12);
                params.bottomMargin = dp(8);
            }
            // The progress strip goes above the terminal, not below it and not
            // over it: below a full-screen page it was off the screen, and laid
            // over the terminal it cut the tops off the program's first row. It
            // keeps its four pixels while it is idle, because a strip that
            // appeared as a sibling would shorten the terminal when a command
            // started - after the PTY had been sized to the taller grid, so a
            // row would go missing.
            busy = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            busy.setIndeterminate(true);
            busy.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
            progressParams.bottomMargin = dp(4);
            column.addView(busy, progressParams);

            column.addView(frame, params);

            if (fullScreen) {
                // Footer: the two rows this device's Termux is configured with,
                // key for key, because a phone keyboard has neither Escape, Tab,
                // the arrows nor the page keys a TUI and a shell are driven with.
                // Eight columns in both rows, so the keys line up as a grid: the
                // burger key is left out, and the copy symbol takes the dash's
                // cell rather than a new column. There is no cancel: Back in the
                // header already leaves, and it only ever left the command
                // running in Termux, which this app cannot stop.
                LinearLayout keys = new LinearLayout(this);
                keys.setOrientation(LinearLayout.VERTICAL);
                keys.setBackgroundColor(0xFF0D1116);
                if (inputEnabled) {
                    LinearLayout top = keyRow();
                    top.addView(terminalKey(R.string.key_esc, () -> type(ESC)), keyParams(1f));
                    top.addView(terminalKey(R.string.key_slash, () -> type(SLASH)), keyParams(1f));
                    top.addView(terminalKey(R.string.key_paste, this::paste), keyParams(1f));
                    // Each key sits over its partner: Home over Left, Up over Down,
                    // End over Right and Page up over Page down, so both rows read
                    // as one grid of columns.
                    top.addView(terminalKey(R.string.key_home, () -> type(HOME)), keyParams(1f));
                    top.addView(terminalKey(R.string.key_up, () -> type(ARROW_UP)), keyParams(1f));
                    top.addView(terminalKey(R.string.key_end, () -> type(END)), keyParams(1f));
                    top.addView(terminalKey(R.string.key_pgup, () -> type(PG_UP)), keyParams(1f));
                    top.addView(copyKey(), keyParams(1f));

                    LinearLayout bottom = keyRow();
                    // Tab gets a symbol too: a terminal's extra keys draw it as
                    // ↹, and the word was the widest label in the row.
                    Button tab = terminalKey(R.string.key_tab_glyph, () -> type(TAB));
                    tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    tab.setContentDescription(getString(R.string.key_tab));
                    bottom.addView(tab, keyParams(1f));
                    ctrlButton = terminalKey(R.string.key_ctrl, () -> setCtrlArmed(!ctrlArmed));
                    bottom.addView(ctrlButton, keyParams(1f));
                    altButton = terminalKey(R.string.key_alt, () -> setAltArmed(!altArmed));
                    bottom.addView(altButton, keyParams(1f));
                    bottom.addView(terminalKey(R.string.key_left, () -> type(ARROW_LEFT)), keyParams(1f));
                    bottom.addView(terminalKey(R.string.key_down, () -> type(ARROW_DOWN)), keyParams(1f));
                    bottom.addView(terminalKey(R.string.key_right, () -> type(ARROW_RIGHT)), keyParams(1f));
                    bottom.addView(terminalKey(R.string.key_pgdn, () -> type(PG_DOWN)), keyParams(1f));
                    keyboardButton = terminalKey(R.string.key_keyboard_glyph,
                            this::toggleKeyboard);
                    keyboardButton.setContentDescription(getString(R.string.key_keyboard));
                    bottom.addView(keyboardButton, keyParams(1f));

                    keys.addView(top, keyRowParams());
                    keys.addView(bottom, keyRowParams());
                } else {
                    // A page whose output is only read keeps the rows' height, so
                    // the terminal does not shrink when a command starts.
                    keys.addView(keyRow(), keyRowParams());
                    keys.addView(keyRow(), keyRowParams());
                }
                column.addView(keys, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            } else {
                copyButton = new Button(this);
                copyButton.setText(R.string.copy_output);
                copyButton.setAllCaps(false);
                copyButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                copyButton.setOnClickListener(v -> copy(screen));
                // Only meaningful once there is output; hidden until then, so it
                // does not sit on a page that has run nothing.
                copyButton.setVisibility(View.GONE);
                column.addView(copyButton, buttonParams());
            }

            output = web;
            // The font travels in the URL so it is in place before the terminal
            // measures its first grid. The PTY is sent that grid, and a font set
            // after the command starts would leave it drawing to the old width.
            int font = getIntent() == null ? 0 : getIntent().getIntExtra("terminal_font", 0);
            String query = font > 0 ? "?font=" + font : "";
            if (inputEnabled) {
                query += (query.isEmpty() ? "?" : "&") + "input=1";
            }
            web.loadUrl("file:///android_asset/terminal.html" + query);
        }
        return output;
    }

    /**
     * One key of the terminal's footer, drawn the way Termux draws its extra
     * keys: a flat label on the strip's own colour, no box around it, as wide as
     * the share of the row it is given.
     */
    private Button key(int label) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTextColor(0xFFDCDCDC);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        view.setPadding(0, 0, 0, 0);
        view.setBackground(keyShape(false));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    /**
     * The box a key sits in.
     *
     * <p>Termux's extra keys are buttons, not bare labels: a fill a shade above
     * the strip and a hairline border, with a small gap between keys, so the
     * rows read as a grid of keys instead of a line of words. An armed Ctrl or
     * Alt takes the accent fill and keeps its border.
     */
    private GradientDrawable keyShape(boolean armed) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(armed ? 0xFF2F6FB2 : 0xFF1A2028);
        shape.setCornerRadius(dp(3));
        shape.setStroke(Math.max(1, dp(1)), armed ? 0xFF5A9BD8 : 0xFF2C343D);
        return shape;
    }

    /** One row of the footer's extra keys, at Termux's strip height. */
    private LinearLayout keyRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    /** The height of one such row; two of them stack above the keyboard. */
    private LinearLayout.LayoutParams keyRowParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
    }

    /** One footer key: no box, monospace, as wide as its share of the row. */
    private Button terminalKey(int label, Runnable action) {
        Button view = key(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    /**
     * Pastes the clipboard into the running command, the way Termux's PASTE key
     * does.
     *
     * <p>A terminal's Enter is a carriage return, so a line feed in the
     * clipboard is sent as one; left as a line feed it would be a control
     * character the program prints instead of a new line. Ctrl and Alt do not
     * apply to a paste.
     */
    private void paste() {
        if (!inputEnabled) {
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            return;
        }
        byte[] bytes = text.toString().replace("\r\n", "\n").replace('\n', '\r')
                .getBytes(StandardCharsets.UTF_8);
        // Only the size: a clipboard may hold anything.
        Diagnostics.log(this, "paste:" + bytes.length);
        liveStream.send(bytes);
    }

    /**
     * The copy key: a symbol rather than a word, so it takes one cell of the
     * strip like the terminal keys do, and dimmer than them because copying is
     * not what the page is for.
     */
    private Button copyKey() {
        Button view = key(R.string.copy_glyph);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTextColor(0xFF7C8792);
        view.setContentDescription(getString(R.string.copy_short));
        view.setOnClickListener(v -> copy(screen));
        return view;
    }

    /** A footer child: an equal share of the row, with the gap between keys. */
    private LinearLayout.LayoutParams keyParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                weight > 0f ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                weight);
        int gap = dp(2);
        params.setMargins(gap, gap, gap, gap);
        return params;
    }

    /**
     * Arms Ctrl for exactly one key, and shows that it is waiting.
     *
     * <p>Termux fills its Ctrl key while it is held; the fill is the only shape
     * the strip has, and it is square for the same reason the keys are flat.
     */
    private void setCtrlArmed(boolean armed) {
        ctrlArmed = armed;
        showArmed(ctrlButton, armed);
    }

    /** Arms Alt for exactly one key, and shows that it is waiting. */
    private void setAltArmed(boolean armed) {
        altArmed = armed;
        showArmed(altButton, armed);
    }

    /** Fills a waiting modifier, the way Termux fills its Ctrl key while held. */
    private void showArmed(Button key, boolean armed) {
        if (key == null) {
            return;
        }
        key.setBackground(keyShape(armed));
        key.setTextColor(armed ? 0xFFFFFFFF : 0xFFDCDCDC);
    }

    /**
     * Shows or hides the keyboard under the terminal.
     *
     * <p>Termux has a key for this, and so does this page: the keyboard covers
     * rows a full-screen program is drawing on, and hiding it is how those rows
     * come back without leaving the page.
     */
    private void toggleKeyboard() {
        WebView box = output;
        InputMethodManager ime =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (box == null || ime == null) {
            return;
        }
        if (keyboardOpen(box)) {
            // xterm types into a textarea of its own and the IME is anchored to
            // it: hiding the window alone leaves the keyboard up, because the
            // focused element is still there asking for it. Blur it first.
            script("blurTerm()");
            box.clearFocus();
            ime.hideSoftInputFromWindow(box.getWindowToken(), 0);
            keyboardShown = false;
            Diagnostics.log(this, "keyboard:hide");
            return;
        }
        box.requestFocus();
        // xterm collects keystrokes in a textarea of its own; focusing the web
        // view alone leaves the keyboard with no element to type into.
        script("focusTerm()");
        ime.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
        Diagnostics.log(this, "keyboard:show");
    }

    /**
     * Whether the keyboard covers part of this page right now.
     *
     * <p>Asked of the window rather than remembered: the keyboard can be closed
     * by the system's back arrow or by the page itself, and a flag that missed
     * either would turn the key into a one-way switch.
     */
    private boolean keyboardOpen(WebView box) {
        WindowInsets insets = box.getRootWindowInsets();
        if (insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.isVisible(WindowInsets.Type.ime());
        }
        return keyboardShown;
    }

    /**
     * Notices the keyboard coming and going, so the footer's key still does the
     * right thing after the keyboard was dismissed by hand.
     */
    private void keyboardChanged() {
        View content = findViewById(android.R.id.content);
        if (content == null || content.getHeight() <= 0) {
            return;
        }
        // Measured against the tallest this window has been, not against the
        // visible display frame: this page resizes for the keyboard, so the frame
        // and the window shrink together and their difference stays zero.
        int height = content.getHeight();
        tallestHeight = Math.max(tallestHeight, height);
        keyboardShown = tallestHeight - height > dp(120);
    }

    /** Shows a problem on the page, with an optional action button. */
    protected void notice(CharSequence text, int actionLabel, View.OnClickListener action) {
        ensureNotice();
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        if (actionLabel == 0 || action == null) {
            noticeAction.setVisibility(View.GONE);
            return;
        }
        noticeAction.setText(actionLabel);
        noticeAction.setOnClickListener(action);
        noticeAction.setVisibility(View.VISIBLE);
    }

    protected void notice(int title, int text, int actionLabel, View.OnClickListener action) {
        notice(getString(title) + "\n\n" + getString(text), actionLabel, action);
    }

    protected void clearNotice() {
        ensureNotice();
        notice.setVisibility(View.GONE);
        noticeAction.setVisibility(View.GONE);
    }

    private void ensureNotice() {
        if (notice != null) {
            return;
        }
        notice = new TextView(this);
        notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        notice.setTypeface(Typeface.DEFAULT_BOLD);
        notice.setPadding(0, dp(16), 0, dp(8));
        notice.setTextIsSelectable(true);
        notice.setVisibility(View.GONE);
        column.addView(notice);

        noticeAction = new Button(this);
        noticeAction.setText(R.string.permission_settings);
        noticeAction.setAllCaps(false);
        noticeAction.setVisibility(View.GONE);
        column.addView(noticeAction, buttonParams());
    }

    // --- running a command ---------------------------------------------------

    /**
     * Writes base64 PTY bytes straight into the terminal, for the rendering
     * tests. A terminal interprets a carriage return, an erase or a colour
     * instead of printing it, so the only honest way to test that is to hand it
     * the bytes and read the screen it publishes afterwards.
     */
    protected void writeProbeToTerminal(String base64Bytes) {
        outputBox();
        showTerminalFrame();
        write(new String(Base64.decode(base64Bytes, Base64.NO_WRAP), StandardCharsets.UTF_8));
        if (terminalFrame != null) {
            terminalFrame.post(() -> script("fitNow()"));
        }
    }

    /** Shows the terminal and its copy button without waiting for a result. */
    private void showTerminalFrame() {
        if (terminalFrame != null) {
            terminalFrame.setVisibility(View.VISIBLE);
        }
        if (copyButton != null) {
            copyButton.setVisibility(View.VISIBLE);
            copyButton.setEnabled(true);
        }
    }

    /**
     * Asks for Termux's RUN_COMMAND permission without sending anything, so the
     * request can happen on the page that explains it instead of in the middle of
     * the first command.
     */
    protected void askForTermuxPermission() {
        pending = null;
        requestPermissions(new String[] {TermuxCommands.PERMISSION}, REQUEST_RUN_COMMAND);
    }

    /**
     * Sends one command to Termux, asking for its permission first if needed.
     * Whatever comes back is printed in this page's terminal.
     */
    protected void runCommand(String command, String label, String description) {
        if (checkSelfPermission(TermuxCommands.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "asking for " + TermuxCommands.PERMISSION);
            // RUN_COMMAND is a dangerous permission owned by Termux; without it
            // startService throws and the reason looks like a settings problem.
            pending = () -> send(command, label, description);
            requestPermissions(new String[] {TermuxCommands.PERMISSION}, REQUEST_RUN_COMMAND);
            return;
        }
        send(command, label, description);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != REQUEST_RUN_COMMAND) {
            return;
        }
        Runnable next = pending;
        pending = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && next != null) {
            next.run();
        } else {
            notice(R.string.permission_denied_title, R.string.permission_denied_message,
                    R.string.permission_settings, v -> openSettings());
        }
    }

    private void send(String command, String label, String description) {
        send(command, label, description, false);
    }

    private void send(String command, String label, String description, boolean force) {
        // The terminal is created before the size wait, not after the send: it is
        // what reports the size, and creating it later meant waiting for a report
        // that could not arrive until the command had already gone out. It also
        // has to be visible while it measures - a hidden view is zero cells wide
        // and reports nothing, which left the wait to time out.
        outputBox();
        if (terminalFrame != null) {
            terminalFrame.setVisibility(View.VISIBLE);
        }
        if (columns <= 0 && !force) {
            // The emulator reports its size on its first tick, just after the page
            // opens. Wait for that instead of guessing a width, but never hold
            // the command for longer than a few seconds.
            pendingSend = () -> send(command, label, description, true);
            ui.postDelayed(() -> {
                Runnable ready = pendingSend;
                pendingSend = null;
                if (ready != null) {
                    ready.run();
                }
            }, 10_000);
            return;
        }
        pendingSend = null;
        int port = liveStream.open(chunk -> {
            // Only live chunks are logged this way: the final result is a
            // different path, so a "stream:" line always means output arrived
            // while the command was still running.
            Diagnostics.log(this, "stream:" + chunk.length());
            streamed = true;
            write(chunk);
        });
        try {
            int controlPort = control.open(chunk -> { });
            TermuxCommands.send(this, command, label, description, port, columns, rows,
                    inputEnabled, controlPort);
            // The PTY was given this grid; anything else is a resize to follow.
            ptyColumns = columns;
            ptyRows = rows;
            // The PTY has this grid now: the page keeps it, re-fits it when the
            // window moves (the keyboard opening, for one) and reports the
            // result, which is the size sent to the PTY below.
            script("lockFit()");
            Diagnostics.log(this, "sent " + label + " port="
                    + (port == 0 ? "none" : port)
                    + " pty=" + columns + "x" + rows);
            clearNotice();
            if (port > 0) {
                startOutput();
            }
        } catch (RuntimeException refused) {
            stopListening();
            running(false);
            Diagnostics.log(this, "refused: " + refused);
            Log.w(TAG, "Termux refused the command", refused);
            copy(TermuxCommands.ALLOW_EXTERNAL_APPS);
            notice(getString(R.string.permission_title) + "\n\n"
                            + getString(R.string.permission_message) + "\n\n"
                            + TermuxCommands.ALLOW_EXTERNAL_APPS,
                    R.string.permission_settings, v -> openSettings());
        }
    }

    @Override
    public void onCommandResult(String text) {
        Diagnostics.log(this, "result: " + text.replace("\n", " | "));
        stopListening();
        running(false);
        if (streamed) {
            // The emulator already shows the PTY's own rendering, progress
            // redraws and colours included. Resetting it to the sanitized bundle
            // would strip all of that and print every redraw as its own line, so
            // only the verdict is added.
            int verdict = text.lastIndexOf("exit code ");
            write("\n" + (verdict < 0 ? text : text.substring(verdict)) + "\n");
        } else {
            script("resetTerm()");
            write(text);
        }
        reveal();
    }

    /**
     * Locks the page's actions while a command runs: a second tap used to be
     * able to start a second apt transaction on top of the first.
     */
    protected void running(boolean active) {
        commandRunning = active;
        if (!active && output != null && screen != null) {
            // The stream has stopped: the finished screen goes to accessibility
            // once, where a screen reader can read it in full.
            output.setContentDescription(screen);
        }
        for (Button button : actionButtons) {
            button.setEnabled(!active);
        }
        if (busy != null) {
            // Invisible, not gone: the strip holds its rows so the terminal does
            // not have to be re-measured when a command starts or ends.
            busy.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        }
        for (View view : collapseOnRun) {
            view.setVisibility(active ? View.GONE : View.VISIBLE);
        }
    }

    // --- helpers -------------------------------------------------------------

    protected void openSettings() {
        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("opencode-setup", text));
        }
    }

    /** Hands one chunk of the PTY's output to the emulator, unchanged. */
    private void write(String chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        String encoded = Base64.encodeToString(
                chunk.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        script("writeB64('" + encoded + "')");
    }

    /**
     * Runs a script in the terminal page. Before the asset has loaded the calls
     * are queued, so output that arrives early is not lost.
     */
    private void script(String javascript) {
        if (!outputLoaded) {
            synchronized (waitingScripts) {
                waitingScripts.add(javascript);
            }
            return;
        }
        WebView box = output;
        if (box == null) {
            return;
        }
        box.post(() -> box.evaluateJavascript(javascript, null));
    }

    private void reveal() {
        WebView box = output;
        View frame = terminalFrame;
        if (box == null || frame == null) {
            return;
        }
        frame.setVisibility(View.VISIBLE);
        if (copyButton != null) {
            copyButton.setVisibility(View.VISIBLE);
            copyButton.setEnabled(true);
        }
        frame.post(() -> {
            script("fitNow()");
            ScrollView scroller = page;
            if (scroller == null) {
                // The terminal-first page has no fold to scroll past.
                return;
            }
            // Scroll so the terminal's *last* line is on screen, not just its
            // top: a tall terminal, or a landscape screen, otherwise ends below
            // the fold and the newest output is never seen as it arrives.
            int viewport = scroller.getHeight();
            int target = viewport <= 0
                    ? frame.getTop()
                    : Math.min(frame.getTop(),
                            Math.max(0, frame.getBottom() - viewport + dp(8)));
            scroller.smoothScrollTo(0, target);
        });
    }

    /**
     * The file this page publishes its screen to.
     *
     * <p>{@code live-output.txt} by default, so adb readers keep working; a
     * caller can name its own with a {@code screen_file} extra, which is how a
     * test stops reading another page's screen by accident.
     */
    private File screenFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            return null;
        }
        String name = getIntent().getStringExtra("screen_file");
        return new File(dir, name == null || name.isEmpty() ? "live-output.txt" : name);
    }

    private void startOutput() {
        outputBox();
        running(true);
        screen = "";
        streamed = false;
        script("resetTerm()");
        String waiting = getString(R.string.output_waiting);
        script("waitingB64('" + Base64.encodeToString(
                waiting.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "')");
        File file = screenFile();
        if (file != null) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(waiting.getBytes(StandardCharsets.UTF_8));
            } catch (IOException unwritable) {
                Log.w(TAG, "could not reset the live output file", unwritable);
            }
        }
        reveal();
    }

    private void stopListening() {
        liveStream.close();
        control.close();
    }

    /**
     * Publishes the text the terminal is showing: to the accessibility tree for
     * a screen reader, and to {@code live-output.txt} for adb.
     */
    private final class Screen {
        /**
         * Where the emulator's last line ends, so the placement can be checked
         * without a screenshot: {@code tail:{"fits":true,...}}.
         */
        @JavascriptInterface
        public void onTail(String json) {
            // Repeated here so whoever reads this line gets the screen and the
            // grid it was drawn on together; a size change announces itself on
            // {@link Screen#onResize} the moment it happens.
            java.util.regex.Matcher cols = COLS.matcher(json);
            java.util.regex.Matcher rows = ROWS.matcher(json);
            if (cols.find() && rows.find()) {
                int reportedColumns = Integer.parseInt(cols.group(1));
                int reportedRows = Integer.parseInt(rows.group(1));
                // Before its first fit the emulator reports a couple of cells.
                // Sending a command at that width is worse than waiting.
                if (reportedColumns >= 20 && reportedRows >= 5) {
                    // The first reported size is the one the page keeps: it
                    // freezes its grid as soon as the command is sent, so a row
                    // cannot be trimmed out from under a TUI's status bar.
                    columns = reportedColumns;
                    // Qualified: "rows" above is the matcher, not the field.
                    PageActivity.this.rows = reportedRows;
                }
            }
            Runnable ready = pendingSend;
            if (ready != null) {
                pendingSend = null;
                ui.post(ready);
            }
            Diagnostics.log(PageActivity.this, "tail:" + json);
        }

        /**
         * The grid and the window around it, sent the moment either moves.
         *
         * <p>These numbers are what the next command is sized to, so they are
         * recorded even while a command runs. The page re-fits a frozen grid
         * before reporting it, so {@code frozen} only says the size belongs to a
         * running command: a grid the pseudo terminal does not have is the
         * window having moved, and the PTY is told the same numbers so the
         * program redraws to fit instead of drawing into rows nobody can see.
         */
        @JavascriptInterface
        public void onResize(String json) {
            java.util.regex.Matcher cols = COLS.matcher(json);
            java.util.regex.Matcher rows = ROWS.matcher(json);
            int reportedColumns = -1;
            int reportedRows = -1;
            if (cols.find() && rows.find()) {
                // The same guard the screen report uses: before its first fit
                // the emulator is a couple of cells wide, and a command sent at
                // that width is worse than one sent at the size before it.
                if (Integer.parseInt(cols.group(1)) >= 20
                        && Integer.parseInt(rows.group(1)) >= 5) {
                    reportedColumns = Integer.parseInt(cols.group(1));
                    reportedRows = Integer.parseInt(rows.group(1));
                    columns = reportedColumns;
                    // Qualified: "rows" above is the matcher, not the field.
                    PageActivity.this.rows = reportedRows;
                }
            }
            Diagnostics.log(PageActivity.this, "resize:" + json);
            // One rule: a grid the running command's PTY does not have is the
            // window having moved, and the page has already re-fitted its grid
            // to match before reporting it.
            if (reportedColumns < 0 || !json.contains("\"frozen\":true")) {
                return;
            }
            if (commandRunning && (reportedColumns != ptyColumns || reportedRows != ptyRows)) {
                resizePty(reportedColumns, reportedRows);
            }
        }

        /**
         * Tells Termux the size the pseudo terminal has to take now.
         *
         * <p>The PTY was made by {@code script}, so this app cannot reach it; a
         * watcher on Termux's side does the resize, and the kernel interrupts the
         * program with SIGWINCH - the same thing a terminal does when its
         * keyboard opens and the view gets shorter.
         */
        private void resizePty(int cols, int rows) {
            if (cols < 20 || rows < 5 || !control.connected()) {
                return;
            }
            ptyColumns = cols;
            ptyRows = rows;
            control.send((cols + " " + rows + "\n").getBytes(StandardCharsets.UTF_8));
            Diagnostics.log(PageActivity.this, "pty-resize:" + cols + "x" + rows);
        }

        /** A keystroke from the page's keyboard, on its way to the PTY. */
        @JavascriptInterface
        public void onInput(String base64) {
            byte[] bytes = control(Base64.decode(base64, Base64.NO_WRAP));
            // Only the size: what a user types into an agent can be private.
            Diagnostics.log(PageActivity.this, "input:" + bytes.length);
            liveStream.send(bytes);
        }

        @JavascriptInterface
        public void onScreen(String text) {
            screen = text;
            Diagnostics.log(PageActivity.this, "screen:" + text.length());
            // A covered page keeps its web view timers running, so it would go on
            // writing the one file every page shares - and the page in front
            // would find the previous phase's screen instead of its own.
            if (!hasWindowFocus()) {
                return;
            }
            File file = screenFile();
            if (file != null) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                } catch (IOException unwritable) {
                    Log.w(TAG, "could not write live output", unwritable);
                }
            }
            // While a command streams, this is four accessibility events a
            // second, and the stream never goes quiet - which is what UiAutomator
            // waits for before it taps, so every click then waited out its idle
            // timeout. The last screen is published once, when the command ends.
            ui.post(() -> {
                if (output != null && !commandRunning) {
                    output.setContentDescription(text);
                }
            });
        }
    }

    /**
     * Sends one of the footer's keys to the PTY, with Ctrl and Alt applied.
     *
     * <p>These are the keys a phone keyboard cannot type: a TUI that never sees
     * Escape, Tab or an arrow cannot be navigated at all. Termux's extra-keys
     * row exists for the same reason, and the modifiers behave the same way -
     * one key, then spent.
     */
    private void type(byte[] bytes) {
        if (!inputEnabled || bytes.length == 0) {
            return;
        }
        byte[] sent = control(bytes);
        Diagnostics.log(this, "key:" + sent.length);
        liveStream.send(sent);
    }

    /**
     * The key typed while Ctrl or Alt was armed, with that modifier applied.
     *
     * <p>Each modifier is spent on that one key, whether or not it changes it,
     * the way a terminal's own modifier behaves: Ctrl names a control code, and
     * Alt is the escape prefix xterm sends in front of the key.
     */
    private byte[] control(byte[] typed) {
        boolean ctrl = ctrlArmed;
        boolean alt = altArmed;
        if ((!ctrl && !alt) || typed.length == 0) {
            return typed;
        }
        if (ctrl) {
            ui.post(() -> setCtrlArmed(false));
        }
        if (alt) {
            ui.post(() -> setAltArmed(false));
        }
        byte[] out = typed;
        if (ctrl) {
            int code = controlCode((char) typed[0]);
            if (code >= 0) {
                out = typed.clone();
                out[0] = (byte) code;
            }
        }
        if (alt) {
            byte[] prefixed = new byte[out.length + 1];
            prefixed[0] = 0x1b;
            System.arraycopy(out, 0, prefixed, 1, out.length);
            out = prefixed;
        }
        return out;
    }

    /** The code Ctrl turns a character into, or -1 when it leaves it alone. */
    private static int controlCode(char typed) {
        if (typed >= 'a' && typed <= 'z') {
            return typed - 'a' + 1;
        }
        if (typed >= 'A' && typed <= 'Z') {
            return typed - 'A' + 1;
        }
        switch (typed) {
            case ' ':
            case '@':
                return 0;
            case '[':
                return 27;
            case '\\':
                return 28;
            case ']':
                return 29;
            case '^':
                return 30;
            case '_':
                return 31;
            case '?':
                return 127;
            default:
                return -1;
        }
    }

    protected int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
