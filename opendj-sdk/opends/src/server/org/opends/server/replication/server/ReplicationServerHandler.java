/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2006-2010 Sun Microsystems, Inc.
 *      Portions copyright 2011-2013 ForgeRock AS
 */
package org.opends.server.replication.server;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.opends.messages.Message;
import org.opends.server.replication.common.DSInfo;
import org.opends.server.replication.common.RSInfo;
import org.opends.server.replication.common.ServerState;
import org.opends.server.replication.common.ServerStatus;
import org.opends.server.replication.protocol.*;
import org.opends.server.types.*;

import static org.opends.messages.ReplicationMessages.*;
import static org.opends.server.loggers.ErrorLogger.*;
import static org.opends.server.loggers.debug.DebugLogger.*;
import static org.opends.server.replication.protocol.ProtocolVersion.*;

/**
 * This class defines a server handler, which handles all interaction with a
 * peer replication server.
 */
public class ReplicationServerHandler extends ServerHandler
{

  /**
   * Properties filled only if remote server is a RS.
   */
  private String serverAddressURL;
  /**
   * this collection will contain as many elements as there are
   * LDAP servers connected to the remote replication server.
   */
  private final Map<Integer, LightweightServerHandler> remoteDirectoryServers =
    new ConcurrentHashMap<Integer, LightweightServerHandler>();

  /**
   * Starts this handler based on a start message received from remote server.
   * @param inReplServerStartMsg The start msg provided by the remote server.
   * @return Whether the remote server requires encryption or not.
   * @throws DirectoryException When a problem occurs.
   */
  private boolean processStartFromRemote(
        ReplServerStartMsg inReplServerStartMsg)
        throws DirectoryException
  {
    try
    {
      short protocolVersion = getCompatibleVersion(inReplServerStartMsg
          .getVersion());
      session.setProtocolVersion(protocolVersion);
      generationId = inReplServerStartMsg.getGenerationId();
      serverId = inReplServerStartMsg.getServerId();
      serverURL = inReplServerStartMsg.getServerURL();
      final String port = serverURL.substring(serverURL.lastIndexOf(':') + 1);
      serverAddressURL = session.getRemoteAddress() + ":" + port;
      setBaseDNAndDomain(inReplServerStartMsg.getBaseDn(), false);
      setInitialServerState(inReplServerStartMsg.getServerState());
      setSendWindowSize(inReplServerStartMsg.getWindowSize());
      if (protocolVersion > ProtocolVersion.REPLICATION_PROTOCOL_V1)
      {
        // We support connection from a V1 RS
        // Only V2 protocol has the group id in repl server start message
        this.groupId = inReplServerStartMsg.getGroupId();
      }

      oldGenerationId = -100;
    }
    catch(Exception e)
    {
      Message message = Message.raw(e.getLocalizedMessage());
      throw new DirectoryException(ResultCode.OTHER, message);
    }
    return inReplServerStartMsg.getSSLEncryption();
  }

  /**
   * Sends a start message to the remote RS.
   *
   * @return The ReplServerStartMsg sent.
   * @throws IOException
   *           When an exception occurs.
   */
  private ReplServerStartMsg sendStartToRemote() throws IOException
  {
    ReplServerStartMsg outReplServerStartMsg = createReplServerStartMsg();
    send(outReplServerStartMsg);
    return outReplServerStartMsg;
  }

  /**
   * Creates a new handler object to a remote replication server.
   * @param session The session with the remote RS.
   * @param queueSize The queue size to manage updates to that RS.
   * @param replicationServer The hosting local RS object.
   * @param rcvWindowSize The receiving window size.
   */
  public ReplicationServerHandler(
      Session session,
      int queueSize,
      ReplicationServer replicationServer,
      int rcvWindowSize)
  {
    super(session, queueSize, replicationServer, rcvWindowSize);
  }

