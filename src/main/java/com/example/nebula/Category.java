package com.example.nebula;

/** Sidebar categories. ALL shows everything; the others show only their own modules. */
public enum Category {
    ALL("All"),
    CLIENT("Client"),
    RENDER("Render"),
    MISC("Misc");

    public final String label;

    Category(String label) {
        this.label = label;
    }
}
