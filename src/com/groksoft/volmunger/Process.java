package com.groksoft.volmunger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.regex.Pattern;

// see https://logging.apache.org/log4j/2.x/
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.groksoft.volmunger.stty.Terminal;
import com.groksoft.volmunger.sftp.Server;
import com.groksoft.volmunger.stty.CommManager;
import com.groksoft.volmunger.storage.Target;
import com.groksoft.volmunger.repository.Item;
import com.groksoft.volmunger.repository.Library;
import com.groksoft.volmunger.repository.Repository;
import com.groksoft.volmunger.storage.Storage;

/**
 * VolMunger Process
 */
public class Process
{
    /**
     * The Formatter.
     * Setup formatter for number
     */
    DecimalFormat formatter = new DecimalFormat("#,###");

    private transient Logger logger = LogManager.getLogger("applog");
    private Configuration cfg = null;
    private Terminal terminal = null;
    private Repository publisherRepository = null;
    private Repository subscriberRepository = null;
    private Storage storageTargets = null;
    private long grandTotalItems = 0L;
    private long grandTotalSize = 0L;
    private String currentGroupName = "";
    private String lastGroupName = "";
    private long whatsNewTotal = 0;
    private int ignoreTotal = 0;
    private int errorCount = 0;
    private int copyCount = 0;
    private ArrayList<String> ignoredList = new ArrayList<>();
    private boolean isListening = false;
    CommManager commManager = null;
    Server transfer = null;

    /**
     * Instantiates the class
     */
    public Process()
    {
    }

    /**
     * Process everything
     * <p>
     * This is the where a munge run starts and ends based on configuration
     *
     * @param config Configuration, null allowed
     */
    public int process(Configuration config)
    {
        int returnValue = 0;
        ThreadGroup sessionThreads = null;

        try
        {
            cfg = config;

            // create primary objects
            publisherRepository = new Repository(cfg);
            subscriberRepository = new Repository(cfg);
            storageTargets = new Storage();

            try
            {
                // get -p Publisher libraries
                if (cfg.getPublisherLibrariesFileName().length() > 0)
                {
                    readRepository(cfg.getPublisherLibrariesFileName(), publisherRepository, true);
                }
                else
                {
                    // get -P Publisher collection
                    if (cfg.getPublisherCollectionFilename().length() > 0)
                    {
                        readRepository(cfg.getPublisherCollectionFilename(), publisherRepository, false);
                    }
                }

                // For -r P connect to remote subscriber -r S
                if (cfg.isPublisherProcess())
                {
                    // get subscriber connection information
                    if (cfg.getSubscriberLibrariesFileName().length() > 0)
                    {
                        readRepository(cfg.getSubscriberLibrariesFileName(), subscriberRepository, false);
                    }
                    else if (cfg.getSubscriberCollectionFilename().length() > 0)
                    {
                        readRepository(cfg.getSubscriberCollectionFilename(), subscriberRepository, false);
                    }
                    else
                        throw new MungerException("-r P requires a subscriber -s or -S");

                    // connect to subscriber
                    terminal = new Terminal(cfg, false);
                    if (!terminal.connect(publisherRepository, subscriberRepository))
                    {
                        throw new MungerException("Publisher Process failed to connect");
                    }

                    // check for opening commands from Subscriber
                    // *** might change cfg options for subscriber and targets that are handled below ***
                    if (terminal.checkBannerCommands())
                    {
                        logger.info("Received subscriber requests:" + (cfg.isRequestCollection() ? " RequestCollection " : "") + (cfg.isRequestTargets() ? "RequestTargets" : ""));
                    }
                }

                // get -s Subscriber libraries
                if (cfg.getSubscriberLibrariesFileName().length() > 0)
                {
                    if (cfg.isRemoteSession() && cfg.isRequestCollection())
                    {
                        // request complete collection data from remote subscriber
                        String location = terminal.retrieveRemoteData(cfg.getSubscriberLibrariesFileName(), "collection");
                        cfg.setSubscriberLibrariesFileName(""); // clear so the collection file will be used
                        cfg.setSubscriberCollectionFilename(location);

                        readRepository(cfg.getSubscriberCollectionFilename(), subscriberRepository, false);
                    }
                    else
                    {
                        readRepository(cfg.getSubscriberLibrariesFileName(), subscriberRepository, true);
                    }
                }

                // get -S Subscriber collection
                if (cfg.getSubscriberCollectionFilename().length() > 0 && !cfg.isRequestCollection())
                {
                    readRepository(cfg.getSubscriberCollectionFilename(), subscriberRepository, true);
                }

                // process -e export text, publisher only
                if (cfg.getExportTextFilename().length() > 0)
                {
                    if (cfg.getPublisherLibrariesFileName().length() > 0 || cfg.getPublisherCollectionFilename().length() > 0)
                    {
                        exportText();
                    }
                    else
                    {
                        throw new MungerException("-e option requires the -p and/or -P options");
                    }
                }

                // process -i export collection items, publisher only
                if (cfg.getExportCollectionFilename().length() > 0)
                {
                    if (cfg.getPublisherLibrariesFileName().length() > 0 || cfg.getPublisherCollectionFilename().length() > 0)
                    {
                        exportCollection();
                    }
                    else
                    {
                        throw new MungerException("-i option requires the -p and/or -P options");
                    }
                }

                // get -t|T Targets
                if (cfg.getTargetsFilename().length() > 0)
                {
                    String location = cfg.getTargetsFilename();

                    if (cfg.isRemoteSession() && cfg.isRequestTargets())
                    {
                        // request target data from remote subscriber
                        location = terminal.retrieveRemoteData(location, "targets");
                        cfg.setTargetsFilename(location);
                    }
                    readTargets(location, storageTargets);
                }
                else
                {
                    if (!cfg.isDryRun())
                    {
                        logger.warn("NOTE: No targets file was specified - performing a dry run");
                        cfg.setDryRun(true);
                    }
                }

                // if all the pieces are specified munge the collections
                if ((cfg.getPublisherLibrariesFileName().length() > 0 ||
                        cfg.getPublisherCollectionFilename().length() > 0) &&
                        (cfg.getSubscriberLibrariesFileName().length() > 0 ||
                                cfg.getSubscriberCollectionFilename().length() > 0) &&
                        cfg.getTargetsFilename().length() > 0)
                {
                    munge(); // this is the full munge process
                }
                else
                {
                    logger.info("Munge not performed");
                }
            }
            catch (Exception ex)
            {
                logger.error(ex.getMessage() + " toString=" + ex.toString());
                returnValue = 2;
            }
        }
        catch (Exception e)
        {
            logger.error(e.getMessage());
            returnValue = 1;
            cfg = null;
        }
        finally
        {
            if (terminal != null)
            {
                logger.info("closing terminal connections");
                terminal.disconnect();
            }

            // the - makes searching for the ending of a run easier
            if (logger != null)
            {
                logger.info("- Process end" + " ------------------------------------------");
                LogManager.shutdown();
            }
        }

        return returnValue;
    } // process

