package com.groksoft.volmonger.vio.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.*;
import java.util.*;

import com.groksoft.volmonger.Configuration;

//----------------------------------------------------------------------------
/**
 * Manage all connections and enforce limits.
 * 
 * The ConnectionManager class is a subclass of Thread. It keeps a list of all
 * connections, and enforces the maximum connection limit.
 * 
 * Each connection uses a separate thread.
 * 
 * There is one ConnectionManager for the entire server.
 * 
 * The ConnectionManager uses another thread to remove dead connections from the
 * _allConnections list.
 */
public class ConnectionManager extends Thread
{
	private transient Logger logger = LogManager.getLogger("applog");


	/** The list of all service connections */
	private Vector _allConnections;
	/** The single instance of this class */
	private static ConnectionManager instance = null;
	/** The maximum connections allowed for this entire server instance */
	protected int _maxConnections;
	/** Count of total connections since started */
	private int _totalConnections = 0;
	/** Flag used to determine when to stop listening */
	private boolean _stop = false;

	//------------------------------------------------------------------------
	/**
	 * Constructor.
	 * 
	 * Instantiates the ConnectionManager object and set it as a daemon so the Java
	 * Virtual Machine does not wait for it to exit.
	 */
	public ConnectionManager(ThreadGroup aGroup, int aMaxConnections)
	{
		// instantiate this object in the specified thread group to
		// enforce the specified maximum connections limitation.
		super(aGroup, "ConnectionManager");

		this.instance = this;

		// make it a daemon so the JVM does not wait for it to exit
		this.setDaemon(true);
		this.setMaxConnections(aMaxConnections);
		_allConnections = new Vector(aMaxConnections);

		// log it
		logger.info("Starting ConnectionManager " + aMaxConnections + " max");
	} // constructor

	//------------------------------------------------------------------------
	/**
	 * Add a connection for a service.
	 * 
	 * Responds to a connection request. The maximum connection limit is
	 * checked. If the limit has not been exceeded the new connection is added
	 * to _allConnections, and a thread is started to service the request.
	 * 
	 */
	public synchronized void addConnection (Socket aSocket)
	{
		// check for maximum connections
		if (_allConnections.size() >= _maxConnections)
		{
			// maximum connections exceeded - try to tell user
			// !?! CHANGE TO API RESPONSE, NOT TEXT
			try
			{
				PrintWriter clientOut = new PrintWriter(aSocket.getOutputStream());
				clientOut.println("Connection request denied; maximum users exceeded");
				clientOut.flush();

				// log it
				logger.info("Maximum connections (" + _maxConnections + ") exceeded");
				logger.info("Connection refused from " + aSocket.getInetAddress().getHostAddress() + ":" + aSocket.getPort());

				// close the connection
				aSocket.close();
			}
			catch (IOException e)
			{
				logger.info(e);
			}
		}
		else
		// if limit has not been reached
		{
			// create a connection thread for this request
			Connection theConnection = new Connection(aSocket, new Session());
			_allConnections.addElement(theConnection);

			// log it
			logger.info("Session opened " + aSocket.getInetAddress().getHostAddress() + ":" + aSocket.getPort() + " port " + aSocket.getLocalPort());

			// start the connection thread
			theConnection.start();
			++_totalConnections;
		}
	}

	//------------------------------------------------------------------------
	/**
	 * End a client connection.
	 * 
	 * Notifies the ConnectionManager that this connection has been closed. Called
	 * from the run() method of the Connection thread created by addConnection()
	 * when the connection is closed for any reason.
	 * 
	 * @see Connection
	 */
	public synchronized void endConnection ()
	{
		// notify the ConnectionManager thread that this connection has closed
		this.notify();
	}

	//------------------------------------------------------------------------
	/**
	 * Get this instance.
	 */
	public static ConnectionManager getInstance ()
	{
		return instance;
	}

	//------------------------------------------------------------------------
	/**
	 * Get the connections Vector
	 */
	public Vector getAllConnections ()
	{
		return this._allConnections;
	}

	//------------------------------------------------------------------------
	/**
	 * Set or change the maximum number of connections allowed for this server.
	 * 
	 */
	public synchronized void setMaxConnections (int aMax)
	{
		_maxConnections = aMax;
	}

	//------------------------------------------------------------------------
	/**
	 * Dump statistics of connections.
	 * 
	 * @param aWriter The PrintWriter to be used to print the list.
	 */
	public synchronized void dumpStatistics (PrintWriter aWriter)
	{
		// dump server information
		aWriter.println("Version " + Configuration.getVOLMONGER_VERSION());

		// dump connection list
		aWriter.println("Active connections: " + _allConnections.size());
		for (int index = 0; index < _allConnections.size(); ++index)
		{
			Connection c = (Connection)_allConnections.elementAt(index);
			aWriter.println("  " + c._service.getName() + " to " + c._socket.getInetAddress().getHostAddress() + ":" + c._socket.getPort() + " port " + c._socket.getLocalPort());
		}

		// dump connection counts
		aWriter.println("  Total connections since started: " + _totalConnections);
		aWriter.println("  Maximum allowed connections: " + _maxConnections);
	}

	//------------------------------------------------------------------------
	/**
	 * Politely request the listener to stop.
	 * 
	 */
	public void requestStop ()
	{
		this._stop = true;
		for (int index = 0; index < _allConnections.size(); ++index)
		{
			// stop live connections
			Connection c = (Connection)_allConnections.elementAt(index);
			if (c.isAlive())
			{
				c.getConsole().requestStop();
			}
		}
		this.interrupt();
	}

	//------------------------------------------------------------------------
	/**
	 * Thread used to clean-up dead connections.
	 * 
	 * Waits to be notified of a closed connection via a call to the
	 * endConnection() method, then scans all current connections for any that
	 * are dead. Each dead connection is removed from the _allConnections list.
	 */
	public void run ()
	{
		while (_stop == false)
		{
			for (int index = 0; index < _allConnections.size(); ++index)
			{
				// remove dead connections
				Connection c = (Connection)_allConnections.elementAt(index);
				if (!c.isAlive())
				{
					_allConnections.removeElementAt(index);
					logger.info(c._service.getName() + " closed " + c._socket.getInetAddress().getHostAddress() + ":" + c._socket.getPort() + " port " + c._socket.getLocalPort());
				}
			}

			// wait for notify of closed connection
			try
			{
				synchronized (this)
				{
					this.wait();
				}
			}
			catch (InterruptedException e)
			{
				logger.info("ConnectionManager interrupted, stop=" + ((_stop)?"true":"false"));
			}
		} // while (true)
		logger.info("Stopped ConnectionManager");
	}
} // ConnectionManager