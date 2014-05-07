/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2014 ForgeRock AS
 */
package org.opends.server.replication.server.changelog.file;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opends.server.loggers.debug.DebugTracer;
import org.opends.server.replication.common.CSN;
import org.opends.server.replication.protocol.UpdateMsg;
import org.opends.server.replication.server.ChangelogState;
import org.opends.server.replication.server.ReplicationServer;
import org.opends.server.replication.server.changelog.api.ChangeNumberIndexRecord;
import org.opends.server.replication.server.changelog.api.ChangelogException;
import org.opends.server.types.DN;
import org.opends.server.types.DirectoryException;
import org.opends.server.util.StaticUtils;

import static org.opends.server.loggers.ErrorLogger.*;
import static org.opends.server.loggers.debug.DebugLogger.*;
import static org.opends.server.util.StaticUtils.*;
import static org.opends.messages.ReplicationMessages.*;

/**
 * Represents the replication environment, which allows to manage the lifecycle
 * of the replication changelog.
 * <p>
 * A changelog has a root directory, under which the following directories and files are
 * created :
 * <ul>
 * <li>A "changenumberindex" directory containing the log files for
 * ChangeNumberIndexDB</li>
 * <li>A "domains.state" file containing a mapping of each domain DN to an id. The
 * id is used to name the corresponding domain directory.</li>
 * <li>One directory per domain, named after "[id].domain" where [id] is the id
 * assigned to the domain, as specified in the "domains.state" file.</li>
 * </ul>
 * <p>
 * Each domain directory contains the following directories and files :
 * <ul>
 * <li>A "generation_[id].id" file, where [id] is the generation id</li>
 * <li>One directory per server id, named after "[id].server" where [id] is the
 * id of the server. Each directory contains the log files for the given server
 * id.</li>
 * </ul>
 * All log files end with the ".log" suffix.
 * <p>
 * Layout example with two domains "o=test1" and "o=test2", each having server
 * ids 22 and 33 :
 *
 * <pre>
 * +---changelog
 * |   \---domains.state  [contains mapping: 1 => "o=test1", 2 => "o=test2"]
 * |   \---changenumberindex
 * |      \--- current.log
 * |   \---1.domain
 * |       \---generation1.id
 * |       \---22.server
 * |           \---current.log
 * |       \---33.server
 * |           \---current.log
 * |   \---2.domain
 * |       \---generation1.id
 * |       \---22.server
 * |           \---current.log
 * |       \---33.server
 * |           \---current.log
 * </pre>
 */
class ReplicationEnvironment
{
  private static final DebugTracer TRACER = getTracer();

  private static final int NO_GENERATION_ID = -1;

  private static final String CN_INDEX_DB_DIRNAME = "changenumberindex";

  private static final String DOMAINS_STATE_FILENAME = "domains.state";

  private static final String DOMAIN_STATE_SEPARATOR = ":";

  private static final String DOMAIN_SUFFIX = ".domain";

  private static final String SERVER_ID_SUFFIX = ".server";

  private static final String GENERATION_ID_FILE_PREFIX = "generation";

  private static final String GENERATION_ID_FILE_SUFFIX = ".id";

  private static final FileFilter DOMAIN_FILE_FILTER = new FileFilter()
  {
    @Override
    public boolean accept(File file)
    {
      return file.isDirectory() && file.getName().endsWith(DOMAIN_SUFFIX);
    }
  };

  private static final FileFilter SERVER_ID_FILE_FILTER = new FileFilter()
  {
    @Override
    public boolean accept(File file)
    {
      return file.isDirectory() && file.getName().endsWith(SERVER_ID_SUFFIX);
    }
  };

  private static final FileFilter GENERATION_ID_FILE_FILTER = new FileFilter()
  {
    @Override
    public boolean accept(File file)
    {
      return file.isFile()
          && file.getName().startsWith(GENERATION_ID_FILE_PREFIX)
          && file.getName().endsWith(GENERATION_ID_FILE_SUFFIX);
    }
  };

  /** Root path where the replication log is stored. */
  private final String replicationRootPath;

  /** The list of logs that are in use. */
  private final List<LogFile<?, ?>> logs = new CopyOnWriteArrayList<LogFile<?, ?>>();