  /**
   * Connect the hosting RS to the RS represented by THIS handler
   * on an outgoing connection.
   * @param baseDN The baseDN
   * @param sslEncryption The sslEncryption requested to the remote RS.
   * @throws DirectoryException when an error occurs.
   */
  public void connect(String baseDN, boolean sslEncryption)
  throws DirectoryException
  {
    // we are the initiator and decides of the encryption
    this.sslEncryption = sslEncryption;

    setBaseDNAndDomain(baseDN, false);

    localGenerationId = replicationServerDomain.getGenerationId();
    oldGenerationId = localGenerationId;

    try
    {
      lockDomain(false); // no timeout

      ReplServerStartMsg outReplServerStartMsg = sendStartToRemote();

      // Wait answer
      ReplicationMsg msg = session.receive();

      // Reject bad responses
      if (!(msg instanceof ReplServerStartMsg))
      {
        if (msg instanceof StopMsg)
        {
          // Remote replication server is probably shutting down or simultaneous
          // cross-connect detected.
          abortStart(null);
        }
        else
        {
          Message message = ERR_REPLICATION_PROTOCOL_MESSAGE_TYPE.get(msg
              .getClass().getCanonicalName(), "ReplServerStartMsg");
          abortStart(message);
        }
        return;
      }

      processStartFromRemote((ReplServerStartMsg) msg);

      if (replicationServerDomain.isAlreadyConnectedToRS(this))
      {
        // Simultaneous cross connect.
        abortStart(null);
        return;
      }

      /*
      Since we are going to send the topology message before having received
      one, we need to set the generation ID as soon as possible if it is
      currently uninitialized. See OpenDJ-121.
      */
      if (localGenerationId < 0 && generationId > 0)
      {
        oldGenerationId = replicationServerDomain.changeGenerationId(
            generationId, false);
      }

      logStartHandshakeSNDandRCV(outReplServerStartMsg,(ReplServerStartMsg)msg);

      // Until here session is encrypted then it depends on the negotiation
      // The session initiator decides whether to use SSL.
      if (!this.sslEncryption)
        session.stopEncryption();

      if (getProtocolVersion() > ProtocolVersion.REPLICATION_PROTOCOL_V1)
      {
        /*
        Only protocol version above V1 has a phase 2 handshake
        NOW PROCEDE WITH SECOND PHASE OF HANDSHAKE:
        TopologyMsg then TopologyMsg (with a RS)

        Send our own TopologyMsg to remote RS
        */
        TopologyMsg outTopoMsg =
            replicationServerDomain.createTopologyMsgForRS();
        sendTopoInfo(outTopoMsg);

        // wait and process Topo from remote RS
        TopologyMsg inTopoMsg = waitAndProcessTopoFromRemoteRS();
        if (inTopoMsg == null)
        {
          // Simultaneous cross connect.
          abortStart(null);
          return;
        }

        logTopoHandshakeSNDandRCV(outTopoMsg, inTopoMsg);

        replicationServerDomain.startMonitoringPublisher();

        /*
        FIXME: i think this should be done for all protocol version !!
        not only those > V1
        */
        registerIntoDomain();

        /*
        Process TopologyMsg sent by remote RS: store matching new info
        (this will also warn our connected DSs of the new received info)
        */
        replicationServerDomain.receiveTopoInfoFromRS(inTopoMsg, this, false);
      }

      Message message = INFO_REPLICATION_SERVER_CONNECTION_TO_RS
          .get(getReplicationServerId(), getServerId(),
              replicationServerDomain.getBaseDn(),
              session.getReadableRemoteAddress());
      logError(message);

      super.finalizeStart();
    }
    catch (IOException e)
    {
      if (debugEnabled())
      {
        TRACER.debugCaught(DebugLogLevel.ERROR, e);
      }
      Message errMessage = ERR_RS_DISCONNECTED_DURING_HANDSHAKE.get(
          String.valueOf(getReplicationServerId()),
          session.getReadableRemoteAddress());
      abortStart(errMessage);
    }
    catch (DirectoryException e)
    {
      if (debugEnabled())
      {
        TRACER.debugCaught(DebugLogLevel.ERROR, e);
      }
      abortStart(e.getMessageObject());
    }
    catch (Exception e)
    {
      if (debugEnabled())
      {
        TRACER.debugCaught(DebugLogLevel.ERROR, e);
      }
      abortStart(Message.raw(e.getLocalizedMessage()));
    }
    finally
    {
      if (replicationServerDomain != null &&
          replicationServerDomain.hasLock())
        replicationServerDomain.release();
    }
  }