    /**
     * Copy file.
     *
     * @param from the from
     * @param to   the to
     * @return the boolean
     */
    public boolean copyFile(String from, String to)
    {
        try
        {
            File f = new File(to);
            if (f != null)
            {
                f.getParentFile().mkdirs();
            }
            Path fromPath = Paths.get(from).toRealPath();
            Path toPath = Paths.get(to);  //.toRealPath();
            Files.copy(fromPath, toPath, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        }
        catch (UnsupportedOperationException e)
        {
            logger.error("Copy problem UnsupportedOperationException: " + e.getMessage());
            return false;
        }
        catch (FileAlreadyExistsException e)
        {
            logger.error("Copy problem FileAlreadyExistsException: " + e.getMessage());
            return false;
        }
        catch (DirectoryNotEmptyException e)
        {
            logger.error("Copy problem DirectoryNotEmptyException: " + e.getMessage());
            return false;
        }
        catch (IOException e)
        {
            logger.error("Copy problem IOException: " + e.getMessage());
            return false;
        }
        return true;
    }

    private void exportCollection()
    {
        try
        {
            for (Library pubLib : publisherRepository.getLibraryData().libraries.bibliography)
            {
                publisherRepository.scan(pubLib.name);
            }
            publisherRepository.exportCollection();
        }
        catch (MungerException e)
        {
            // no logger yet to just print to the screen
            System.out.println(e.getMessage());
        }
    }

    private void exportText()
    {
        try
        {
            for (Library pubLib : publisherRepository.getLibraryData().libraries.bibliography)
            {
                publisherRepository.scan(pubLib.name);
            }
            publisherRepository.exportText();
        }
        catch (MungerException e)
        {
            // no logger yet to just print to the screen
            System.out.println(e.getMessage());
        }
    }

    /**
     * Get item size long.
     *
     * @param item the item root node
     * @return the long size of the item(s) in bytes
     */
    public long getItemSize(Item item)
    {
        long size = 0;
        try
        {
            size = Files.size(Paths.get(item.getFullPath()));
        }
        catch (IOException e)
        {
            logger.error("Exception '" + e.getMessage() + "' getting size of item " + item.getFullPath());
        }
        return size;
    }

    /**
     * Gets a subscriber target.
     * <p>
     * Will return one of the subscriber targets for the library of the item that is
     * large enough to hold the size specified, otherwise an empty string is returned.
     *
     * @param item    the item
     * @param library the publisher library.definition.name
     * @param size    the total size of item(s) to be copied
     * @return the target
     * @throws MungerException the volmunger exception
     */
    public String getTarget(Item item, String library, long size) throws MungerException
    {
        String target = null;
        boolean allFull = true;
        boolean notFound = true;
        long space = 0L;
        long minimum = 0L;

        Target tar = storageTargets.getLibraryTarget(library);
        if (tar != null)
        {
            minimum = Utils.getScaledValue(tar.minimum);
        }
        else
        {
            minimum = Storage.minimumBytes;
        }

        // see if there is an "original" directory the new content will fit in
        String path = subscriberRepository.hasDirectory(library, item.getItemPath());
        if (path != null)
        {
            if (cfg.isRemoteSession())
            {
                // remote subscriber
                space = terminal.availableSpace(path);
            }
            else
            {
                space = Utils.availableSpace(path);
            }
            logger.info("Checking space on " + (cfg.isRemoteSession() ? "remote" : "local") + " path " + path + " == " + (space / (1024 * 1024)) + " for " +
                    (size / (1024 * 1024)) + " minimum " + (minimum / (1024 * 1024)) + " MB");
            if (space > (size + minimum))
            {
                logger.info("Using original storage location for " + item.getItemPath() + " at " + path);
                //
                // inline return
                //
                return path;
            }
            else
            {
                logger.info("Original storage location too full for " + item.getItemPath() + " (" + size + ") at " + path);
            }
        }

        // find a matching target
        if (tar != null)
        {
            notFound = false;
            for (int j = 0; j < tar.locations.length; ++j)
            {
                // check space on the candidate target
                String candidate = tar.locations[j];
                if (cfg.isRemoteSession())
                { // remote subscriber
                    space = terminal.availableSpace(candidate);
                }
                else
                {
                    space = Utils.availableSpace(candidate);
                }
                if (space > minimum)
                {
                    // check target space minimum
                    allFull = false;
                    if (space > (size + minimum))
                    {
                        // check size of item(s) to be copied
                        target = candidate;             // has space, use it
                        break;
                    }
                }
            }
            if (allFull)
            {
                logger.error("All locations for library " + library + " are below specified minimum of " + tar.minimum);

                // todo Should this be a throw ??
                System.exit(2);     // EXIT the program


            }
        }
        if (notFound)
        {
            logger.error("No target library match found for publisher library " + library);
        }
        return target;
    }

    /**
     * Determine if item should be ignored.
     *
     * @param item
     * @return true if it should be ignored
     */
    private boolean ignore(Item item)
    {
        String str = "";
        String str1 = "";
        boolean ret = false;

        for (Pattern patt : publisherRepository.getLibraryData().libraries.compiledPatterns)
        {
            str = patt.toString();
            str1 = str.replace("?", ".?").replace("*", ".*?");
            if (item.getName().matches(str1))
            {
                //logger.info(">>>>>>Ignoring '" + item.getName());
                ignoreTotal++;
                ret = true;
                break;
            }
        }
        return ret;
    }

    /**
     * Is new grouping boolean.
     *
     * @param publisherItem the publisher item
     * @return the boolean
     */
    private boolean isNewGrouping(Item publisherItem)
    {
        boolean ret = true;
        int i = publisherItem.getItemPath().lastIndexOf(File.separator);
        if (i < 0)
        {
            logger.warn("File pathsep: '" + File.separator + "'");
            logger.warn("File     sep: '" + File.separator + "'");
            logger.warn("No subdirectory in path : " + publisherItem.getItemPath());
            return true;
        }
        String path = publisherItem.getItemPath().substring(0, i);
        if (path.length() < 1)
        {
            path = publisherItem.getItemPath().substring(0, publisherItem.getItemPath().lastIndexOf(File.separator));
        }
        if (currentGroupName.equalsIgnoreCase(path))
        {
            ret = false;
        }
        else
        {
            currentGroupName = path;
        }
        return ret;
    }

    /**
     * Munge two collections
     *
     * @throws MungerException the volmunger exception
     */
    private void munge() throws MungerException
    {
        boolean iWin = false;
        Item lastDirectoryItem = null;
        PrintWriter mismatchFile = null;
        PrintWriter whatsNewFile = null;
        PrintWriter targetFile = null;
        String currentWhatsNew = "";
        String currLib = "";
        ArrayList<Item> group = new ArrayList<>();
        long totalSize = 0;

        String header = "Munging " + publisherRepository.getLibraryData().libraries.description + " to " +
                subscriberRepository.getLibraryData().libraries.description;

        // setup the -m mismatch output file
        if (cfg.getMismatchFilename().length() > 0)
        {
            try
            {
                mismatchFile = new PrintWriter(cfg.getMismatchFilename());
                mismatchFile.println(header);
                logger.info("Writing to Mismatches file " + cfg.getMismatchFilename());
            }
            catch (FileNotFoundException fnf)
            {
                String s = "File not found exception for Mismatches output file " + cfg.getMismatchFilename();
                logger.error(s);
                throw new MungerException(s);
            }
        }

        // setup the -n What's New output file
        if (cfg.getWhatsNewFilename().length() > 0)
        {
            try
            {
                whatsNewFile = new PrintWriter(cfg.getWhatsNewFilename());
                whatsNewFile.println("What's New");
                logger.info("Writing to What's New file " + cfg.getWhatsNewFilename());
            }
            catch (FileNotFoundException fnf)
            {
                String s = "File not found exception for What's New output file " + cfg.getWhatsNewFilename();
                logger.error(s);
                throw new MungerException(s);
            }
        }

        logger.info(header);

        try
        {
            for (Library subLib : subscriberRepository.getLibraryData().libraries.bibliography)
            {
                boolean scanned = false;
                Library pubLib = null;

                // TODO Add filtering which library to process by -l option, isSpecificPublisherLibrary() & getPublisherLibraryNames()

                if ((pubLib = publisherRepository.getLibrary(subLib.name)) != null)
                {

                    // Do the libraries have items or do we need to be scanned?
                    if (pubLib.items == null || pubLib.items.size() < 1)
                    {
                        publisherRepository.scan(pubLib.name);
                        scanned = true;
                    }
                    if (subLib.items == null || subLib.items.size() < 1)
                    {
                        subscriberRepository.scan(subLib.name);
                    }

                    logger.info("Munge " + subLib.name + ": " + pubLib.items.size() + " publisher items with " +
                            subLib.items.size() + " subscriber items");

                    for (Item item : pubLib.items)
                    {
                        if (ignore(item))
                        {
                            logger.info("  ! Ignoring '" + item.getItemPath() + "'");
                            ignoredList.add(item.getFullPath());
                        }
                        else
                        {
                            boolean has = subscriberRepository.hasItem(subLib.name, item.getItemPath());
                            if (has)
                            {
                                logger.info("  = Subscriber " + subLib.name + " has " + item.getItemPath());
                            }
                            else
                            {

                                if (cfg.getWhatsNewFilename().length() > 0)
                                {
                                    /*
                                     * Only show the left side of mismatches file. And Only show it once.
                                     * So if you have 10 new episodes of Lucifer only the following will show in the what's new file
                                     * Big Bang Theory
                                     * Lucifer
                                     * Legion
                                     */
                                    if (!item.getLibrary().equals(currLib))
                                    {
                                        // If not first time display and reset the whatsNewTotal
                                        if (!currLib.equals(""))
                                        {
                                            whatsNewFile.println("--------------------------------");
                                            whatsNewFile.println("Total for " + currLib + " = " + whatsNewTotal);
                                            whatsNewFile.println("================================");
                                            whatsNewTotal = 0;
                                        }
                                        currLib = item.getLibrary();
                                        whatsNewFile.println("");
                                        whatsNewFile.println(currLib);
                                        whatsNewFile.println(new String(new char[currLib.length()]).replace('\0', '='));
                                    }
                                    String path;
                                    path = Utils.getLastPath(item.getItemPath());
                                    if (!currentWhatsNew.equalsIgnoreCase(path))
                                    {
                                        assert whatsNewFile != null;
                                        whatsNewFile.println("    " + path);
                                        currentWhatsNew = path;
                                        whatsNewTotal++;
                                    }
                                }

                                logger.info("  + Subscriber " + subLib.name + " missing " + item.getItemPath());

                                if (!item.isDirectory())
                                {
                                    if (cfg.getMismatchFilename().length() > 0)
                                    {
                                        assert mismatchFile != null;
                                        mismatchFile.println(item.getFullPath());
                                    }

                                    /* If the group is switching, process the current one. */
                                    if (isNewGrouping(item))
                                    {
                                        logger.info("Switching groups from '" + lastGroupName + "' to '" + currentGroupName + "'");
                                        // There is a new group - process the old group
                                        copyGroup(group, totalSize);
                                        totalSize = 0L;

                                        // Flush the output files
                                        if (cfg.getWhatsNewFilename().length() > 0)
                                        {
                                            whatsNewFile.flush();
                                        }
                                        if (cfg.getMismatchFilename().length() > 0)
                                        {
                                            mismatchFile.flush();
                                        }
                                    }
                                    long size = 0L;
                                    if (scanned)
                                    {
                                        size = getItemSize(item);
                                        item.setSize(size);
                                        totalSize += size;
                                    }
                                    group.add(item);
                                }
                            }
                        }
                    }
                }
                else
                {
                    throw new MungerException("Subscribed Publisher library " + subLib.name + " not found");
                }
            }
        }
        catch (Exception e)
        {
            logger.error("Exception " + e.getMessage() + " trace: " + Utils.getStackTrace(e));
        }
        finally
        {
            if (group.size() > 0)
            {
                // Process the last group
                logger.info("Processing last group '" + currentGroupName + "'");
                // There is another group - process it
                copyGroup(group, totalSize);
                totalSize = 0L;
            }

            // Close all the files and show the results
            if (mismatchFile != null)
            {
                mismatchFile.println("----------------------------------------------------");
                mismatchFile.println("Grand total items: " + grandTotalItems);
                double gb = grandTotalSize / (1024 * 1024 * 1024);
                mismatchFile.println("Grand total size : " + formatter.format(grandTotalSize) + " bytes, " + gb + " GB");
                mismatchFile.close();
            }
            if (whatsNewFile != null)
            {
                whatsNewFile.println("--------------------------------");
                whatsNewFile.println("Total for " + currLib + " = " + whatsNewTotal);
                whatsNewFile.println("================================");
                whatsNewFile.close();
            }
        }

        logger.info("-----------------------------------------------------");
        if (ignoredList.size() > 0)
        {
            logger.info("Ignored " + ignoredList.size() + " files: ");
            for (String s : ignoredList)
            {
                logger.info("    " + s);
            }
        }
        logger.info("Grand total copies: " + copyCount);
        logger.info("Grand total errors: " + errorCount);
        logger.info("Grand total ignored: " + ignoreTotal);
        logger.info("Grand total items: " + grandTotalItems);
        double gb = grandTotalSize / (1024 * 1024 * 1024);
        logger.info("Grand total size : " + formatter.format(grandTotalSize) + " bytes, " + gb + " GB");
    }

    /**
     * Copy group of files.
     *
     * @param group     the group
     * @param totalSize the total size
     * @throws MungerException the volmunger exception
     */
    private void copyGroup(ArrayList<Item> group, long totalSize) throws MungerException
    {
        try
        {
            if (group.size() > 0)
            {
                for (Item groupItem : group)
                {
                    if (cfg.isDryRun())
                    {
                        // -D Dry run option
                        ++copyCount;
                        logger.info("    Would copy #" + copyCount + " " + groupItem.getFullPath());
                    }
                    else
                    {
                        String targetPath = getTarget(groupItem, groupItem.getLibrary(), totalSize);
                        if (targetPath != null)
                        {
                            // copy item(s) to targetPath
                            ++copyCount;
                            String to = targetPath + File.separator + groupItem.getItemPath();
                            logger.info("  > Copying #" + copyCount + " " + groupItem.getFullPath() + " to " + to);
                            if (!copyFile(groupItem.getFullPath(), to))
                            {
                                ++errorCount;
                            }
                        }
                        else
                        {
                            logger.error("    No space on any targetPath " + group.get(0).getLibrary() + " for " +
                                    lastGroupName + " that is " + totalSize / (1024 * 1024) + " MB");
                        }
                    }
                }
            }
            grandTotalItems = grandTotalItems + group.size();
            group.clear();
            grandTotalSize = grandTotalSize + totalSize;
            totalSize = 0L;
            lastGroupName = currentGroupName;
        }
        catch (Exception e)
        {
            throw new MungerException(e.getMessage() + " trace: " + Utils.getStackTrace(e));
        }
    }

    /**
     * Read repository.
     *
     * @param filename the filename
     * @param repo     the repo
     * @throws MungerException the volmunger exception
     */
    private void readRepository(String filename, Repository repo, boolean validate) throws MungerException
    {
        repo.read(filename);
        if (validate)
        {
            repo.validate();
        }
    } // readRepository

    /**
     * Read targets.
     *
     * @param filename the filename
     * @param storage  the storage
     * @throws MungerException the volmunger exception
     */
    public void readTargets(String filename, Storage storage) throws MungerException
    {
        storage.read(filename);
        storage.validate();
    }

} // Process