  /** Maps each domain DN to a domain id that is used to name directory in file system. */
  private final Map<DN, String> domains = new HashMap<DN, String>();

  /** Exclusive lock to guard the domains mapping and change of state to a domain.*/
  // TODO : review the usefulness of this lock
  private final Object domainLock = new Object();

  /** The underlying replication server. */
  private final ReplicationServer replicationServer;

  private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

  /**
   * Creates the replication environment.
   *
   * @param rootPath
   *          Root path where replication log is stored.
   * @param replicationServer
   *          The underlying replication server.
   * @throws ChangelogException
   *           If an error occurs during initialization.
   */
  ReplicationEnvironment(final String rootPath,
      final ReplicationServer replicationServer) throws ChangelogException
  {
    this.replicationRootPath = rootPath;
    this.replicationServer = replicationServer;
  }

  /**
   * Returns the state of the replication changelog, which includes the list of
   * known servers and the generation id.
   *
   * @return the {@link ChangelogState}
   * @throws ChangelogException
   *           if a problem occurs while retrieving the state.
   */
  ChangelogState readChangelogState() throws ChangelogException
  {
    final ChangelogState state = new ChangelogState();
    final File changelogPath = new File(replicationRootPath);
    synchronized (domainLock)
    {
      readDomainsStateFile();
      checkDomainDirectories(changelogPath);
      for (final Entry<DN, String> domainEntry : domains.entrySet())
      {
        readStateForDomain(domainEntry, state);
      }
    }
    return state;
  }

  /**
   * Finds or creates the log used to store changes from the replication server
   * with the given serverId and the given baseDN.
   *
   * @param domainDN
   *          The DN that identifies the domain.
   * @param serverId
   *          The server id that identifies the server.
   * @param generationId
   *          The generationId associated to this domain.
   * @return the log.
   * @throws ChangelogException
   *           if an error occurs.
   */
  LogFile<CSN, UpdateMsg> getOrCreateReplicaDB(final DN domainDN, final int serverId, final long generationId)
      throws ChangelogException
  {
    if (debugEnabled())
    {
      debug("ReplicationEnvironment.getOrCreateReplicaDB(" + domainDN + ", " + serverId + ", " + generationId + ")");
    }

    try
    {
      ensureRootDirectoryExists();

      String domainId = null;
      synchronized (domainLock)
      {
        domainId = domains.get(domainDN);
        if (domainId == null)
        {
          domainId = createDomainId(domainDN);
        }

        final File serverIdPath = getServerIdPath(domainId, serverId);
        ensureServerIdDirectoryExists(serverIdPath);

        final File generationIdPath = getGenerationIdPath(domainId, generationId);
        ensureGenerationIdFileExists(generationIdPath);

        return openLog(serverIdPath, FileReplicaDB.RECORD_PARSER);
      }
    }
    catch (Exception e)
    {
      throw new ChangelogException(
          ERR_CHANGELOG_UNABLE_TO_CREATE_REPLICA_DB.get(domainDN.toString(), serverId, generationId), e);
    }
  }

  /**
   * Find or create the log to manage integer change number associated to
   * multidomain server state.
   * <p>
   * TODO: ECL how to manage compatibility of this db
   * with new domains added or removed ?
   *
   * @return the log.
   * @throws ChangelogException
   *           when a problem occurs.
   */
  LogFile<Long, ChangeNumberIndexRecord> getOrCreateCNIndexDB() throws ChangelogException
  {
    final File path = getCNIndexDBPath();
    try
    {
      return openLog(path, FileChangeNumberIndexDB.RECORD_PARSER);
    }
    catch (Exception e)
    {
      throw new ChangelogException(
          ERR_CHANGELOG_UNABLE_TO_CREATE_CN_INDEX_DB.get(replicationRootPath, path.getPath()), e);
    }
  }

  /**
   * Shutdown the environment.
   * <p>
   * The log DBs are not closed by this method. It assumes they are already
   * closed.
   */
  void shutdown()
  {
    if (isShuttingDown.compareAndSet(false, true))
    {
      logs.clear();
    }
  }

