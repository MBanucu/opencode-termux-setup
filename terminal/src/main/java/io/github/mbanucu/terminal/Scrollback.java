package io.github.mbanucu.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * The primary screen's scrollback: rows that have left the top of the grid,
 * oldest first, up to a fixed depth.
 *
 * <p>Kept apart from the grid so the parser only has to say "this row left",
 * and so the storage rule - drop the oldest when full, resize every row with
 * the view - lives in one named place.
 */
final class Scrollback {

    private final List<HistoryRow> rows = new ArrayList<>();
    private final int limit;

    Scrollback(int limit) {
        this.limit = Math.max(0, limit);
    }

    void add(HistoryRow row) {
        if (limit == 0) {
            return;
        }
        rows.add(row);
        while (rows.size() > limit) {
            rows.remove(0);
        }
    }

    void clear() {
        rows.clear();
    }

    void resize(int cols) {
        for (int i = 0; i < rows.size(); i++) {
            rows.set(i, rows.get(i).resize(cols));
        }
    }

    int size() {
        return rows.size();
    }

    /** One row, oldest first, without its trailing blanks. */
    String line(int row) {
        if (row < 0 || row >= rows.size()) {
            return "";
        }
        return rows.get(row).text();
    }

    int foreground(int row, int col) {
        if (row < 0 || row >= rows.size()) {
            return 0;
        }
        return rows.get(row).foreground(col);
    }

    int background(int row, int col) {
        if (row < 0 || row >= rows.size()) {
            return 0;
        }
        return rows.get(row).background(col);
    }

    boolean wrapped(int row) {
        return row >= 0 && row < rows.size() && rows.get(row).wrapped();
    }
}
