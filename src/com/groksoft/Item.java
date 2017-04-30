package com.groksoft;

import java.io.Serializable;

/**
 * The type Item.
 */
public class Item implements Serializable
{
    // JSON output will be in the order defined here
    private String itemPath;
    private String fullPath;
    private String library;
    private boolean directory = false;
    private boolean symLink = false;

    /**
     * Instantiates a new Item.
     */
    public Item() {
        super();
    }

    /**
     * Is directory boolean.
     *
     * @return the boolean
     */
    public boolean isDirectory() {
        return directory;
    }

    /**
     * Sets directory.
     *
     * @param directory the directory
     */
    public void setDirectory(boolean directory) {
        this.directory = directory;
    }

    /**
     * Gets item path.
     * <p>
     * The item path is the right-side of the full path
     * with the library path removed from the left side.
     *
     * @return the item path
     */
    public String getItemPath() {
        return itemPath;
    }

    /**
     * Sets item path.
     *
     * @param itemPath the item path
     */
    public void setItemPath(String itemPath) {
        this.itemPath = itemPath;
    }


    /**
     * Gets full path.
     *
     * @return the full path
     */
    public String getFullPath() {
        return fullPath;
    }

    /**
     * Sets full path.
     *
     * @param fullPath the full path
     */
    public void setFullPath(String fullPath) {
        this.fullPath = fullPath;
    }

    public String getLibrary() {
        return library;
    }

    public void setLibrary(String library) {
        this.library = library;
    }

    /**
     * Is sym link boolean.
     *
     * @return the boolean
     */
    public boolean isSymLink() {
        return symLink;
    }

    /**
     * Sets sym link.
     *
     * @param symLink the sym link
     */
    public void setSymLink(boolean symLink) {
        this.symLink = symLink;
    }

}