  /**
   * Starts the handler from a remote ReplServerStart message received from
   * the remote replication server.
   * @param inReplServerStartMsg The provided ReplServerStart message received.
   */
  public void startFromRemoteRS(ReplServerStartMsg inReplServerStartMsg)
  {
    localGenerationId = -1;
    oldGenerationId = -100;
    try
    {
      // The initiator decides if the session is encrypted
      sslEncryption = processStartFromRemote(inReplServerStartMsg);

      // lock with timeout
      lockDomain(true);

      if (replicationServerDomain.isAlreadyConnectedToRS(this))
      {
        abortStart(null);
        return;
      }

      this.localGenerationId = replicationServerDomain.getGenerationId();
      ReplServerStartMsg outReplServerStartMsg = sendStartToRemote();

      logStartHandshakeRCVandSND(inReplServerStartMsg, outReplServerStartMsg);

      /*
      until here session is encrypted then it depends on the negotiation
      The session initiator decides whether to use SSL.
      */
      if (!sslEncryption)
        session.stopEncryption();

      TopologyMsg inTopoMsg = null;
      if (getProtocolVersion() > ProtocolVersion.REPLICATION_PROTOCOL_V1)
      {
        /*
        Only protocol version above V1 has a phase 2 handshake
        NOW PROCEED WITH SECOND PHASE OF HANDSHAKE:
        TopologyMsg then TopologyMsg (with a RS)
        wait and process Topo from remote RS
        */
        inTopoMsg = waitAndProcessTopoFromRemoteRS();
        if (inTopoMsg == null)
        {
          // Simultaneous cross connect.
          abortStart(null);
          return;
        }

        // send our own TopologyMsg to remote RS
        TopologyMsg outTopoMsg = replicationServerDomain
            .createTopologyMsgForRS();
        sendTopoInfo(outTopoMsg);

        logTopoHandshakeRCVandSND(inTopoMsg, outTopoMsg);
      }
      else
      {
        // Terminate connection from a V1 RS

        // if the remote RS and the local RS have the same genID
        // then it's ok and nothing else to do
        if (generationId == localGenerationId)
        {
          if (debugEnabled())
          {
            TRACER.debugInfo("In " + replicationServer.getMonitorInstanceName()
                + " " + this + " RS V1 with serverID=" + serverId
                + " is connected with the right generation ID");
          }
        } else
        {
          checkGenerationId();
        }
        /*
        Note: the supported scenario for V1->V2 upgrade is to upgrade 1 by 1
        all the servers of the topology. We prefer not not send a TopologyMsg
        for giving partial/false information to the V2 servers as for
        instance we don't have the connected DS of the V1 RS...When the V1
        RS will be upgraded in his turn, topo info will be sent and accurate.
        That way, there is  no risk to have false/incomplete information in
        other servers.
        */
      }

      replicationServerDomain.startMonitoringPublisher();

      registerIntoDomain();

      // Process TopologyMsg sent by remote RS: store matching new info
      // (this will also warn our connected DSs of the new received info)
      if (inTopoMsg!=null)
        replicationServerDomain.receiveTopoInfoFromRS(inTopoMsg, this, false);

      Message message = INFO_REPLICATION_SERVER_CONNECTION_FROM_RS
          .get(getReplicationServerId(), getServerId(),
              replicationServerDomain.getBaseDn(),
              session.getReadableRemoteAddress());
      logError(message);

      super.finalizeStart();
    }
    catch (IOException e)
    {
      if (debugEnabled())
      {
        TRACER.debugCaught(DebugLogLevel.ERROR, e);
      }
      Message errMessage = ERR_RS_DISCONNECTED_DURING_HANDSHAKE.get(
          Integer.toString(inReplServerStartMsg.getServerId()),
          Integer.toString(replicationServer.getServerId()));
      abortStart(errMessage);
    }
    catch (DirectoryException e)
    {
      if (debugEnabled())
      {
        TRACER.debugCaught(DebugLogLevel.ERROR, e);
      }
      abortStart(e.getMessageObject());
    }
    catch (Exception e)
    {
      if (debugEnabled())
      {
        TRACER.debugCaught(DebugLogLevel.ERROR, e);
      }
      abortStart(Message.raw(e.getLocalizedMessage()));
    }
    finally
    {
      if (replicationServerDomain != null &&
          replicationServerDomain.hasLock())
        replicationServerDomain.release();
    }
  }

  /**
   * Registers this handler into its related domain and notifies the domain.
   */
  private void registerIntoDomain()
  {
    // Alright, connected with new RS (either outgoing or incoming
    // connection): store handler.
    Map<Integer, ReplicationServerHandler> connectedRSs =
      replicationServerDomain.getConnectedRSs();
    connectedRSs.put(serverId, this);
  }

