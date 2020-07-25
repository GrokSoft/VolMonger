package com.groksoft.volmunger.sftp;

import com.groksoft.volmunger.MungerException;
import com.groksoft.volmunger.Utils;
import com.groksoft.volmunger.repository.Libraries;
import com.groksoft.volmunger.repository.Repository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.subsystem.sftp.SftpClient;
import org.apache.sshd.client.subsystem.sftp.impl.DefaultSftpClientFactory;
import org.apache.sshd.server.subsystem.sftp.SftpErrorStatusDataHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;

public class ClientSftp implements SftpErrorStatusDataHandler
{
    private final int BUFFER_SIZE = 1048576;
    private transient byte[] buffer;

    private String hostname;
    private int hostport;
    private transient Logger logger = LogManager.getLogger("applog");
    private Repository myRepo;
    private String password;
    private ClientSession session;
    private SftpClient sftpClient;
    private SshClient sshClient;
    private Repository theirRepo;
    private String user;
// todo    private final int BUFFER_SIZE = 10485760;

    private ClientSftp()
    {
        // hide default constructor
    }

    public ClientSftp(Repository mine, Repository theirs)
    {
        myRepo = mine;
        theirRepo = theirs;

        hostname = Utils.parseHost(theirRepo.getLibraryData().libraries.site);
        hostport = Utils.getPort(theirRepo.getLibraryData().libraries.site) + 1;

        user = myRepo.getLibraryData().libraries.key;
        password = theirRepo.getLibraryData().libraries.key;
    }

    /**
     * Make a remote directory tree
     *
     * @param pathname Path and filename. Note that a filename is required but is removed
     * @return True if any directories were created
     * @throws IOException
     */
    public String makeRemoteDirectory(String pathname) throws Exception
    {
        String remotePath = "";

        // filename might have mixed separators
//        pathname = pathname.replace('\\', '/');
//        if (myRepo.getLibraryData().libraries.flavor.equalsIgnoreCase(Libraries.APPLE))
//            pathname = pathname.replace('/', ':');

        File f = new File(pathname);
        String dir = f.getParentFile().getAbsolutePath(); // get parent of file

        // filename might have mixed separators, normalize to forward-slash for split()
//        dir = dir.replace('\\', '/');
//        if (myRepo.getLibraryData().libraries.flavor.equalsIgnoreCase(Libraries.APPLE))
//            dir = dir.replace(':', '/');
        String[] parts = dir.split(myRepo.getSeparator());

        String sep = theirRepo.getSeparator();
        String whole = "";
        for (int i = 0; i < parts.length; ++i)
        {
            try
            {
                // is it a Windows drive letter: ?
                if (i == 0 && parts[i].endsWith(":"))
                {
                    // don't try to create a Windows root directory, e.g. C:\
                    if (theirRepo.getLibraryData().libraries.flavor.equalsIgnoreCase(Libraries.WINDOWS) &&
                            parts[i].length() == 2)
                    {
                        whole = parts[i];
                        continue;
                    }
                }
                whole = whole + sep + parts[i];

                // protect the root of drives
                if (whole.equals(sep))
                    continue;

                // try to create next directory segment
                sftpClient.mkdir(whole);
            }
            catch (IOException e)
            {
                String msg = e.toString().trim().toLowerCase();
                if (msg.startsWith("sftp error"))
                {
                    if (!msg.contains("alreadyexists")) // ignore "already exists" errors
                        throw e;
                }
            }
        }
        return remotePath;
    }

    public void startClient()
    {
        try
        {
            sshClient = SshClient.setUpDefaultClient();
            sshClient.start();

            session = sshClient.connect(user, hostname, hostport).verify(180000L).getSession();
            session.addPasswordIdentity(password);
            session.auth().verify(180000L).await();

            sftpClient = DefaultSftpClientFactory.INSTANCE.createSftpClient(session);
        }
        catch (Exception e)
        {
            logger.error(e.getMessage());
        }
    }

    public void stopClient()
    {
        try
        {
            if (sftpClient != null)
                session.close();

            if (sshClient != null)
                sshClient.close();
        }
        catch (IOException e)
        {

        }
    }

    public void transmitFile(String src, String dest) throws IOException
    {
        try
        {
            SftpClient.Attributes destAttr = null;
            int readOffset = 0;
            long writeOffset = 0L;

            String copyDest = dest + ".part";

            // does the destination already exist?
            // automatically resume/continue transfer
            try
            {
                destAttr = sftpClient.stat(copyDest);
                if (destAttr != null)
                {
                    if (destAttr.isRegularFile() && destAttr.getSize() > 0)
                    {
                        readOffset = (int) destAttr.getSize();
                        writeOffset = readOffset;
                    }
                }
            }
            catch (IOException e)
            {
                String msg = e.toString().trim().toLowerCase();
                if (msg.startsWith("sftp error"))
                {
                    if (!msg.contains("nosuchfileexception"))
                        throw e;
                }
                destAttr = null;
            }

            if (destAttr == null) // file does not exist, try making directory tree
            {
                makeRemoteDirectory(copyDest);
            }

            // append to existing file, otherwise create
            Collection<SftpClient.OpenMode> mode;
            if (writeOffset > 0L)
            {
                mode = EnumSet.of(
                        SftpClient.OpenMode.Read,
                        SftpClient.OpenMode.Write,
                        SftpClient.OpenMode.Append,
                        SftpClient.OpenMode.Exclusive);
                logger.warn("Resuming partial transfer");
            }
            else
            {
                mode = EnumSet.of(
                        SftpClient.OpenMode.Read,
                        SftpClient.OpenMode.Write,
                        SftpClient.OpenMode.Create,
                        SftpClient.OpenMode.Exclusive);
            }

            // open remote file
            SftpClient.Handle handle = sftpClient.open(copyDest, mode);
            SftpClient.Attributes attr = new SftpClient.Attributes().perms(Utils.getLocalPermissions(src));
            sftpClient.setStat(handle, attr);

            // open local file
            FileInputStream srcStream = new FileInputStream(src);
            srcStream.skip(readOffset);

            // copy with chunks to avoid out of memory problems
            buffer = new byte[BUFFER_SIZE];
            int size = 0;
            while (true)
            {
                size = srcStream.read(buffer, 0, BUFFER_SIZE);
                if (size < 1)
                    break;
                sftpClient.write(handle, writeOffset, buffer, 0, size);
                Arrays.fill(buffer, (byte) 0);
                writeOffset += size;
            }

            srcStream.close();
            sftpClient.close(handle);

            // delete old file
            try
            {
                sftpClient.remove(dest);
            }
            catch (FileNotFoundException fnf)
            {
                // ignore FileNotFoundException
            }
            catch (IOException e)
            {
                String msg = e.toString().trim().toLowerCase();
                if (msg.startsWith("sftp error"))
                {
                    if (!msg.contains("nosuchfileexception"))
                        throw e;
                }
            }

            // rename .part file
            sftpClient.rename(copyDest, dest);
        }
        catch (Exception e)
        {
            logger.error(e.getMessage() + "\r\n" + Utils.getStackTrace(e));
        }
    }

}