  /**
   * Clears the content of replication database.
   *
   * @param log
   *          The log to clear.
   */
  void clearDB(final LogFile<?, ?> log)
  {
    try
    {
      log.clear();
    }
    catch (ChangelogException e)
    {
      logError(ERR_ERROR_CLEARING_DB.get(log.getName(), stackTraceToSingleLineString(e)));
    }
  }

  /**
   * Clears the generated id associated to the provided domain DN from the state
   * Db.
   * <p>
   * If generation id can't be found, it is not considered as an error, the
   * method will just return.
   *
   * @param domainDN
   *          The domain DN for which the generationID must be cleared.
   * @throws ChangelogException
   *           If a problem occurs during clearing.
   */
  void clearGenerationId(final DN domainDN) throws ChangelogException
  {
    synchronized(domainLock)
    {
      final String domainId = domains.get(domainDN);
      final File idFile = retrieveGenerationIdFile(getDomainPath(domainId));
      if (idFile != null)
      {
        final boolean isDeleted = idFile.delete();
        if (!isDeleted)
        {
          throw new ChangelogException(
              ERR_CHANGELOG_UNABLE_TO_DELETE_GENERATION_ID_FILE.get(idFile.getPath(), domainDN.toString()));
        }
      }
    }
  }

  /**
   * Reset the generationId to the default value used when there is no
   * generation id.
   *
   * @param baseDN
   *          The baseDN for which the generationID must be reset.
   * @throws ChangelogException
   *           If a problem occurs during reset.
   */
  void resetGenerationId(final DN baseDN) throws ChangelogException
  {
    synchronized (domainLock)
    {
      clearGenerationId(baseDN);
      final String domainId = domains.get(baseDN);
      final File generationIdPath = getGenerationIdPath(domainId, NO_GENERATION_ID);
      ensureGenerationIdFileExists(generationIdPath);
    }
  }

  /** Reads the domain state file to find mapping between each domainDN and its associated domainId. */
  private void readDomainsStateFile() throws ChangelogException
  {
    final File domainsStateFile = new File(replicationRootPath, DOMAINS_STATE_FILENAME);
    if (domainsStateFile.exists())
    {
      BufferedReader reader = null;
      String line = null;
      try
      {
        reader = new BufferedReader(new InputStreamReader(new FileInputStream(domainsStateFile), "UTF-8"));
        while ((line = reader.readLine()) != null)
        {
          final int separatorPos = line.indexOf(DOMAIN_STATE_SEPARATOR);
          final String domainId = line.substring(0, separatorPos);
          final DN domainDN = DN.decode(line.substring(separatorPos+1));
          domains.put(domainDN, domainId);
        }
      }
      catch(DirectoryException e)
      {
        throw new ChangelogException(ERR_CHANGELOG_UNABLE_TO_DECODE_DN_FROM_DOMAIN_STATE_FILE.get(
            domainsStateFile.getPath(), line), e);
      }
      catch(Exception e)
      {
        throw new ChangelogException(ERR_CHANGELOG_UNABLE_TO_READ_DOMAIN_STATE_FILE.get(
            domainsStateFile.getPath()), e);
      }
      finally {
        StaticUtils.close(reader);
      }
    }
  }

  /**
   * Checks that domain directories in file system are consistent with
   * information from domains mapping.
   */
  private void checkDomainDirectories(final File changelogPath) throws ChangelogException
  {
    final File[] dnDirectories = changelogPath.listFiles(DOMAIN_FILE_FILTER);
    if (dnDirectories == null)
    {
      throw new ChangelogException(ERR_CHANGELOG_READ_STATE_WRONG_ROOT_PATH.get(replicationRootPath));
    }

    Set<String> domainIdsFromFileSystem = new HashSet<String>();
    for (final File dnDir : dnDirectories)
    {
      final String fileName = dnDir.getName();
      final String domainId = fileName.substring(0, fileName.length() - DOMAIN_SUFFIX.length());
      domainIdsFromFileSystem.add(domainId);
    }

    Set<String> expectedDomainIds = new HashSet<String>(domains.values());
    if (!domainIdsFromFileSystem.equals(expectedDomainIds))
    {
      throw new ChangelogException(ERR_CHANGELOG_INCOHERENT_DOMAIN_STATE.get(domains.values().toString(),
          domainIdsFromFileSystem.toString()));
    }
  }

