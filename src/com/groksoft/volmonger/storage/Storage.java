package com.groksoft.volmonger.storage;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.gson.Gson;                    // see https://github.com/google/gson

// see https://logging.apache.org/log4j/2.x/
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.groksoft.volmonger.Configuration;
import com.groksoft.volmonger.MongerException;
import com.groksoft.volmonger.Utils;

/**
 * The type Storage.
 */
public class Storage
{
    private transient Logger logger = LogManager.getLogger("applog");
    private transient Configuration cfg = null;

    // TargetData members
    private TargetData targetData = null;
    private String jsonFilename = "";

    /**
     * Instantiates a new Storage instance.
     */
    public Storage() {
        cfg = Configuration.getInstance();
    }

    /**
     * Read Targets.
     *
     * @param filename The JSON Libraries filename
     * @throws MongerException the monger exception
     */
    public void read(String filename) throws MongerException {
        try {
            String json;
            Gson gson = new Gson();
            logger.info("Reading Targets file " + filename);
            setJsonFilename(filename);
            json = new String(Files.readAllBytes(Paths.get(filename)));
            json = json.replaceAll("[\n\r]", "");
            targetData = gson.fromJson(json, TargetData.class);



            String p = targetData.targets.storage[0].locations[0];



        } catch (IOException ioe) {
            throw new MongerException("Exception while reading targets: " + filename + " trace: " + Utils.getStackTrace(ioe));
        }
    }

    /**
     * Validate the Targets data.
     *
     * @throws MongerException the monger exception
     */
    public void validate() throws MongerException {
        long minimumSize;

        if (getTargetData() == null) {
            throw new MongerException("TargetData are null");
        }

        Targets targets = targetData.targets;

        if (targets.description == null || targets.description.length() == 0) {
            throw new MongerException("targets.description must be defined");
        }

        for (int i = 0; i < targets.storage.length; ++i) {
            Target t = targets.storage[i];
            if (t.name == null || t.name.length() == 0) {
                throw new MongerException("storage.name " + i + " must be defined");
            }
            if (t.minimum == null || t.minimum.length() == 0) {
                throw new MongerException("storage.minimum " + i + " must be defined");
            }
            if (t.locations == null || t.locations.length == 0) {
                throw new MongerException("storage.locations " + i + " must be defined");
            }
            for (int j = 0; j < t.locations.length; ++j) {

            }
        }
    }

    /**
     * Gets Storage filename.
     *
     * @return the TargetData filename
     */
    public String getJsonFilename() {
        return jsonFilename;
    }

    /**
     * Sets Storage file.
     *
     * @param jsonFilename the TargetData file
     */
    public void setJsonFilename(String jsonFilename) {
        this.jsonFilename = jsonFilename;
    }

    /**
     * Gets targetData.
     *
     * @return the target data
     */
    public TargetData getTargetData() {
        return targetData;
    }

}