  /**
   * Wait receiving the TopologyMsg from the remote RS and process it.
   * @return the topologyMsg received or {@code null} if stop was received.
   * @throws DirectoryException
   */
  private TopologyMsg waitAndProcessTopoFromRemoteRS()
      throws DirectoryException
  {
    ReplicationMsg msg;
    try
    {
      msg = session.receive();
    }
    catch(Exception e)
    {
      Message message = Message.raw(e.getLocalizedMessage());
      throw new DirectoryException(ResultCode.OTHER, message);
    }

    if (!(msg instanceof TopologyMsg))
    {
      if (msg instanceof StopMsg)
      {
        // Remote replication server is probably shutting down, or cross
        // connection attempt.
        return null;
      }

      Message message = ERR_REPLICATION_PROTOCOL_MESSAGE_TYPE.get(
          msg.getClass().getCanonicalName(), "TopologyMsg");
      throw new DirectoryException(ResultCode.OTHER, message);
    }

    // Remote RS sent his topo msg
    TopologyMsg inTopoMsg = (TopologyMsg) msg;

    /* Store remote RS weight if it has one.
     * For protocol version < 4, use default value of 1 for weight
     */
    if (getProtocolVersion() >= ProtocolVersion.REPLICATION_PROTOCOL_V4)
    {
      // List should only contain RS info for sender
      RSInfo rsInfo = inTopoMsg.getRsList().get(0);
      weight = rsInfo.getWeight();
    }

    /*
    if the remote RS and the local RS have the same genID
    then it's ok and nothing else to do
    */
    if (generationId == localGenerationId)
    {
      if (debugEnabled())
      {
        TRACER.debugInfo("In " + replicationServer.getMonitorInstanceName()
            + " RS with serverID=" + serverId
            + " is connected with the right generation ID, same as local ="
            + generationId);
      }
    }
    else
    {
      checkGenerationId();
    }

    return inTopoMsg;
  }