  /**
   * Update the changelog state with the state corresponding to the provided
   * domain DN.
   */
  private void readStateForDomain(final Entry<DN, String> domainEntry, final ChangelogState result)
      throws ChangelogException
  {
    final File domainDirectory = getDomainPath(domainEntry.getValue());
    final String generationId = retrieveGenerationId(domainDirectory);
    if (generationId == null)
    {
      throw new ChangelogException(ERR_CHANGELOG_READ_STATE_NO_GENERATION_ID_FOUND.get(
          replicationRootPath, domainDirectory.getPath()));
    }
    final DN domainDN = domainEntry.getKey();
    result.setDomainGenerationId(domainDN, toGenerationId(generationId));

    final File[] serverIds = domainDirectory.listFiles(SERVER_ID_FILE_FILTER);
    if (serverIds == null)
    {
      throw new ChangelogException(ERR_CHANGELOG_READ_STATE_CANT_READ_DOMAIN_DIRECTORY.get(
          replicationRootPath, domainDirectory.getPath()));
    }
    for (final File serverId : serverIds)
    {
      result.addServerIdToDomain(toServerId(serverId.getName()), domainDN);
    }
  }

  private String createDomainId(final DN domainDN) throws ChangelogException
  {
    final String nextDomainId = findNextDomainId();
    domains.put(domainDN, nextDomainId);
    final File domainsStateFile = new File(replicationRootPath, DOMAINS_STATE_FILENAME);
    Writer writer = null;
    try
    {
      writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(domainsStateFile), "UTF-8"));
      for (final Entry<DN, String> entry : domains.entrySet())
      {
        writer.write(String.format("%s%s%s%n", entry.getValue(), DOMAIN_STATE_SEPARATOR, entry.getKey()));
      }
    }
    catch (Exception e)
    {
      throw new ChangelogException(ERR_CHANGELOG_UNABLE_TO_UPDATE_DOMAIN_STATE_FILE.get(nextDomainId,
          domainDN.toString(), domainsStateFile.getPath()), e);
    }
    finally
    {
      StaticUtils.close(writer);
    }
    return nextDomainId;
  }

  /** Find the next domain id to use. This is the lowest integer that is higher than all existing ids. */
  private String findNextDomainId()
  {
    int nextId = 1;
    for (final String domainId : domains.values())
    {
      final Integer id = Integer.valueOf(domainId);
      if (nextId <= id)
      {
        nextId = id + 1;
      }
    }
    return String.valueOf(nextId);
  }

  /** Open a log from the provided path and record parser. */
  private <K extends Comparable<K>, V> LogFile<K, V> openLog(final File serverIdPath, final RecordParser<K, V> parser)
      throws ChangelogException
  {
    checkShutDownBeforeOpening(serverIdPath);

    final LogFile<K, V> log = LogFile.newAppendableLogFile(serverIdPath, parser);

    checkShutDownAfterOpening(serverIdPath, log);

    logs.add(log);
    return log;
  }

  private void checkShutDownAfterOpening(final File serverIdPath, final LogFile<?, ?> log) throws ChangelogException
  {
    if (isShuttingDown.get())
    {
      closeDB(log);
      throw new ChangelogException(WARN_CANNOT_OPEN_DATABASE_BECAUSE_SHUTDOWN_WAS_REQUESTED.get(serverIdPath.getPath(),
          replicationServer.getServerId()));
    }
  }

  private void checkShutDownBeforeOpening(final File serverIdPath) throws ChangelogException
  {
    if (isShuttingDown.get())
    {
      throw new ChangelogException(
          WARN_CANNOT_OPEN_DATABASE_BECAUSE_SHUTDOWN_WAS_REQUESTED.get(
              serverIdPath.getPath(), replicationServer.getServerId()));
    }
  }

  /**
   * Retrieve the generation id from the provided directory.
   *
   * @return the generation id or {@code null} if the corresponding file can't
   *         be found
   */
  private String retrieveGenerationId(final File directory)
  {
    final File generationId = retrieveGenerationIdFile(directory);
    if (generationId != null)
    {
      String filename = generationId.getName();
      return filename.substring(GENERATION_ID_FILE_PREFIX.length(),
         filename.length() - GENERATION_ID_FILE_SUFFIX.length());
    }
    return null;
  }

  /**
   * Retrieve the file named after the generation id from the provided
   * directory.
   *
   * @return the generation id file or {@code null} if the corresponding file
   *         can't be found
   */
  private File retrieveGenerationIdFile(final File directory)
  {
    File[] generationIds = directory.listFiles(GENERATION_ID_FILE_FILTER);
    return (generationIds != null && generationIds.length > 0) ? generationIds[0] : null;
  }

  private File getDomainPath(final String domainId)
  {
    return new File(replicationRootPath, domainId + DOMAIN_SUFFIX);
  }

  private File getServerIdPath(final String domainId, final int serverId)
  {
    return new File(getDomainPath(domainId), String.valueOf(serverId) + SERVER_ID_SUFFIX);
  }

  private File getGenerationIdPath(final String domainId, final long generationId)
  {
    return new File(getDomainPath(domainId), GENERATION_ID_FILE_PREFIX + generationId + GENERATION_ID_FILE_SUFFIX);
  }

  private File getCNIndexDBPath()
  {
    return new File(replicationRootPath, CN_INDEX_DB_DIRNAME);
  }

  private void closeDB(final LogFile<?, ?> log)
  {
    logs.remove(log);
    log.close();
  }

  private void ensureRootDirectoryExists() throws ChangelogException
  {
    final File rootDir = new File(replicationRootPath);
    if (!rootDir.exists())
    {
      final boolean created = rootDir.mkdirs();
      if (!created)
      {
        throw new ChangelogException(ERR_CHANGELOG_UNABLE_TO_CREATE_LOG_DIRECTORY.get(replicationRootPath));
      }
    }
  }

  private void ensureServerIdDirectoryExists(final File serverIdPath) throws ChangelogException
  {
    if (!serverIdPath.exists())
    {
      boolean created = false;
      try
      {
        created = serverIdPath.mkdirs();
      }
      catch (Exception e)
      {
        // nothing to do
      }

      if (!created)
      {
        throw new ChangelogException(
            ERR_CHANGELOG_UNABLE_TO_CREATE_SERVER_ID_DIRECTORY.get(serverIdPath.getPath(), 0));
      }
    }
  }

  private void ensureGenerationIdFileExists(final File generationIdPath)
      throws ChangelogException
  {
    if (!generationIdPath.exists())
    {
      try
      {
        boolean isCreated = generationIdPath.createNewFile();
        if (!isCreated)
        {
          throw new ChangelogException(
              ERR_CHANGELOG_UNABLE_TO_CREATE_GENERATION_ID_FILE.get(generationIdPath.getPath()));
        }
      }
      catch (IOException e)
      {
        throw new ChangelogException(
            ERR_CHANGELOG_UNABLE_TO_CREATE_GENERATION_ID_FILE.get(generationIdPath.getPath()));
      }
    }
  }

  private void debug(String message)
  {
    // Replication server may be null when testing
    String monitorInstanceName = replicationServer != null ? replicationServer.getMonitorInstanceName() :
      "no monitor [test]";
    TRACER.debugInfo("In " + monitorInstanceName + ", " + message);
  }

  private int toServerId(final String serverIdName) throws ChangelogException
  {
    try
    {
      String serverId = serverIdName.substring(0, serverIdName.length() - SERVER_ID_SUFFIX.length());
      return Integer.parseInt(serverId);
    }
    catch (NumberFormatException e)
    {
      // should never happen
      throw new ChangelogException(ERR_CHANGELOG_SERVER_ID_FILENAME_WRONG_FORMAT.get(serverIdName), e);
    }
  }

  private long toGenerationId(final String data) throws ChangelogException
  {
    try
    {
      return Long.parseLong(data);
    }
    catch (NumberFormatException e)
    {
      // should never happen
      throw new ChangelogException(ERR_CHANGELOG_GENERATION_ID_WRONG_FORMAT.get(data), e);
    }
  }
}