  /**
   * Checks local generation ID against the remote RS one,
   * and logs Warning messages if needed.
   */
  private void checkGenerationId()
  {
    if (localGenerationId > 0)
    { // the local RS is initialized
      if (generationId > 0
          // the remote RS is initialized. If not, there's nothing to do anyway.
          && generationId != localGenerationId)
      {
        /* Either:
         *
         * 1) The 2 RS have different generationID
         * replicationServerDomain.getGenerationIdSavedStatus() == true
         *
         * if the present RS has received changes regarding its
         * gen ID and so won't change without a reset
         * then  we are just degrading the peer.
         *
         * 2) This RS has never received any changes for the current
         * generation ID.
         *
         * Example case:
         * - we are in RS1
         * - RS2 has genId2 from LS2 (genId2 <=> no data in LS2)
         * - RS1 has genId1 from LS1 /genId1 comes from data in suffix
         * - we are in RS1 and we receive a START msg from RS2
         * - Each RS keeps its genID / is degraded and when LS2
         * will be populated from LS1 everything will become ok.
         *
         * Issue:
         * FIXME : Would it be a good idea in some cases to just set the
         * gen ID received from the peer RS specially if the peer has a
         * non null state and we have a null state ?
         * replicationServerDomain.setGenerationId(generationId, false);
         */
        Message message = WARN_BAD_GENERATION_ID_FROM_RS.get(
            serverId, session.getReadableRemoteAddress(), generationId,
            getBaseDN(), getReplicationServerId(), localGenerationId);
        logError(message);
      }
    }
    else
    {
      /*
      The local RS is not initialized - take the one received
      WARNING: Must be done before computing topo message to send
      to peer server as topo message must embed valid generation id
      for our server
      */
      oldGenerationId =
          replicationServerDomain.changeGenerationId(generationId, false);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDataServer()
  {
    return false;
  }

  /**
   * Add the DSinfos of the connected Directory Servers
   * to the List of DSInfo provided as a parameter.
   *
   * @param dsInfos The List of DSInfo that should be updated
   *                with the DSInfo for the remoteDirectoryServers
   *                connected to this ServerHandler.
   */
  public void addDSInfos(List<DSInfo> dsInfos)
  {
    synchronized (remoteDirectoryServers)
    {
      for (LightweightServerHandler ls : remoteDirectoryServers.values())
      {
        dsInfos.add(ls.toDSInfo());
      }
    }
  }

  /**
   * Shutdown This ServerHandler.
   */
  @Override
  public void shutdown()
  {
    super.shutdown();
    // Stop the remote LSHandler
    synchronized (remoteDirectoryServers)
    {
      for (LightweightServerHandler lsh : remoteDirectoryServers.values())
      {
        lsh.stopHandler();
      }
      remoteDirectoryServers.clear();
    }
  }
  /**
   * Stores topology information received from a peer RS and that must be kept
   * in RS handler.
   *
   * @param topoMsg The received topology message
   */
  public void processTopoInfoFromRS(TopologyMsg topoMsg)
  {
    // Store info for remote RS
    List<RSInfo> rsInfos = topoMsg.getRsList();
    // List should only contain RS info for sender
    RSInfo rsInfo = rsInfos.get(0);
    generationId = rsInfo.getGenerationId();
    groupId = rsInfo.getGroupId();
    weight = rsInfo.getWeight();

    // Store info for DSs connected to the peer RS
    List<DSInfo> dsInfos = topoMsg.getDsList();

    synchronized (remoteDirectoryServers)
    {
      // Removes the existing structures
      for (LightweightServerHandler lsh : remoteDirectoryServers.values())
      {
        lsh.stopHandler();
      }
      remoteDirectoryServers.clear();

      // Creates the new structure according to the message received.
      for (DSInfo dsInfo : dsInfos)
      {
        LightweightServerHandler lsh = new LightweightServerHandler(this,
            serverId, dsInfo.getDsId(), dsInfo.getDsUrl(),
            dsInfo.getGenerationId(), dsInfo.getGroupId(), dsInfo.getStatus(),
            dsInfo.getRefUrls(), dsInfo.isAssured(), dsInfo.getAssuredMode(),
            dsInfo.getSafeDataLevel(), dsInfo.getEclIncludes(),
            dsInfo.getEclIncludesForDeletes(), dsInfo.getProtocolVersion());
        lsh.startHandler();
        remoteDirectoryServers.put(lsh.getServerId(), lsh);
      }
    }
  }

  /**
   * When this handler is connected to a replication server, specifies if
   * a wanted server is connected to this replication server.
   *
   * @param serverId The server we want to know if it is connected
   * to the replication server represented by this handler.
   * @return boolean True is the wanted server is connected to the server
   * represented by this handler.
   */
  public boolean isRemoteLDAPServer(int serverId)
  {
    synchronized (remoteDirectoryServers)
    {
      for (LightweightServerHandler server : remoteDirectoryServers.values())
      {
        if (serverId == server.getServerId())
        {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * When the handler is connected to a replication server, specifies the
   * replication server has remote LDAP servers connected to it.
   *
   * @return boolean True is the replication server has remote LDAP servers
   * connected to it.
   */
  public boolean hasRemoteLDAPServers()
  {
    synchronized (remoteDirectoryServers)
    {
      return !remoteDirectoryServers.isEmpty();
    }
  }

  /**
   * Return a Set containing the servers known by this replicationServer.
   * @return a set containing the servers known by this replicationServer.
   */
  public Set<Integer> getConnectedDirectoryServerIds()
  {
    synchronized (remoteDirectoryServers)
    {
      return remoteDirectoryServers.keySet();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getMonitorInstanceName()
  {
    return "Connected replication server RS(" + serverId + ") " + serverURL
        + ",cn=" + replicationServerDomain.getMonitorInstanceName();
  }

  /**
   * Retrieves a set of attributes containing monitor data that should be
   * returned to the client if the corresponding monitor entry is requested.
   *
   * @return  A set of attributes containing monitor data that should be
   *          returned to the client if the corresponding monitor entry is
   *          requested.
   */
  @Override
  public List<Attribute> getMonitorData()
  {
    // Get the generic ones
    List<Attribute> attributes = super.getMonitorData();

    // Add the specific RS ones
    attributes.add(Attributes.create("Replication-Server", serverURL));

    MonitorData md = replicationServerDomain.getDomainMonitorData();

    // Missing changes
    attributes.add(Attributes.create("missing-changes",
        String.valueOf(md.getMissingChangesRS(serverId))));

    // get the Server State
    AttributeBuilder builder = new AttributeBuilder("server-state");
    ServerState state = md.getRSStates(serverId);
    if (state != null)
    {
      for (String str : state.toStringSet())
      {
        builder.add(str);
      }
      attributes.add(builder.toAttribute());
    }

    return attributes;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString()
  {
    if (serverId != 0)
    {
      return "Replication server RS(" + serverId + ") for domain \""
          + replicationServerDomain.getBaseDn() + "\"";
    }
    return "Unknown server";
  }

  /**
   * Gets the status of the connected DS.
   * @return The status of the connected DS.
   */
  @Override
  public ServerStatus getStatus()
  {
    return ServerStatus.INVALID_STATUS;
  }

  /**
   * Retrieves the Address URL for this server handler.
   *
   * @return  The Address URL for this server handler,
   *          in the form of an IP address and port separated by a colon.
   */
  public String getServerAddressURL()
  {
    return serverAddressURL;
  }

  /**
   * Receives a topology msg.
   * @param topoMsg The message received.
   * @throws DirectoryException when it occurs.
   * @throws IOException when it occurs.
   */
  public void receiveTopoInfoFromRS(TopologyMsg topoMsg)
  throws DirectoryException, IOException
  {
    if (replicationServerDomain != null)
      replicationServerDomain.receiveTopoInfoFromRS(topoMsg, this, true);
  }
}
